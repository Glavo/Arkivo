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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/// Analyzes complete archive metadata fields using reusable, bounded decoding workspaces.
/// No caller buffer is changed or retained. Instances are not safe for concurrent use; immutable
/// evidence summaries can be shared. Every operation starts independent field state, including after
/// an earlier field failed validation. ASCII-only input does not initialize statistical models.
///
/// This class neither processes format declarations nor supplies a fallback charset. A selected encoding
/// is heuristic, not an assertion about the producer. Names and comments use separate evidence profiles.
@NotNullByDefault
final class EncodingDetector {
    /// Maximum supplied fields per context batch, before deduplication.
    static final int MAX_EVIDENCE_FIELDS = 512;
    /// Maximum raw bytes in one context sample.
    static final int MAX_EVIDENCE_FIELD_BYTES = 4096;
    /// Maximum total raw bytes in a context batch, before deduplication.
    static final int MAX_EVIDENCE_BYTES = 256 * 1024;
    /// Sentinel returned by a decoded cursor at end of field.
    private static final int END = -1;
    /// Output storage shared across sequential candidate evaluations.
    private final CharBuffer output = CharBuffer.allocate(1024);
    /// Second output buffer used only for exact equivalence comparisons.
    private final CharBuffer comparison = CharBuffer.allocate(1024);
    /// Lazily initialized candidate workspaces; no per-field decoder allocation is required.
    private @Nullable
    @Unmodifiable List<MetadataScorer> scorers;

    /// Identifies the metadata role, independently of the archive format.
    @NotNullByDefault
    enum Kind {
        /// Basename evidence with decoded path, extension, and numeric boundaries.
        NAME,
        /// Continuous comment evidence without path segmentation.
        COMMENT
    }

    /// Creates an independent metadata workspace without loading statistical tables.
    EncodingDetector() {
    }

    /// Returns immutable scores for all candidates that strictly decode the complete field.
    /// Empty or ASCII-only fields return an empty list. Non-positive scores describe absent or contrary
    /// evidence, not malformed bytes. Candidate alternatives for one charset are merged by quality.
    /// At most 4096 scalars per comment or path component are scored; all bytes are validated.
    /// Names use the last component containing non-ASCII text, so repeated parent prefixes do not
    /// outweigh a non-ASCII basename. Scores are in charset-name order, not confidence order.
    ///
    /// @param input complete field between position and limit; mark and content are preserved
    /// @param kind  metadata role
    /// @return scores without references to caller storage
    /// @throws NullPointerException if an argument is null
    @Unmodifiable
    List<EncodingScore> analyze(@UnmodifiableView ByteBuffer input, Kind kind) {
        Objects.requireNonNull(kind);
        if (isAscii(input)) return List.of();
        var scores = new TreeMap<Charset, EncodingScore>();
        for (var scorer : scorers()) {
            @Nullable var value = scorer.score(input, kind, output);
            if (value != null) {
                scores.merge(value.charset(), value, (left, right) -> left.compareQuality(right) >= 0 ? left : right);
            }
        }
        return List.copyOf(scores.values());
    }

    /// Selects an encoding from one complete field without historical context or a fallback.
    /// Equivalent to [#detect(ByteBuffer, Kind, EncodingEvidence)] with null evidence.
    @Nullable Charset detect(@UnmodifiableView ByteBuffer input, Kind kind) {
        return detect(input, kind, null);
    }

