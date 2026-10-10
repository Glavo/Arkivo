// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.internal.Weights.*;

/// Scores Latin text using the upstream state machine.
@NotNullByDefault
final class LatinCandidate extends Candidate {
    /// Character classes and pair scores for this encoding.
    private final SingleByteData data;
    /// Previous character class used for adjacency scoring.
    private int prev;
    /// Capitalization pattern of the current word.
    private LatinCaseState caseState = LatinCaseState.SPACE;
    /// Number of consecutive non-ASCII bytes preceding the next byte.
    private long prevNonAscii;
    /// Progress through a Western ordinal or abbreviation.
    private OrdinalState ordinalState = OrdinalState.SPACE;
    /// Whether Western ordinal and copyright bonuses apply.
    private final boolean windows1252;

    /// Creates an independent candidate from a shared model.
    LatinCandidate(SingleByteData data) {
        super(data.charsetName());
        this.data = data;
        windows1252 = data == Models.SINGLE_BYTE_DATA[7];
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
        var asciiPair = this.prevNonAscii == 0 && ascii;
        long nonAsciiPenalty = this.prevNonAscii <= 2 ? 0 : this.prevNonAscii == 3 ? -5 : this.prevNonAscii == 4 ? -20 : -200;
        score += nonAsciiPenalty;
        if (!this.data.isLatinAlphabetic(caselessClass)) {
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
        var asciiIshPair = asciiPair
                || (ascii && this.prev == 0)
                || (caselessClass == 0 && this.prevNonAscii == 0);
        if (!asciiIshPair) {
            score += this.data.score(caselessClass, this.prev, false);
        }
        if (this.windows1252) {
            // Recognize ordinals and abbreviations such as "Nº1" and "Mª".
            // Pair scores alone cannot distinguish them reliably from other Latin encodings.
            if (this.ordinalState == OrdinalState.OTHER) {
                if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                }
            } else if (this.ordinalState == OrdinalState.SPACE) {
                if (caselessClass == 0) {
                    // Keep the boundary state while scanning separators.
                } else if (b == 0xAA || b == 0xBA) {
                    this.ordinalState = OrdinalState.ORDINAL_EXPECTING_SPACE;
                } else if (b == 'M' || b == 'D' || b == 'S') {
                    this.ordinalState = OrdinalState.FEMININE_ABBREVIATION_START_LETTER;
                } else if (b == 'N') {
                    this.ordinalState = OrdinalState.UPPER_N;
                } else if (b == 'n') {
                    this.ordinalState = OrdinalState.LOWER_N;
                } else if (caselessClass == 100) {
                    this.ordinalState = OrdinalState.DIGIT;
                } else if (caselessClass == 9 || caselessClass == 22 || caselessClass == 24) {
                    this.ordinalState = OrdinalState.ROMAN;
                } else if (b == 0xA9) {
                    this.ordinalState = OrdinalState.COPYRIGHT;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.ORDINAL_EXPECTING_SPACE) {
                if (caselessClass == 0) {
                    score += ORDINAL_BONUS;
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.ORDINAL_EXPECTING_SPACE_UNDO_IMPLAUSIBILITY) {
                if (caselessClass == 0) {
                    score += ORDINAL_BONUS - IMPLAUSIBILITY_PENALTY;
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.ORDINAL_EXPECTING_SPACE_OR_DIGIT) {
                if (caselessClass == 0) {
                    score += ORDINAL_BONUS;
                    this.ordinalState = OrdinalState.SPACE;
                } else if (caselessClass == 100) {
                    score += ORDINAL_BONUS;
                    this.ordinalState = OrdinalState.OTHER;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.ORDINAL_EXPECTING_SPACE_OR_DIGIT_UNDO_IMPLAUSIBILITY) {
                if (caselessClass == 0) {
                    score += ORDINAL_BONUS - IMPLAUSIBILITY_PENALTY;
                    this.ordinalState = OrdinalState.SPACE;
                } else if (caselessClass == 100) {
                    score += ORDINAL_BONUS - IMPLAUSIBILITY_PENALTY;
                    this.ordinalState = OrdinalState.OTHER;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.UPPER_N) {
                if (b == 0xAA) {
                    this.ordinalState =
                            OrdinalState.ORDINAL_EXPECTING_SPACE_UNDO_IMPLAUSIBILITY;
                } else if (b == 0xBA) {
                    this.ordinalState =
                            OrdinalState.ORDINAL_EXPECTING_SPACE_OR_DIGIT_UNDO_IMPLAUSIBILITY;
                } else if (b == '.') {
                    this.ordinalState = OrdinalState.PERIOD_AFTER_N;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.LOWER_N) {
                if (b == 0xBA) {
                    this.ordinalState =
                            OrdinalState.ORDINAL_EXPECTING_SPACE_OR_DIGIT_UNDO_IMPLAUSIBILITY;
                } else if (b == '.') {
                    this.ordinalState = OrdinalState.PERIOD_AFTER_N;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.FEMININE_ABBREVIATION_START_LETTER) {
                if (b == 0xAA) {
                    this.ordinalState =
                            OrdinalState.ORDINAL_EXPECTING_SPACE_UNDO_IMPLAUSIBILITY;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.DIGIT) {
                if (b == 0xAA || b == 0xBA) {
                    this.ordinalState = OrdinalState.ORDINAL_EXPECTING_SPACE;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else if (caselessClass == 100) {
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.ROMAN) {
                if (b == 0xAA || b == 0xBA) {
                    this.ordinalState =
                            OrdinalState.ORDINAL_EXPECTING_SPACE_UNDO_IMPLAUSIBILITY;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else if (caselessClass == 9 || caselessClass == 22 || caselessClass == 24) {
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.PERIOD_AFTER_N) {
                if (b == 0xBA) {
                    this.ordinalState = OrdinalState.ORDINAL_EXPECTING_SPACE_OR_DIGIT;
                } else if (caselessClass == 0) {
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            } else if (this.ordinalState == OrdinalState.COPYRIGHT) {
                if (caselessClass == 0) {
                    score += COPYRIGHT_BONUS;
                    this.ordinalState = OrdinalState.SPACE;
                } else {
                    this.ordinalState = OrdinalState.OTHER;
                }
            }
        }
        if (ascii) {
            this.prevNonAscii = 0;
        } else {
            this.prevNonAscii += 1;
        }
        this.prev = caselessClass;
        return score;
    }

    @Override
    void finish() {
        accept(' ');
    }
}
