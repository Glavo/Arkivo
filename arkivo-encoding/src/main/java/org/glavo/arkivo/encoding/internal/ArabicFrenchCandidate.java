// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.internal.Weights.*;

/// Scores ArabicFrench text using the upstream state machine.
@NotNullByDefault
final class ArabicFrenchCandidate extends Candidate {
    /// Character classes and pair scores for this encoding.
    private final SingleByteData data;
    /// Previous character class used for adjacency scoring.
    private int prev;
    /// Capitalization pattern of the current word.
    private LatinCaseState caseState = LatinCaseState.SPACE;
    /// Whether the preceding byte was ASCII.
    private boolean prevAscii = true;
    /// Length of the current non-Latin word.
    private long currentWordLen;
    /// Length of the longest completed non-Latin word.
    private long longestWord;

    /// Creates an independent candidate from a shared model.
    ArabicFrenchCandidate(SingleByteData data) {
        super(data.charsetName());
        this.data = data;
    }

    @Override
    long step(int b) {
        long score = 0;
        var charClass = this.data.classify(b);
        if (charClass == 255) {
            return INVALID;
        }
        var caselessClass = charClass & 0x7F;
        var ascii = b < 0x80;
        var asciiPair = this.prevAscii && ascii;
        if (caselessClass != LATIN_LETTER) {
            this.caseState = LatinCaseState.SPACE;
        } else if ((charClass >> 7) == 0) {
            if (this.caseState == LatinCaseState.ALL_CAPS && !asciiPair) {
                score += IMPLAUSIBLE_LATIN_CASE_TRANSITION_PENALTY;
            }
            this.caseState = LatinCaseState.LOWER;
        } else {
            if (this.caseState == LatinCaseState.SPACE) {
                this.caseState = LatinCaseState.UPPER;
            } else if (this.caseState == LatinCaseState.UPPER || this.caseState == LatinCaseState.ALL_CAPS) {
                this.caseState = LatinCaseState.ALL_CAPS;
            } else if (this.caseState == LatinCaseState.LOWER) {
                if (!asciiPair) {
                    score += IMPLAUSIBLE_LATIN_CASE_TRANSITION_PENALTY;
                }
                this.caseState = LatinCaseState.UPPER;
            }
        }
        var nonAsciiAlphabetic = this.data.isNonLatinAlphabetic(caselessClass, true);
        if (nonAsciiAlphabetic) {
            this.currentWordLen += 1;
        } else {
            if (this.currentWordLen > this.longestWord) {
                this.longestWord = this.currentWordLen;
            }
            this.currentWordLen = 0;
        }
        if (!asciiPair) {
            score += this.data.score(caselessClass, this.prev, true);
            if (this.prev == LATIN_LETTER && nonAsciiAlphabetic) {
                score += LATIN_ADJACENCY_PENALTY;
            } else if (caselessClass == LATIN_LETTER
                    && this.data.isNonLatinAlphabetic(this.prev, true)) {
                score += LATIN_ADJACENCY_PENALTY;
            }
        }
        this.prevAscii = ascii;
        this.prev = caselessClass;
        return score;
    }

    @Override
    void finish() {
        accept(' ');
    }

    @Override
    long eligibleScore() {
        return longestWord < 2 ? INVALID : score;
    }
}