    /// Selects a strictly valid encoding, or null when the retained evidence is insufficient or tied.
    /// ASCII-only fields return US-ASCII. Positive candidates are ranked by lexical consistency and mean
    /// feature score. At least two contributing features are needed without supporting context. Equal
    /// quality outputs are compared exactly, without normalization; equivalent outputs are not ambiguous.
    /// Context can break a quality tie or resolve a one-feature field, but cannot override a stronger
    /// multi-feature local result or admit a locally invalid candidate. No numeric confidence is returned.
    ///
    /// @param input   complete field; buffer state and content are preserved
    /// @param kind    role of the current field
    /// @param context optional immutable context of the same role
    /// @return heuristic selection, or null; format-specific fallback belongs to the caller
    /// @throws NullPointerException     if input or kind is null
    /// @throws IllegalArgumentException if context belongs to a different role
    @Nullable Charset detect(@UnmodifiableView ByteBuffer input, Kind kind, @Nullable EncodingEvidence context) {
        Objects.requireNonNull(kind);
        if (context != null && context.kind() != kind) throw new IllegalArgumentException("Metadata role mismatch");
        if (isAscii(input)) return StandardCharsets.US_ASCII;
        var scores = analyze(input, kind).stream()
                .filter(value -> value.score() > 0 && value.evidence() > 0 && value.suspicious() == 0).toList();
        if (scores.isEmpty()) return null;
        var best = scores.get(0);
        for (var score : scores) if (score.compareQuality(best) > 0) best = score;
        var choices = new ArrayList<EncodingScore>();
        for (var score : scores) if (best.evidence() < 2 || score.compareQuality(best) == 0) choices.add(score);
        if (best.evidence() >= 2 && sameOutputs(input, choices)) return choices.get(0).charset();
        if (context == null) return null;
        @Nullable EncodingEvidence.Support strongest = null;
        var supported = new ArrayList<EncodingScore>();
        for (var choice : choices) {
            for (var support : context.candidates()) {
                if (!support.charset().equals(choice.charset()) || support.fields() < 2 || support.rank() <= 0)
                    continue;
                int order = strongest == null ? 1 : Long.compare(support.rank() * strongest.fields(),
                        strongest.rank() * support.fields());
                if (order > 0) {
                    strongest = support;
                    supported.clear();
                }
                if (order >= 0) supported.add(choice);
            }
        }
        return !supported.isEmpty() && sameOutputs(input, supported) ? supported.get(0).charset() : null;
    }

