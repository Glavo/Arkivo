// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/// Maintains independent candidates and applies generic-domain chardetng selection.
@NotNullByDefault
public final class DetectionEngine {
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
