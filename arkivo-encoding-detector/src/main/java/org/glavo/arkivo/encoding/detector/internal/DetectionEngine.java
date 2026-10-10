// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/// Scores independent encoding candidates and applies continuous-text selection.
@NotNullByDefault
public final class DetectionEngine {
    /// Maximum number of supplied fields per evidence batch, including duplicates and ASCII fields.
    static final int MAX_EVIDENCE_FIELDS = 512;
    /// Maximum remaining byte count of one field in an evidence batch.
    static final int MAX_EVIDENCE_FIELD_BYTES = 4096;
    /// Maximum total remaining byte count of all supplied fields, before deduplication.
    static final int MAX_EVIDENCE_BYTES = 256 * 1024;
    /// Candidates in upstream tie-breaking order.
    private final Candidate[] candidates = {
            new DecodedCandidate("UTF-8"),
            new DecodedCandidate("ISO-2022-JP"),
            new VisualCandidate(Models.SINGLE_BYTE_DATA[13]),
            new GbkCandidate(),
            new EucJpCandidate(),
            new EucKrCandidate(),
            new ShiftJisCandidate(),
            new Big5Candidate(),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[7]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[3]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[1]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[2]),
            new ArabicFrenchCandidate(Models.SINGLE_BYTE_DATA[14]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[8]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[11]),
            new CaselessCandidate(Models.SINGLE_BYTE_DATA[19]),
            new LogicalCandidate(Models.SINGLE_BYTE_DATA[12]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[9]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[10]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[16]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[17]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[4]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[6]),
            new CaselessCandidate(Models.SINGLE_BYTE_DATA[15]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[0]),
            new LatinCandidate(Models.SINGLE_BYTE_DATA[18]),
            new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[5])
    };

    /// Creates an engine with optional ISO-2022-JP recognition.
    ///
    /// @param allowIso2022Jp whether escape-based Japanese detection is enabled
    public DetectionEngine(boolean allowIso2022Jp) {
        if (!allowIso2022Jp) {
            candidates[1].score = Candidate.INVALID;
        }
    }

    /// Scores one independent, complete field, including DOS candidates but excluding ISO-2022-JP.
    /// Empty and ASCII-only fields return no evidence without loading the model tables.
    /// Input position, limit, mark, and content are unchanged; input storage is not retained.
    ///
    /// Scores include negative and zero evidence and do not apply the prose scorer's minimum word length.
    /// Rejected candidates are omitted; alternative models for one charset contribute their maximum score.
    /// UTF-8 receives no unconditional preference here. Selection and fallback belong to the caller.
    /// The returned list is immutable and follows candidate order, not score order.
    ///
    /// @param input complete field between position and limit
    /// @return independent candidate scores; not a guarantee that an omitted charset cannot decode the field
    /// @throws NullPointerException if input is null
    static @Unmodifiable List<EncodingScore> scoreField(@UnmodifiableView ByteBuffer input) {
        int start = input.position();
        while (start < input.limit() && input.get(start) >= 0) {
            start++;
        }
        if (start == input.limit()) {
            return List.of();
        }
        var engine = new DetectionEngine(false);
        var dos = new Candidate[DosModels.VALUES.length];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new LatinCandidate(DosModels.VALUES[i]);
        }
        // Match the continuous detector's initial ASCII context without connecting separate fields.
        for (int index = Math.max(input.position(), start - 2); index < input.limit(); index++) {
            int value = input.get(index) & 255;
            engine.accept(value);
            for (var candidate : dos) {
                candidate.accept(value);
            }
        }
        engine.finish();
        for (var candidate : dos) {
            candidate.finish();
        }
        var scores = new LinkedHashMap<Charset, EncodingScore>();
        collectScores(engine.candidates, scores);
        collectScores(dos, scores);
        return List.copyOf(scores.values());
    }

    /// Combines models of the same charset without discarding short-word evidence.
    private static void collectScores(Candidate[] candidates, LinkedHashMap<Charset, EncodingScore> scores) {
        for (var candidate : candidates) {
            if (candidate.score != Candidate.INVALID) {
                var charset = Objects.requireNonNull(candidate.charset);
                var value = new EncodingScore(charset, candidate.score);
                scores.merge(charset, value, (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
    }

    /// Sums independently finished field scores, counting identical remaining byte sequences only once.
    /// ASCII-only fields supply no evidence. A rejected candidate contributes neither a score nor a count
    /// for that field, but can still contribute for other fields. Longer fields can have larger scores;
    /// this method does not normalize, select an encoding, or impose an encoding on an individual field.
    /// Results are immutable and sorted by charset name, independent of field order.
    ///
    /// Callers must not modify the list, buffer state, or contents during this call. Input buffers are
    /// borrowed only until return and are not changed. The returned summaries retain none of that storage.
    /// Oversized batches are rejected rather than silently sampled or truncated.
    ///
    /// @param inputs complete fields; at most MAX_EVIDENCE_FIELDS, each at most MAX_EVIDENCE_FIELD_BYTES,
    ///               and together at most MAX_EVIDENCE_BYTES bytes
    /// @return scores and contribution counts for candidates accepted in at least one non-ASCII field
    /// @throws NullPointerException if the list or an element is null
    /// @throws IllegalArgumentException if any batch limit is exceeded
    static @Unmodifiable List<EncodingEvidence> scoreFields(List<? extends ByteBuffer> inputs) {
        if (inputs.size() > MAX_EVIDENCE_FIELDS) {
            throw new IllegalArgumentException("Too many metadata fields");
        }
        int bytes = 0;
        for (var input : inputs) {
            if (input.remaining() > MAX_EVIDENCE_FIELD_BYTES) {
                throw new IllegalArgumentException("Metadata field exceeds evidence limit");
            }
            bytes += input.remaining();
        }
        if (bytes > MAX_EVIDENCE_BYTES) {
            throw new IllegalArgumentException("Metadata batch exceeds evidence limit");
        }
        var seen = new HashSet<ByteBuffer>();
        var evidence = new TreeMap<Charset, EncodingEvidence>();
        for (var input : inputs) {
            if (!seen.add(input.asReadOnlyBuffer())) {
                continue;
            }
            for (var score : scoreField(input)) {
                var value = new EncodingEvidence(score.charset(), score.score(), 1);
                evidence.merge(score.charset(), value, (left, right) -> new EncodingEvidence(
                        left.charset(), left.score() + right.score(), left.fields() + right.fields()));
            }
        }
        return List.copyOf(evidence.values());
    }

    /// Feeds an unsigned byte to every remaining candidate.
    ///
    /// @param value byte value from 0 through 255
    public void accept(int value) {
        for (var candidate : candidates) {
            candidate.accept(value);
        }
    }

    /// Checks incomplete sequences and applies end-of-field scoring.
    public void finish() {
        for (var candidate : candidates) {
            candidate.finish();
        }
    }

    /// Selects an encoding without domain-specific preferences.
    ///
    /// @param allowUtf8 whether valid UTF-8 takes precedence over statistical candidates
    /// @param nonAscii  whether any non-ASCII byte was seen
    /// @param escape    whether an escape byte was seen
    /// @return the guessed encoding, defaulting to Windows-1252
    public Charset guess(boolean allowUtf8, boolean nonAscii, boolean escape) {
        if (!nonAscii && escape && candidates[1].score != Candidate.INVALID) {
            return java.util.Objects.requireNonNull(candidates[1].charset);
        }
        if (candidates[0].score != Candidate.INVALID) {
            return allowUtf8 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        }
        Charset result = Charset.forName("windows-1252");
        long maximum = 0;
        for (int i = 3; i < candidates.length; i++) {
            var candidate = candidates[i];
            long score = candidate.eligibleScore();
            if (score > maximum) {
                maximum = score;
                result = java.util.Objects.requireNonNull(candidate.charset);
            }
        }
        var visual = (VisualCandidate) candidates[2];
        var logical = (LogicalCandidate) candidates[16];
        long visualScore = visual.eligibleScore();
        if (visualScore != Candidate.INVALID
                && (visualScore > maximum || result.name().equalsIgnoreCase("windows-1255"))
                && visual.plausiblePunctuation() > logical.plausiblePunctuation()) {
            result = java.util.Objects.requireNonNull(visual.charset);
        }
        return result;
    }
}