    /// Builds bounded ordinal context without connecting decoder state across fields.
    /// Batches exceeding any MAX_EVIDENCE limit are rejected, not sampled. Fields are processed in byte
    /// order for deterministic results. Exact byte duplicates and repeated per-candidate feature signatures
    /// contribute once; signatures reduce repetition but do not prove text equality. Name signatures ignore
    /// numbered suffixes and ASCII extensions at scoring boundaries. Comments are collected separately.
    /// Rejected candidates in one field remain eligible for other fields. The summary retains no input.
    ///
    /// @param inputs complete fields, unchanged and borrowed only for this call
    /// @param kind   metadata role; callers must group names and comments separately
    /// @return immutable per-candidate ordinal support
    /// @throws NullPointerException     if the list, kind, or an element is null
    /// @throws IllegalArgumentException if a batch limit is exceeded
    EncodingEvidence prepare(List<? extends ByteBuffer> inputs, Kind kind) {
        Objects.requireNonNull(kind);
        if (inputs.size() > MAX_EVIDENCE_FIELDS) throw new IllegalArgumentException("Too many metadata fields");
        int bytes = 0;
        var fields = new ArrayList<ByteBuffer>(inputs.size());
        for (var input : inputs) {
            if (input.remaining() > MAX_EVIDENCE_FIELD_BYTES)
                throw new IllegalArgumentException("Metadata field too large");
            bytes += input.remaining();
            fields.add(input.asReadOnlyBuffer());
        }
        if (bytes > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("Metadata batch too large");
        fields.sort(ByteBuffer::compareTo);
        var seen = new HashMap<Charset, HashSet<EncodingScore>>();
        var support = new TreeMap<Charset, EncodingEvidence.Support>();
        @Nullable ByteBuffer previous = null;
        for (var field : fields) {
            if (field.equals(previous)) continue;
            previous = field;
            var scores = analyze(field, kind);
            for (var score : scores) {
                if (score.evidence() == 0 || !seen.computeIfAbsent(score.charset(), ignored -> new HashSet<>()).add(score))
                    continue;
                long rank = 0;
                for (var other : scores) {
                    if (other.evidence() > 0) rank += Integer.signum(score.compareQuality(other));
                }
                var value = new EncodingEvidence.Support(score.charset(), rank, 1);
                support.merge(score.charset(), value, (left, right) -> new EncodingEvidence.Support(
                        left.charset(), left.rank() + right.rank(), left.fields() + right.fields()));
            }
        }
        return new EncodingEvidence(kind, List.copyOf(support.values()));
    }

    /// Tests bytes without consuming the field or loading models.
    private static boolean isAscii(ByteBuffer input) {
        for (int i = input.position(); i < input.limit(); i++) if (input.get(i) < 0) return false;
        return true;
    }

    /// Allocates installed candidates once per detector; model tables are shared and immutable.
    private @Unmodifiable List<MetadataScorer> scorers() {
        if (scorers == null) {
            var values = new ArrayList<MetadataScorer>();
            add(values, "UTF-8", MetadataScorer.Family.UTF8);
            add(values, "GB18030", MetadataScorer.Family.CHINESE);
            add(values, "Big5-HKSCS", MetadataScorer.Family.BIG5);
            add(values, "windows-31j", MetadataScorer.Family.JAPANESE);
            add(values, "EUC-JP", MetadataScorer.Family.JAPANESE);
            add(values, "x-windows-949", MetadataScorer.Family.KOREAN);
            for (var group : List.of(Models.SINGLE_BYTE_DATA, DosModels.VALUES)) {
                for (var model : group) {
                    if (Charset.isSupported(model.charsetName())) values.add(new MetadataScorer(
                            Charset.forName(model.charsetName()), model, MetadataScorer.Family.SINGLE_BYTE));
                }
            }
            scorers = List.copyOf(values);
        }
        return scorers;
    }

    /// Adds a multibyte candidate only when its JDK charset is available.
    private static void add(List<MetadataScorer> values, String name, MetadataScorer.Family family) {
        if (Charset.isSupported(name)) values.add(new MetadataScorer(Charset.forName(name), null, family));
    }

    /// Tests decoded output equality exactly, without constructing a String per candidate.
    private boolean sameOutputs(ByteBuffer input, List<EncodingScore> choices) {
        for (int i = 1; i < choices.size(); i++) {
            var first = cursor(input, choices.get(0).charset(), output);
            var second = cursor(input, choices.get(i).charset(), comparison);
            while (true) {
                int left = first.read();
                if (left != second.read()) return false;
                if (left == END) break;
            }
        }
        return true;
    }

    /// Borrows a candidate decoder for output comparison after scoring has completed.
    private TextCursor cursor(ByteBuffer input, Charset charset, CharBuffer buffer) {
        for (var scorer : scorers()) {
            if (scorer.charset.equals(charset)) return new TextCursor(input, scorer.decoder, buffer);
        }
        throw new IllegalArgumentException("Charset is not a scored candidate");
    }

    /// Reads UTF-16 units through fixed storage; used only while comparing candidate outputs.
    @NotNullByDefault
    private static final class TextCursor {
        /// Borrowed input view, retained only by this call-local cursor.
        private final ByteBuffer input;
        /// Borrowed reusable decoder.
        private final CharsetDecoder decoder;
        /// Borrowed output workspace.
        private final CharBuffer buffer;
        /// Whether decoding has reached its final underflow.
        private boolean flushing;
        /// Whether decoder flushing has finished.
        private boolean finished;

        /// Starts an independent complete-field decoding pass.
        TextCursor(ByteBuffer input, CharsetDecoder decoder, CharBuffer buffer) {
            this.input = input.duplicate();
            this.decoder = decoder.reset();
            this.buffer = buffer;
            buffer.clear().limit(0);
        }

        /// Returns a UTF-16 unit or END; input must remain unchanged since validation.
        int read() {
            while (!buffer.hasRemaining()) {
                if (finished) return END;
                buffer.clear();
                var result = flushing ? decoder.flush(buffer) : decoder.decode(input, buffer, true);
                if (result.isError()) throw new IllegalStateException("Validated metadata changed during selection");
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
