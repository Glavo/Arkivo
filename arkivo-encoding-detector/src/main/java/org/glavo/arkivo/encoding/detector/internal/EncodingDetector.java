// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Infers a common encoding from bounded archive metadata samples.
/// Fields contribute independent byte-pair profiles; decoder state never crosses field boundaries.
/// Buffers are borrowed only during a call and their state is preserved. Instances reuse mutable
/// workspaces and are not safe for concurrent use. Model data is immutable and shared lazily.
@NotNullByDefault
public final class EncodingDetector {
    /// Maximum fields in one statistical batch.
    public static final int MAX_EVIDENCE_FIELDS = 512;
    /// Maximum bytes in one batch field; single-field decoding accepts longer fields.
    public static final int MAX_EVIDENCE_FIELD_BYTES = 65535;
    /// Maximum total bytes in a statistical batch.
    public static final int MAX_EVIDENCE_BYTES = 256 * 1024;

    /// Selects path-component or continuous-comment feature boundaries.
    @NotNullByDefault
    public enum Kind {
        /// Pools distinct non-ASCII path components without pairs across separators.
        NAME,
        /// Uses continuous comment bytes.
        COMMENT
    }

    /// Breaks equal scores using known producer code-page conventions.
    @NotNullByDefault
    public enum Preference {
        /// Does not prefer a code-page family.
        NONE,
        /// Prefers DOS code pages.
        OEM,
        /// Prefers Windows single-byte code pages.
        ANSI;

        /// Tests family membership without inferring a language.
        /// @param charset candidate encoding
        /// @return whether the candidate belongs to the preferred family
        public boolean accepts(Charset charset) {
            String name = charset.name();
            return this == OEM ? name.startsWith("IBM")
                    : this == ANSI && (name.startsWith("windows-125") || name.equals("x-windows-874"));
        }
    }

    /// Tests the complete field without loading statistical data or changing its buffer state.
    /// @param input borrowed remaining bytes
    /// @return whether every byte is below 128
    public static boolean isAscii(ByteBuffer input) {
        for (int i = input.position(); i < input.limit(); i++) if (input.get(i) < 0) return false;
        return true;
    }

    /// Ranks installed candidates using pooled byte pairs and bounded UTF-8 structural evidence.
    /// Malformed UTF-8 samples exclude UTF-8; other candidates and unsampled fields require strict decoding.
    /// Zero-score candidates are omitted. Equal scores use the supplied family preference, then charset
    /// name order. Callers must validate a candidate against every field before freezing an archive choice.
    /// @param fields borrowed complete samples within the MAX_EVIDENCE limits
    /// @param kind field role
    /// @param preference tie-breaking producer convention
    /// @return immutable descending score list, empty when samples supply no scoring evidence
    /// @throws IllegalArgumentException if the sample count or byte budgets are exceeded
    public @Unmodifiable List<Score> rank(List<? extends ByteBuffer> fields, Kind kind, Preference preference) {
        return rank(fields, kind, preference, true);
    }

    /// Ranks full fields; single-field decoding bounds profile storage rather than truncating input bytes.
    private @Unmodifiable List<Score> rank(List<? extends ByteBuffer> fields, Kind kind, Preference preference,
                                          boolean checkLimits) {
        Objects.requireNonNull(preference);
        var utf8 = new Utf8Evidence();
        var profile = prepare(fields, kind, utf8, checkLimits);
        if (profile.pairs.length == 0 && (!utf8.valid || utf8.count == 0)) return List.of();
        var scores = new ArrayList<Score>();
        BigramModels.INSTANCE.candidates.forEach((charset, models) -> {
            boolean isUtf8 = charset.equals(StandardCharsets.UTF_8);
            if (isUtf8 && !utf8.valid) return;
            double best = 0;
            if (profile.norm != 0) {
                for (var model : models) {
                    best = Math.max(best, model.score(profile.pairs, profile.frequencies, profile.norm));
                }
            }
            if (isUtf8) best += UTF8_STRUCTURE_WEIGHT * utf8.count;
            if (best > 0) scores.add(new Score(charset, best));
        });
        scores.sort(Comparator.comparingDouble(Score::similarity).reversed()
                .thenComparing(score -> !preference.accepts(score.charset))
                .thenComparing(score -> score.charset.name()));
        return List.copyOf(scores);
    }

