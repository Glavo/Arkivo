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
import java.util.List;
import java.util.Map;
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
public final class EncodingDetector {
    /// Maximum supplied fields per context batch, before deduplication.
    public static final int MAX_EVIDENCE_FIELDS = 512;
    /// Maximum raw bytes in one context sample.
    public static final int MAX_EVIDENCE_FIELD_BYTES = 4096;
    /// Maximum total raw bytes in a context batch, before deduplication.
    public static final int MAX_EVIDENCE_BYTES = 256 * 1024;
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
    public enum Kind {
        /// Basename evidence with decoded path, extension, and numeric boundaries.
        NAME,
        /// Continuous comment evidence without path segmentation.
        COMMENT
    }

    /// Creates an independent metadata workspace without loading statistical tables.
    public EncodingDetector() {
    }

    /// Supplies a producer's code-page family only when content evidence is tied.
    @NotNullByDefault
    public enum Preference {
        /// No producer information is available.
        NONE,
        /// Prefer DOS code pages among equally supported single-byte candidates.
        OEM,
        /// Prefer Windows code pages among equally supported single-byte candidates.
        ANSI;

        /// Tests the supported single-byte families; East Asian code pages remain neutral.
        private boolean accepts(Charset charset) {
            String name = charset.name();
            return this == OEM ? name.startsWith("IBM")
                    : this == ANSI && (name.startsWith("windows-125") || name.equals("x-windows-874"));
        }
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
    public List<EncodingScore> analyze(@UnmodifiableView ByteBuffer input, Kind kind) {
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
    ///
    /// @param input complete field; state and content are preserved
    /// @param kind metadata role
    /// @return heuristic selection, or null when evidence is insufficient
    public @Nullable Charset detect(@UnmodifiableView ByteBuffer input, Kind kind) {
        return detect(input, kind, null);
    }

    /// Selects a strictly valid encoding, or null when the retained evidence is insufficient or tied.
    /// ASCII-only fields return US-ASCII. Positive candidates are ranked by character plausibility and mean
    /// feature score. Legacy selections require multiple non-ASCII scalars or supporting context. Equal
    /// quality outputs are compared exactly, without normalization; equivalent outputs are not ambiguous.
    /// Context can break a quality tie or resolve a short field, but cannot override a stronger
    /// multi-character local result or admit a locally invalid candidate. No numeric confidence is returned.
    ///
    /// @param input   complete field; buffer state and content are preserved
    /// @param kind    role of the current field
    /// @param context optional immutable context of the same role
    /// @return heuristic selection, or null; format-specific fallback belongs to the caller
    /// @throws NullPointerException     if input or kind is null
    /// @throws IllegalArgumentException if context belongs to a different role
    public @Nullable Charset detect(@UnmodifiableView ByteBuffer input, Kind kind, @Nullable EncodingEvidence context) {
        return detect(input, kind, context, Preference.NONE);
    }

    /// Selects an encoding with an optional producer-family tie breaker; it never establishes a language.
    /// The preference cannot admit an invalid encoding or override stronger content evidence.
    ///
    /// @param input complete field; state and content are preserved
    /// @param kind metadata role
    /// @param context optional immutable evidence for the same role
    /// @param preference producer-family hint, applied only to tied candidates
    /// @return heuristic selection, or null when evidence is insufficient
    /// @throws IllegalArgumentException if the context belongs to a different role
    public @Nullable Charset detect(@UnmodifiableView ByteBuffer input, Kind kind,
                                    @Nullable EncodingEvidence context, Preference preference) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(preference);
        if (context != null && context.kind() != kind) throw new IllegalArgumentException("Metadata role mismatch");
        if (isAscii(input)) return StandardCharsets.US_ASCII;
        if (hasByteSweep(input)) return null;
        var scores = analyze(input, kind).stream()
                .filter(value -> value.strength() > 0 && value.evidence() > 0 && value.controls() == 0).toList();
        if (scores.isEmpty()) return null;
        var best = scores.get(0);
        for (var score : scores) if (score.compareQuality(best) > 0) best = score;
        var choices = new ArrayList<EncodingScore>();
        // A single multibyte character can look like several unrelated single-byte letters.
        boolean decisive = best.nonAscii() >= 2 && best.evidence() >= 2
                && scores.stream().noneMatch(value -> value.nonAscii() == 1);
        for (var score : scores) if (!decisive || score.compareQuality(best) == 0) choices.add(score);
        if (decisive) {
            var preferred = prefer(choices, preference);
            if (sameOutputs(input, preferred)) return preferred.get(0).charset();
        }
        long highest = best.strength();
        if (context == null || context.candidates().isEmpty()) return best.charset().equals(StandardCharsets.UTF_8)
                && sameOutputs(input, scores.stream().filter(value -> value.strength() == highest).toList())
                ? StandardCharsets.UTF_8 : null;
        long strongestScore = 0;
        int strongestFields = 0;
        var supported = new ArrayList<EncodingScore>();
        for (var choice : choices) {
            for (var support : context.candidates()) {
                if (!support.charset().equals(choice.charset())) continue;
                boolean own = support.contributions().containsKey(choice);
                int fields = support.fields() - (own ? 1 : 0);
                if (fields < 2) continue;
                long value = support.score() - support.contributions().getOrDefault(choice, 0L);
                int order = strongestFields == 0 ? 1 : Long.compare(value * strongestFields, strongestScore * fields);
                if (order > 0) {
                    strongestScore = value;
                    strongestFields = fields;
                    supported.clear();
                }
                if (order >= 0) supported.add(choice);
            }
        }
        supported = prefer(supported, preference);
        return !supported.isEmpty() && sameOutputs(input, supported) ? supported.get(0).charset() : null;
    }

