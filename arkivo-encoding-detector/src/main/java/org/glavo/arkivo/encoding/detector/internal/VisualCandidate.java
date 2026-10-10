// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.detector.internal.Weights.*;

/// Scores Visual text using the upstream state machine.
@NotNullByDefault
final class VisualCandidate extends Candidate {
    /// Character classes and pair scores for this encoding.
    private final SingleByteData data;
    /// Previous character class used for adjacency scoring.
    private int prev;
    /// Whether the preceding byte was ASCII.
    private boolean prevAscii = true;
    /// Whether the preceding byte was plausible punctuation.
    private boolean prevPunctuation;
    /// Number of punctuation placements consistent with this writing direction.
    private long plausiblePunctuation;
    /// Length of the current non-Latin word.
    private long currentWordLen;
    /// Length of the longest completed non-Latin word.
    private long longestWord;

    /// Creates an independent candidate from a shared model.
    VisualCandidate(SingleByteData data) {
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
        var nonAsciiAlphabetic = this.data.isNonLatinAlphabetic(caselessClass, false);
        if (nonAsciiAlphabetic) {
            this.currentWordLen += 1;
        } else {
            if (this.currentWordLen > this.longestWord) {
                this.longestWord = this.currentWordLen;
            }
            this.currentWordLen = 0;
        }
        if (!asciiPair) {
            score += this.data.score(caselessClass, this.prev, false);
            if (nonAsciiAlphabetic && this.prevPunctuation) {
                this.plausiblePunctuation += 1;
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
        this.prevPunctuation = caselessClass == 0 && isAsciiPunctuation(b);
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

    /// Returns punctuation consistent with the writing direction.
    long plausiblePunctuation() {
        return plausiblePunctuation;
    }

    /// Tests punctuation used by the Hebrew direction heuristic.
    private static boolean isAsciiPunctuation(int value) {
        return value == '.' || value == ',' || value == ':' || value == ';' || value == '?' || value == '!';
    }
}
