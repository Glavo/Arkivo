// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.detector.internal.Weights.*;

/// Scores NonLatinCased text using the upstream state machine.
@NotNullByDefault
final class NonLatinCasedCandidate extends Candidate {
    /// Character classes and pair scores for this encoding.
    private final SingleByteData data;
    /// Previous character class used for adjacency scoring.
    private int prev;
    /// Capitalization pattern of the current word.
    private NonLatinCaseState caseState = NonLatinCaseState.SPACE;
    /// Whether the preceding byte was ASCII.
    private boolean prevAscii = true;
    /// Length of the current non-Latin word.
    private long currentWordLen;
    /// Length of the longest completed non-Latin word.
    private long longestWord;
    /// Whether IBM866 no-break-space suppression applies.
    private final boolean ibm866;
    /// Whether the preceding byte was 0xA0.
    private boolean prevWasA0;

    /// Creates an independent candidate from a shared model.
    NonLatinCasedCandidate(SingleByteData data) {
        super(data.charsetName());
        this.data = data;
        ibm866 = data == Models.SINGLE_BYTE_DATA[6];
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
        var nonAsciiAlphabetic = this.data.isNonLatinAlphabetic(caselessClass, false);
        // Case-pattern scores distinguish Greek from byte-compatible Cyrillic interpretations.
        // ASCII letters mark a mixed word but do not themselves receive capitalization bonuses.
        if (caselessClass == LATIN_LETTER) {
            this.caseState = NonLatinCaseState.MIX;
        } else if (!nonAsciiAlphabetic) {
            if (this.caseState == NonLatinCaseState.UPPER_LOWER) {
                score += NON_LATIN_CAPITALIZATION_BONUS;
            } else if (this.caseState == NonLatinCaseState.ALL_CAPS) {
                if (this.data == Models.SINGLE_BYTE_DATA[4]) {
                    score += NON_LATIN_ALL_CAPS_PENALTY;
                }
            } else if (this.caseState == NonLatinCaseState.MIX) {
                score += NON_LATIN_MIXED_CASE_PENALTY * this.currentWordLen;
            }
            this.caseState = NonLatinCaseState.SPACE;
        } else if ((charClass >> 7) == 0) {
            if (this.caseState == NonLatinCaseState.SPACE) {
                this.caseState = NonLatinCaseState.LOWER;
            } else if (this.caseState == NonLatinCaseState.UPPER) {
                this.caseState = NonLatinCaseState.UPPER_LOWER;
            } else if (this.caseState == NonLatinCaseState.ALL_CAPS) {
                this.caseState = NonLatinCaseState.MIX;
            }
        } else {
            if (this.caseState == NonLatinCaseState.SPACE) {
                this.caseState = NonLatinCaseState.UPPER;
            } else if (this.caseState == NonLatinCaseState.UPPER) {
                this.caseState = NonLatinCaseState.ALL_CAPS;
            } else if (this.caseState == NonLatinCaseState.LOWER || this.caseState == NonLatinCaseState.UPPER_LOWER) {
                this.caseState = NonLatinCaseState.MIX;
            }
        }
        if (nonAsciiAlphabetic) {
            this.currentWordLen += 1;
        } else {
            if (this.currentWordLen > this.longestWord) {
                this.longestWord = this.currentWordLen;
            }
            this.currentWordLen = 0;
        }
        var isA0 = b == 0xA0;
        if (!asciiPair) {
            if (!(this.ibm866
                    && ((isA0 && (this.prevWasA0 || this.prev == 0))
                    || caselessClass == 0 && this.prevWasA0))) {
                score += this.data.score(caselessClass, this.prev, false);
            }
            if (this.prev == LATIN_LETTER && nonAsciiAlphabetic) {
                score += LATIN_ADJACENCY_PENALTY;
            } else if (caselessClass == LATIN_LETTER
                    && this.data.isNonLatinAlphabetic(this.prev, false)) {
                score += LATIN_ADJACENCY_PENALTY;
            }
        }
        this.prevAscii = ascii;
        this.prev = caselessClass;
        this.prevWasA0 = isA0;
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