    /// Returns the highest content score from a nonempty list.
    private static EncodingScore bestScore(List<EncodingScore> scores) {
        var best = scores.get(0);
        for (var score : scores) if (score.compareQuality(best) > 0) best = score;
        return best;
    }

    /// Restricts an already tied set only when a producer-family candidate is present.
    private static ArrayList<EncodingScore> prefer(List<EncodingScore> choices, Preference preference) {
        var preferred = new ArrayList<EncodingScore>();
        for (var choice : choices) if (preference.accepts(choice.charset())) preferred.add(choice);
        return preferred.isEmpty() ? new ArrayList<>(choices) : preferred;
    }

    /// Returns installed candidates whose strict decoding equals the complete supplied Unicode text.
    /// No temporary candidate strings are constructed and neither input is retained.
    /// ASCII-only input returns an empty list because it supplies no encoding evidence.
    ///
    /// @param input complete field; state and content are preserved
    /// @param text authoritative decoded counterpart
    /// @return matching candidates in charset-name order
    public @Unmodifiable List<Charset> matching(ByteBuffer input, String text) {
        var result = new ArrayList<Charset>();
        for (var score : analyze(input, Kind.NAME)) {
            var cursor = cursor(input, score.charset(), output);
            int index = 0;
            while (index < text.length() && cursor.read() == text.charAt(index)) index++;
            if (index == text.length() && cursor.read() == END) result.add(score.charset());
        }
        return List.copyOf(result);
    }

    /// Returns one candidate if all supplied candidates strictly produce the same complete text.
    /// Returns null for an empty list, an unavailable candidate, invalid bytes, or different outputs.
    /// Candidates must come from this detector's candidate set. Input is neither changed nor retained;
    /// comparisons use bounded character buffers instead of materializing candidate strings.
    ///
    /// @param input complete field; state and content are preserved
    /// @param candidates supported encodings to compare
    /// @return one candidate producing the shared text, or null
    public @Nullable Charset agreement(ByteBuffer input, List<Charset> candidates) {
        if (candidates.isEmpty()) return null;
        if (isAscii(input)) return candidates.get(0);
        var valid = analyze(input, Kind.NAME);
        var choices = new ArrayList<EncodingScore>();
        for (var charset : candidates) {
            @Nullable EncodingScore selected = null;
            for (var score : valid) if (score.charset().equals(charset)) selected = score;
            if (selected == null) return null;
            if (!choices.contains(selected)) choices.add(selected);
        }
        return sameOutputs(input, choices) ? choices.get(0).charset() : null;
    }

    /// Builds bounded score-vector context without connecting decoder state across fields.
    /// Batches exceeding any MAX_EVIDENCE limit are rejected, not sampled. Fields are processed in byte
    /// order for deterministic results. Exact byte duplicates and repeated per-candidate feature signatures
    /// contribute once; signatures reduce repetition but do not prove text equality. Name signatures ignore
    /// numbered suffixes and ASCII extensions at scoring boundaries. Comments are collected separately.
    /// Rejected candidates in one field remain eligible for other fields. The summary retains no input.
    ///
    /// @param inputs complete fields, unchanged and borrowed only for this call
    /// @param kind   metadata role; callers must group names and comments separately
    /// @return immutable per-candidate relative scores
    /// @throws NullPointerException     if the list, kind, or an element is null
    /// @throws IllegalArgumentException if a batch limit is exceeded
    public EncodingEvidence prepare(List<? extends ByteBuffer> inputs, Kind kind) {
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
        var support = new TreeMap<Charset, Map<EncodingScore, Long>>();
        @Nullable ByteBuffer previous = null;
        for (var field : fields) {
            if (field.equals(previous)) continue;
            previous = field;
            if (hasByteSweep(field)) continue;
            var scores = analyze(field, kind);
            if (scores.isEmpty()) continue;
            long maximum = bestScore(scores).strength();
            for (var score : scores) {
                if (score.evidence() == 0) continue;
                // Clip contrary evidence so one exceptional field does not veto the archive.
                long relative = Math.max(-64 * 256L, score.strength() - maximum);
                support.computeIfAbsent(score.charset(), ignored -> new HashMap<>()).putIfAbsent(score, relative);
            }
        }
        var candidates = new ArrayList<EncodingEvidence.Support>();
        support.forEach((charset, values) -> candidates.add(new EncodingEvidence.Support(charset,
                values.values().stream().mapToLong(Long::longValue).sum(), values.size(), values)));
        return new EncodingEvidence(kind, candidates);
    }

    /// Tests bytes without consuming the field or loading models.
    ///
    /// @param input bytes between position and limit
    /// @return whether every byte is in the range 0 through 127
    public static boolean isAscii(ByteBuffer input) {
        for (int i = input.position(); i < input.limit(); i++) if (input.get(i) < 0) return false;
        return true;
    }

    /// Excludes byte-table runs from language inference without declaring them malformed.
    /// Eight consecutive ascending or descending high bytes identify a code-page sweep, not useful
    /// linguistic evidence. Such runs can otherwise masquerade as alphabetic text in several encodings.
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
