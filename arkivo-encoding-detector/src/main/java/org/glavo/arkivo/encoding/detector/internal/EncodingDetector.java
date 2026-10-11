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
    /// Maximum bytes in a sampled field; validation accepts longer fields.
    public static final int MAX_EVIDENCE_FIELD_BYTES = 4096;
    /// Maximum total bytes in a statistical batch.
    public static final int MAX_EVIDENCE_BYTES = 256 * 1024;

    /// Selects path-component or continuous-comment feature boundaries.
    @NotNullByDefault
    public enum Kind {
        /// Uses the last non-ASCII path component.
        NAME,
        /// Uses continuous comment bytes.
        COMMENT
    }

    /// Breaks equal statistical scores using known producer code-page conventions.
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

    /// Ranks installed candidates from pooled samples; this does not validate unsampled archive fields.
    /// Zero-score candidates are omitted. Equal scores use the supplied family preference, then charset
    /// name order. Callers must validate a candidate against every field before freezing an archive choice.
    /// @param fields borrowed complete samples within the MAX_EVIDENCE limits
    /// @param kind field role
    /// @param preference tie-breaking producer convention
    /// @return immutable descending score list, empty when samples supply no statistical evidence
    /// @throws IllegalArgumentException if the sample count or byte budgets are exceeded
    public @Unmodifiable List<Score> rank(List<? extends ByteBuffer> fields, Kind kind, Preference preference) {
        Objects.requireNonNull(preference);
        var profile = prepare(fields, kind);
        if (profile.pairs.length == 0) return List.of();
        var scores = new ArrayList<Score>();
        BigramModels.INSTANCE.candidates.forEach((charset, models) -> {
            double best = 0;
            for (var model : models) {
                best = Math.max(best, model.score(profile.pairs, profile.frequencies, profile.norm));
            }
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
        var ranked = rank(List.of(input.remaining() <= MAX_EVIDENCE_FIELD_BYTES ? input
                : input.slice(input.position(), MAX_EVIDENCE_FIELD_BYTES)), kind, preference);
        @Nullable Score selected = null;
        @Nullable String text = null;
        for (var score : ranked) {
            if (selected != null && (score.similarity < selected.similarity
                    || preference.accepts(selected.charset) && !preference.accepts(score.charset))) break;
            @Nullable String decoded = decode(input, kind, score.charset);
            if (decoded == null) continue;
            if (selected == null) {
                selected = score;
                text = decoded;
            } else if (!decoded.equals(text)) return null;
        }
        return text;
    }

    /// Strictly decodes every byte and rejects controls unsuitable for the metadata role.
    /// Validation and text construction share one decoder pass, including the unscored tail.
    /// @param input complete borrowed field; its state and content are preserved
    /// @param kind field role
    /// @param charset installed model candidate
    /// @return decoded text, or null for malformed input, unmappable bytes, or disallowed controls
    /// @throws IllegalArgumentException if the charset is not an installed model candidate
    public @Nullable String decode(ByteBuffer input, Kind kind, Charset charset) {
        Objects.requireNonNull(kind);
        var text = new StringBuilder(Math.min(input.remaining(), OUTPUT_CAPACITY));
        return decode(input, kind, decoder(charset), text, null) ? text.toString() : null;
    }

    /// Finds candidates whose strict decoding equals an authoritative Unicode counterpart.
    /// @param input borrowed legacy bytes
    /// @param text authoritative text, compared without normalization
    /// @return immutable matching candidates, empty for ASCII or lossy legacy spellings
    public @Unmodifiable List<Charset> matching(ByteBuffer input, String text) {
        if (isAscii(input)) return List.of();
        var matches = new ArrayList<Charset>();
        for (var charset : BigramModels.INSTANCE.candidates.keySet()) {
            if (decode(input, Kind.COMMENT, decoder(charset), null, text)) matches.add(charset);
        }
        return List.copyOf(matches);
    }

    /// Maximum raw bytes used for a field's statistical profile; validation is never truncated.
    static final int MAX_PROFILE_BYTES = 4096;
    /// Unsigned pair occurrences admitted per field.
    private static final int REPETITION_LIMIT = 2;
    /// Maximum characters retained between decoder calls.
    private static final int OUTPUT_CAPACITY = 1024;
    /// Reusable character storage, allocated only when validating a candidate.
    private @Nullable CharBuffer output;
    /// Decoders created only for candidates that have been validated by this instance.
    private @Nullable Map<Charset, CharsetDecoder> decoders;

    /// Creates a workspace without loading model data.
    public EncodingDetector() {
    }

    /// Pools independent capped field profiles; exact and statistically identical duplicates count once.
    /// The batch limits match the production evidence builder. The immutable result owns its arrays
    /// and retains neither buffers nor decoders. Integer accumulation is independent of input order.
    private Profile prepare(List<? extends ByteBuffer> fields, Kind kind) {
        Objects.requireNonNull(kind);
        if (fields.size() > EncodingDetector.MAX_EVIDENCE_FIELDS)
            throw new IllegalArgumentException("Too many metadata fields");
        int bytes = 0;
        for (var field : fields) {
            if (field.remaining() > EncodingDetector.MAX_EVIDENCE_FIELD_BYTES)
                throw new IllegalArgumentException("Metadata field too large");
            bytes += field.remaining();
        }
        if (bytes > EncodingDetector.MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("Metadata batch too large");
        var distinct = new HashSet<Profile>();
        for (var field : fields) {
            if (!EncodingDetector.isAscii(field) && !hasByteSweep(field)) {
                Profile profile = profile(field, kind);
                if (profile.pairs.length != 0) distinct.add(profile);
            }
        }
        if (distinct.isEmpty()) return Profile.EMPTY;
        if (distinct.size() == 1) return distinct.iterator().next();
        // A single bounded batch workspace replaces one dense frequency table per filename.
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
    /// Names use the last slash-delimited component with non-ASCII bytes. Backslash is not a raw
    /// separator because it can be a trail byte in several candidate encodings.
    private static Profile profile(ByteBuffer input, Kind kind) {
        Objects.requireNonNull(kind);
        int start = input.position();
        int end = input.limit();
        if (kind == Kind.NAME) {
            int component = start;
            int selectedStart = start;
            int selectedEnd = end;
            boolean nonAscii = false;
            for (int index = start;; index++) {
                if (index == end || input.get(index) == '/') {
                    if (nonAscii) {
                        selectedStart = component;
                        selectedEnd = index;
                    }
                    if (index == end) break;
                    component = index + 1;
                    nonAscii = false;
                } else if (input.get(index) < 0) {
                    nonAscii = true;
                }
            }
            start = selectedStart;
            end = selectedEnd;
        }
        end = start + Math.min(end - start, MAX_PROFILE_BYTES);
        char[] pairs = new char[Math.max(0, end - start - 1)];
        int count = 0;
        for (int index = start + 1; index < end; index++) {
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
    private boolean decode(ByteBuffer input, Kind kind, CharsetDecoder decoder,
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
            for (int i = 0; i < buffer.limit(); i++) {
                char value = buffer.get(i);
                if (Character.isISOControl(value) && !(kind == Kind.COMMENT
                        && (value == '\t' || value == '\n' || value == '\r'))) return false;
                if (expected != null && (index >= expected.length() || value != expected.charAt(index++))) return false;
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

    /// Describes a candidate's strongest language-model similarity; validity is checked separately.
    /// @param charset installed candidate
    /// @param similarity cosine score against the original model scale, not a correctness probability
    @NotNullByDefault
    public record Score(Charset charset, double similarity) {
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