    /// Infers an encoding and decodes one complete field without previous-call evidence.
    /// ASCII fields are decoded directly without loading the statistical model.
    /// @param input borrowed bytes; position, limit, mark, and content are preserved
    /// @param kind field role
    /// @param preference producer convention used only for equal scores
    /// @return decoded text, or null for no evidence or distinct equally supported outputs
    public @Nullable String decode(ByteBuffer input, Kind kind, Preference preference) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(preference);
        if (isAscii(input)) return StandardCharsets.US_ASCII.decode(input.duplicate()).toString();
        var ranked = rank(List.of(input), kind, preference, false);
        @Nullable Score selected = null;
        @Nullable String text = null;
        double best = NO_SCORE;
        boolean ambiguous = false;
        for (var score : ranked) {
            // Character penalties can only lower a score, so unadjusted scores bound the remaining work.
            if (score.similarity < best) break;
            @Nullable String decoded = decode(input, kind, score.charset);
            if (decoded == null) continue;
            double adjusted = score.similarity * textQuality(decoded, kind);
            if (selected == null || adjusted > best || adjusted == best
                    && preference.accepts(score.charset) && !preference.accepts(selected.charset)) {
                selected = score;
                text = decoded;
                best = adjusted;
                ambiguous = false;
            } else if (adjusted == best && preference.accepts(score.charset) == preference.accepts(selected.charset)
                    && !decoded.equals(text)) ambiguous = true;
        }
        return ambiguous ? null : text;
    }

    /// Strictly decodes every byte without imposing filename or character-category restrictions.
    /// Validation and text construction share one decoder pass, including the unscored tail.
    /// @param input complete borrowed field; its state and content are preserved
    /// @param kind field role
    /// @param charset installed model candidate
    /// @return decoded text, or null for malformed input or unmappable bytes
    /// @throws IllegalArgumentException if the charset is not an installed model candidate
    public @Nullable String decode(ByteBuffer input, Kind kind, Charset charset) {
        Objects.requireNonNull(kind);
        var text = new StringBuilder(Math.min(input.remaining(), OUTPUT_CAPACITY));
        return decode(input, decoder(charset), text, null) ? text.toString() : null;
    }

    /// Returns a bounded score multiplier for decoded controls, not a text-validity decision.
    /// Each ISO control halves or further reduces support through {@code 1 / (1 + count)}, counting at
    /// most four controls. Tabs and line endings are not penalized in comments. Other character categories,
    /// including private-use characters, combining marks and format characters, are not penalized.
    /// @param text complete decoded field
    /// @param kind field role
    /// @return a multiplier from 0.2 through 1, inclusive
    public static double textQuality(String text, Kind kind) {
        Objects.requireNonNull(kind);
        int controls = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isISOControl(value) && !(kind == Kind.COMMENT
                    && (value == '\t' || value == '\n' || value == '\r')) && ++controls == 4) break;
        }
        return 1.0 / (1 + controls);
    }

    /// Finds candidates whose strict decoding equals an authoritative Unicode counterpart.
    /// @param input borrowed legacy bytes
    /// @param text authoritative text, compared without normalization
    /// @return immutable matching candidates, empty for ASCII or lossy legacy spellings
    public @Unmodifiable List<Charset> matching(ByteBuffer input, String text) {
        if (isAscii(input)) return List.of();
        var matches = new ArrayList<Charset>();
        for (var charset : BigramModels.INSTANCE.candidates.keySet()) {
            if (decode(input, decoder(charset), null, text)) matches.add(charset);
        }
        return List.copyOf(matches);
    }

    /// Maximum retained pair coordinates per component and total distinct coordinates admitted per field.
    static final int MAX_PROFILE_PAIRS = 4096;
    /// Unsigned pair occurrences admitted per field.
    private static final int REPETITION_LIMIT = 2;
    /// Maximum characters retained between decoder calls.
    private static final int OUTPUT_CAPACITY = 1024;
    /// Sentinel for the absence of a successfully decoded candidate.
    private static final double NO_SCORE = -1;
    /// Additive support per distinct non-ASCII UTF-8 scalar, capped at four scalars.
    private static final double UTF8_STRUCTURE_WEIGHT = 0.05;
    /// Reusable character storage, allocated only when validating a candidate.
    private @Nullable CharBuffer output;
    /// Decoders created only for candidates that have been validated by this instance.
    private @Nullable Map<Charset, CharsetDecoder> decoders;

    /// Creates a workspace without loading model data.
    public EncodingDetector() {
    }

    /// Pools capped field or path-component profiles; statistically identical contributions count once.
    /// The batch limits match the production evidence builder. The immutable result owns its arrays
    /// and retains neither buffers nor decoders. Integer accumulation is independent of input order.
    private Profile prepare(List<? extends ByteBuffer> fields, Kind kind, Utf8Evidence utf8, boolean checkLimits) {
        Objects.requireNonNull(kind);
        if (checkLimits) {
            if (fields.size() > MAX_EVIDENCE_FIELDS)
                throw new IllegalArgumentException("Too many metadata fields");
            int bytes = 0;
            for (var field : fields) {
                if (field.remaining() > MAX_EVIDENCE_FIELD_BYTES)
                    throw new IllegalArgumentException("Metadata field too large");
                bytes += field.remaining();
            }
            if (bytes > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("Metadata batch too large");
        }
        var distinct = new HashSet<Profile>();
        for (var field : fields) {
            if (!EncodingDetector.isAscii(field)) {
                utf8.accept(field);
                if (hasByteSweep(field)) continue;
                if (kind == Kind.COMMENT) {
                    Profile profile = profile(field, field.position(), field.limit(), kind, MAX_PROFILE_PAIRS);
                    if (profile.pairs.length != 0) distinct.add(profile);
                    continue;
                }
                int start = field.position();
                int budget = MAX_PROFILE_PAIRS;
                boolean nonAscii = false;
                for (int index = start; index <= field.limit() && budget > 0; index++) {
                    if (index == field.limit() || field.get(index) == '/') {
                        if (nonAscii) {
                            Profile profile = profile(field, start, index, kind, budget);
                            if (profile.pairs.length != 0) distinct.add(profile);
                            budget -= Math.max(1, profile.pairs.length);
                        }
                        if (index == field.limit()) break;
                        start = index + 1;
                        nonAscii = false;
                    } else if (field.get(index) < 0) {
                        nonAscii = true;
                    }
                }
            }
        }
        if (distinct.isEmpty()) return Profile.EMPTY;
        if (distinct.size() == 1) return distinct.iterator().next();
        int coordinates = 0;
        for (var profile : distinct) coordinates += profile.pairs.length;
        // Use less than one dense table's storage for sparse batches, without boxed counters.
        if (coordinates < BigramModels.DIMENSIONS / 2) {
            long[] packed = new long[coordinates];
            int index = 0;
            for (var profile : distinct) {
                for (int i = 0; i < profile.pairs.length; i++) {
                    packed[index++] = (long) profile.pairs[i] << 32 | profile.frequencies[i];
                }
            }
            return Profile.fromPacked(packed);
        }
        int[] frequencies = new int[BigramModels.DIMENSIONS];
        for (var profile : distinct) {
            for (int i = 0; i < profile.pairs.length; i++) frequencies[profile.pairs[i]] += profile.frequencies[i];
        }
        return Profile.fromDense(frequencies);
    }

    /// Excludes runs of at least eight consecutive high byte values from linguistic evidence.
    /// Such encoding-table samples remain subject to full validation but cannot establish a language.
    private static boolean hasByteSweep(ByteBuffer input) {
        int ascending = 1;
        int descending = 1;
        for (int index = input.position() + 1; index < input.limit(); index++) {
            int previous = input.get(index - 1) & 255;
            int current = input.get(index) & 255;
            ascending = previous >= 128 && current == previous + 1 ? ascending + 1 : 1;
            descending = current >= 128 && current == previous - 1 ? descending + 1 : 1;
            if (ascending >= 8 || descending >= 8) return true;
        }
        return false;
    }

    /// Builds a sorted sparse IDF-weighted profile without touching the caller's position or mark.
    /// ASCII-only pairs do not consume a name's profile budget, even in a long component. Backslash
    /// remains a byte, since it can be a trail byte in several candidate encodings.
    private static Profile profile(ByteBuffer input, int start, int end, Kind kind, int budget) {
        char[] pairs = new char[Math.min(budget, Math.max(0, end - start - 1))];
        int count = 0;
        for (int index = start + 1; index < end && count < pairs.length; index++) {
            int pair = ((input.get(index - 1) & 255) << 8) | (input.get(index) & 255);
            // Names omit pairs consisting entirely of ASCII bytes.
            if (kind == Kind.COMMENT || (pair & 0x8080) != 0) pairs[count++] = (char) pair;
        }
        if (count == 0) return Profile.EMPTY;
        Arrays.sort(pairs, 0, count);
        var models = BigramModels.INSTANCE;
        int[] frequencies = new int[count];
        int distinct = 0;
        long squared = 0;
        for (int i = 0; i < count;) {
            char pair = pairs[i];
            int next = i + 1;
            while (next < count && pairs[next] == pair) next++;
            int value = models.idf(pair) * Math.min(REPETITION_LIMIT, next - i);
            if (value != 0) {
                pairs[distinct] = pair;
                frequencies[distinct++] = value;
                squared += (long) value * value;
            }
            i = next;
        }
        return new Profile(Arrays.copyOf(pairs, distinct), Arrays.copyOf(frequencies, distinct), Math.sqrt(squared));
    }

    /// Decodes once, either appending text or comparing it with an authoritative counterpart.
    /// A failed comparison or validation discards the current candidate; decoder state is reset on every call.
    private boolean decode(ByteBuffer input, CharsetDecoder decoder,
                           @Nullable StringBuilder text, @Nullable String expected) {
        decoder.reset();
        @Nullable CharBuffer buffer = output;
        if (buffer == null) output = buffer = CharBuffer.allocate(OUTPUT_CAPACITY);
        var bytes = input.duplicate();
        int index = 0;
        boolean decoding = true;
        while (true) {
            buffer.clear();
            var result = decoding ? decoder.decode(bytes, buffer, true) : decoder.flush(buffer);
            if (result.isError()) return false;
            buffer.flip();
            if (expected != null) {
                for (int i = 0; i < buffer.limit(); i++) {
                    if (index >= expected.length() || buffer.get(i) != expected.charAt(index++)) return false;
                }
            }
            if (text != null) text.append(buffer);
            if (result.isUnderflow()) {
                if (!decoding) return expected == null || index == expected.length();
                decoding = false;
            }
        }
    }

    /// Creates a strict decoder on first use and reuses it for subsequent fields.
    private CharsetDecoder decoder(Charset charset) {
        if (!BigramModels.INSTANCE.candidates.containsKey(charset)) throw new IllegalArgumentException("Unknown candidate");
        if (decoders == null) decoders = new HashMap<>();
        return decoders.computeIfAbsent(charset, Charset::newDecoder);
    }

    /// Describes a candidate's statistical and structural support before decoded-character penalties.
    /// @param charset installed candidate
    /// @param similarity language-model cosine score plus bounded structural support, not a probability
    @NotNullByDefault
    public record Score(Charset charset, double similarity) {
    }

    /// Validates independent UTF-8 fields and counts at most four distinct non-ASCII scalars.
    /// Repeated characters, fields and directory prefixes cannot increase the structural support.
    @NotNullByDefault
    private static final class Utf8Evidence {
        /// Distinct scalars retained only until the support cap is reached.
        private final int[] scalars = new int[4];
        /// Number of distinct retained scalars.
        private int count;
        /// Whether all fields seen so far satisfy UTF-8 syntax.
        private boolean valid = true;

        /// Creates an empty per-call structural accumulator.
        private Utf8Evidence() {
        }

        /// Checks RFC 3629 byte ranges, resetting sequence state at every field boundary.
        private void accept(ByteBuffer bytes) {
            if (!valid) return;
            int remaining = 0;
            int scalar = 0;
            int lower = 0x80;
            int upper = 0xbf;
            for (int i = bytes.position(); i < bytes.limit(); i++) {
                int value = bytes.get(i) & 255;
                if (remaining == 0) {
                    if (value < 0x80) continue;
                    if (value >= 0xc2 && value <= 0xdf) {
                        remaining = 1;
                        scalar = value & 0x1f;
                    } else if (value >= 0xe0 && value <= 0xef) {
                        remaining = 2;
                        scalar = value & 0x0f;
                        lower = value == 0xe0 ? 0xa0 : 0x80;
                        upper = value == 0xed ? 0x9f : 0xbf;
                    } else if (value >= 0xf0 && value <= 0xf4) {
                        remaining = 3;
                        scalar = value & 7;
                        lower = value == 0xf0 ? 0x90 : 0x80;
                        upper = value == 0xf4 ? 0x8f : 0xbf;
                    } else {
                        valid = false;
                        return;
                    }
                } else {
                    if (value < lower || value > upper) {
                        valid = false;
                        return;
                    }
                    scalar = scalar << 6 | value & 0x3f;
                    lower = 0x80;
                    upper = 0xbf;
                    if (--remaining == 0 && count < scalars.length) {
                        int index = 0;
                        while (index < count && scalars[index] != scalar) index++;
                        if (index == count) scalars[count++] = scalar;
                    }
                }
            }
            if (remaining != 0) valid = false;
        }
    }

    /// Stores sparse IDF-weighted counts, with value equality for duplicate evidence suppression.
    /// Arrays belong exclusively to this profile and must not be modified.
    /// @param pairs sorted byte-pair indices
    /// @param frequencies corresponding positive weighted counts
    /// @param norm L2 norm of weighted counts
    @NotNullByDefault
    private record Profile(char @Unmodifiable [] pairs, int @Unmodifiable [] frequencies, double norm) {
        /// Shared empty evidence, requiring no model initialization.
        static final Profile EMPTY = new Profile(new char[0], new int[0], 0);

        /// Sorts and merges owned packed pairs and weights without allocating a dense table.
        static Profile fromPacked(long[] packed) {
            Arrays.sort(packed);
            int count = 0;
            for (int index = 0; index < packed.length;) {
                long pair = packed[index] & 0xffff_ffff_0000_0000L;
                int sum = (int) packed[index++];
                while (index < packed.length && (packed[index] & 0xffff_ffff_0000_0000L) == pair) {
                    sum += (int) packed[index++];
                }
                packed[count++] = pair | sum;
            }
            char[] pairs = new char[count];
            int[] values = new int[count];
            long squared = 0;
            for (int index = 0; index < count; index++) {
                pairs[index] = (char) (packed[index] >>> 32);
                int value = (int) packed[index];
                values[index] = value;
                squared += (long) value * value;
            }
            return new Profile(pairs, values, Math.sqrt(squared));
        }

        /// Copies the nonzero coordinates of a batch workspace in ascending order.
        static Profile fromDense(int[] dense) {
            int count = 0;
            for (int value : dense) if (value != 0) count++;
            char[] pairs = new char[count];
            int[] values = new int[count];
            long squared = 0;
            int index = 0;
            for (int i = 0; i < dense.length; i++) {
                if (dense[i] != 0) {
                    pairs[index] = (char) i;
                    values[index++] = dense[i];
                    squared += (long) dense[i] * dense[i];
                }
            }
            return new Profile(pairs, values, Math.sqrt(squared));
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Profile profile && Arrays.equals(pairs, profile.pairs)
                    && Arrays.equals(frequencies, profile.frequencies);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(pairs) * 31 + Arrays.hashCode(frequencies);
        }
    }
}
