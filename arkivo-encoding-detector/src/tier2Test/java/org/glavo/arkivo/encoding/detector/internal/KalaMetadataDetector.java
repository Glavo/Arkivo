// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/// Tests Kala's statistical core on independent metadata fields without changing ZIP defaults.
/// All candidates must strictly decode the complete field. Statistics ignore ASCII-only pairs,
/// cap repetitions, and never form pairs across fields. No MIME, markup, BOM, or UTF-8 shortcut
/// from the general file detector is used. Returned similarities are not confidence probabilities.
/// Instances reuse decoder workspaces and are not safe for concurrent use.
@NotNullByDefault
final class KalaMetadataDetector {
    /// Maximum raw bytes used for a field's statistical profile; validation is never truncated.
    static final int MAX_PROFILE_BYTES = 4096;
    /// Unsigned pair occurrences admitted per field.
    private static final int REPETITION_LIMIT = 2;
    /// Reusable character storage for strict validation and output comparison.
    private final CharBuffer output = CharBuffer.allocate(1024);
    /// Second character workspace for exact output comparisons.
    private final CharBuffer comparison = CharBuffer.allocate(1024);
    /// Lazily initialized installed candidates; ASCII does not allocate them or load models.
    private @Nullable @Unmodifiable List<Candidate> candidates;

    /// Creates a workspace without loading model data.
    KalaMetadataDetector() {
    }

    /// Returns strictly valid candidates with role-appropriate controls, sorted by descending cosine score.
    /// A supplied context contributes its pooled profile instead of an arbitrary weighted prior;
    /// this intentionally measures the archive-wide common-encoding hypothesis separately.
    /// Neither the field nor context is modified or retained.
    @Unmodifiable List<Score> analyze(@UnmodifiableView ByteBuffer input, EncodingDetector.Kind kind,
                                    @Nullable Context context) {
        Objects.requireNonNull(kind);
        if (context != null && context.kind != kind) throw new IllegalArgumentException("Metadata role mismatch");
        if (EncodingDetector.isAscii(input)) return List.of();
        Profile profile = context == null || context.profile.pairs.length == 0 ? profile(input, kind) : context.profile;
        var scores = new ArrayList<Score>();
        for (var candidate : candidates()) {
            if (!valid(input, kind, candidate.decoder)) continue;
            double best = 0;
            for (var model : candidate.models) {
                best = Math.max(best, model.score(profile.pairs, profile.frequencies, profile.norm));
            }
            scores.add(new Score(candidate.charset, best));
        }
        scores.sort(Comparator.comparingDouble(Score::similarity).reversed()
                .thenComparing(score -> score.charset.name()));
        return List.copyOf(scores);
    }

    /// Selects the highest positive score, abstaining if tied candidates decode differently.
    /// No threshold is tuned on the evaluation cases and no legacy fallback is supplied here.
    @Nullable Charset detect(ByteBuffer input, EncodingDetector.Kind kind, @Nullable Context context) {
        Objects.requireNonNull(kind);
        if (context != null && context.kind != kind) throw new IllegalArgumentException("Metadata role mismatch");
        if (EncodingDetector.isAscii(input)) return StandardCharsets.US_ASCII;
        var scores = analyze(input, kind, context);
        if (scores.isEmpty() || scores.get(0).similarity == 0) return null;
        var best = scores.get(0);
        for (int i = 1; i < scores.size() && scores.get(i).similarity == best.similarity; i++) {
            if (!sameOutput(input, best.charset, scores.get(i).charset)) return null;
        }
        return best.charset;
    }

    /// Pools independent capped field profiles; exact and statistically identical duplicates count once.
    /// The batch limits match the production evidence builder. The immutable result owns its arrays
    /// and retains neither buffers nor decoders. Integer accumulation is independent of input order.
    Context prepare(List<? extends ByteBuffer> fields, EncodingDetector.Kind kind) {
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
            if (!EncodingDetector.isAscii(field)) {
                Profile profile = profile(field, kind);
                if (profile.pairs.length != 0) distinct.add(profile);
            }
        }
        if (distinct.isEmpty()) return new Context(kind, Profile.EMPTY);
        // A single bounded batch workspace replaces one dense frequency table per filename.
        int[] frequencies = new int[KalaModels.DIMENSIONS];
        for (var profile : distinct) {
            for (int i = 0; i < profile.pairs.length; i++) frequencies[profile.pairs[i]] += profile.frequencies[i];
        }
        return new Context(kind, Profile.fromDense(frequencies));
    }

    /// Builds a sorted sparse IDF-weighted profile without touching the caller's position or mark.
    /// Names use the last slash-delimited component with non-ASCII bytes. Backslash is not a raw
    /// separator because it can be a trail byte in several candidate encodings.
    static Profile profile(ByteBuffer input, EncodingDetector.Kind kind) {
        Objects.requireNonNull(kind);
        int start = input.position();
        int end = input.limit();
        if (kind == EncodingDetector.Kind.NAME) {
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
            if (KalaModels.informative(pair)) pairs[count++] = (char) pair;
        }
        if (count == 0) return Profile.EMPTY;
        Arrays.sort(pairs, 0, count);
        var models = KalaModels.instance();
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

    /// Validates the complete field, including suffixes outside the statistical sample window.
    private boolean valid(ByteBuffer input, EncodingDetector.Kind kind, CharsetDecoder decoder) {
        decoder.reset();
        var bytes = input.duplicate();
        boolean decoding = true;
        while (true) {
            output.clear();
            var result = decoding ? decoder.decode(bytes, output, true) : decoder.flush(output);
            if (result.isError()) return false;
            output.flip();
            while (output.hasRemaining()) {
                char value = output.get();
                if (Character.isISOControl(value) && !(kind == EncodingDetector.Kind.COMMENT
                        && (value == '\t' || value == '\n' || value == '\r'))) return false;
            }
            if (result.isUnderflow()) {
                if (!decoding) return true;
                decoding = false;
            }
        }
    }

    /// Creates strict JDK decoders for the installed model candidates once per workspace.
    private @Unmodifiable List<Candidate> candidates() {
        if (candidates == null) {
            var result = new ArrayList<Candidate>();
            KalaModels.instance().candidates.forEach((charset, models) ->
                    result.add(new Candidate(charset, charset.newDecoder(), models)));
            candidates = List.copyOf(result);
        }
        return candidates;
    }

    /// Returns the reusable decoder for a scored candidate.
    private CharsetDecoder decoder(Charset charset) {
        for (var candidate : candidates()) if (candidate.charset.equals(charset)) return candidate.decoder;
        throw new IllegalArgumentException("Unknown candidate");
    }

    /// Compares complete decoded outputs through bounded buffers without materializing candidate strings.
    private boolean sameOutput(ByteBuffer input, Charset first, Charset second) {
        var left = new Cursor(input, decoder(first), output);
        var right = new Cursor(input, decoder(second), comparison);
        while (true) {
            int value = left.read();
            if (value != right.read()) return false;
            if (value == -1) return true;
        }
    }

    /// Describes a valid candidate's strongest language-model similarity.
    /// @param charset strictly validated candidate
    /// @param similarity projected cosine score, not a correctness probability
    @NotNullByDefault
    record Score(Charset charset, double similarity) {
    }

    /// Retains one decoder and shared model variants.
    /// @param charset candidate encoding
    /// @param decoder mutable field-local decoding state
    /// @param models immutable language variants
    @NotNullByDefault
    private record Candidate(Charset charset, CharsetDecoder decoder, @Unmodifiable List<KalaModels.Model> models) {
    }

    /// Stores an immutable pooled profile for one metadata role.
    /// @param kind metadata role
    /// @param profile field-independent joint evidence
    @NotNullByDefault
    record Context(EncodingDetector.Kind kind, Profile profile) {
    }

    /// Stores sparse IDF-weighted counts, with value equality for duplicate evidence suppression.
    /// Arrays belong exclusively to this profile and must not be modified.
    /// @param pairs sorted byte-pair indices
    /// @param frequencies corresponding positive weighted counts
    /// @param norm L2 norm of weighted counts
    @NotNullByDefault
    record Profile(char @Unmodifiable [] pairs, int @Unmodifiable [] frequencies, double norm) {
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

    /// Reads validated UTF-16 units without retaining caller storage after output comparison returns.
    @NotNullByDefault
    private static final class Cursor {
        /// Borrowed view used only during the comparison.
        private final ByteBuffer input;
        /// Borrowed candidate decoder.
        private final CharsetDecoder decoder;
        /// Borrowed output workspace.
        private final CharBuffer buffer;
        /// Whether decoding reached its final underflow.
        private boolean flushing;
        /// Whether decoder flushing is complete.
        private boolean finished;

        /// Resets a candidate and the associated character workspace.
        private Cursor(ByteBuffer input, CharsetDecoder decoder, CharBuffer buffer) {
            this.input = input.duplicate();
            this.decoder = decoder.reset();
            this.buffer = buffer;
            buffer.clear().limit(0);
        }

        /// Returns the next UTF-16 unit, or -1 after the complete field.
        private int read() {
            while (!buffer.hasRemaining()) {
                if (finished) return -1;
                buffer.clear();
                var result = flushing ? decoder.flush(buffer) : decoder.decode(input, buffer, true);
                if (result.isError()) throw new IllegalStateException("Validated input changed during comparison");
                if (result.isUnderflow()) {
                    if (flushing) finished = true;
                    else flushing = true;
                }
                buffer.flip();
            }
            return buffer.get();
        }
    }
}
