// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.detector.internal.Weights.*;

/// Scores x-windows-949 text using strict JDK decoding and the upstream character rules.
@NotNullByDefault
final class EucKrCandidate extends DecodedCandidate {
    /// Previous raw byte used to distinguish character repertoire levels.
    private int prevByte;
    /// Whether the preceding byte was in the EUC range 0xA1 through 0xFE.
    private boolean prevWasEucRange;
    /// Previous character class used for adjacency scoring.
    private LatinKorean prev = LatinKorean.OTHER;
    /// Length of the current non-Latin word.
    private long currentWordLen;
    /// Deferred score for an ambiguous character, or NO_PENDING_SCORE.
    private long pendingScore = NO_PENDING_SCORE;

    /// Creates an independent multibyte candidate.
    EucKrCandidate() {
        super("x-windows-949");
    }

    @Override
    long step(int b) {
        long score = 0;
        var inEucRange = b >= 0xA1 && b <= 0xFE;
        int written = decoder.accept(b);
        if (written < 0) {
            return INVALID;
        }
        if (written > 0) {
            var u = decoder.first();
            if ((u >= 'a' && u <= 'z')
                    || (u >= 'A' && u <= 'Z')) {
                this.pendingScore = NO_PENDING_SCORE;
                if (this.prev == LatinKorean.HANGUL || this.prev == LatinKorean.HANJA) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                } else {
                }
                this.prev = LatinKorean.ASCII_LETTER;
                this.currentWordLen = 0;
            } else if (u >= 0xAC00 && u <= 0xD7A3) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                if (this.prevWasEucRange && inEucRange) {
                    score += EUC_KR_SCORE_PER_EUC_HANGUL;
                    score += cjkExtraScore(u, Models.FREQUENT_HANGUL);
                } else {
                    score += this.maybeSetAsPending(EUC_KR_SCORE_PER_NON_EUC_HANGUL);
                }
                if (this.prev == LatinKorean.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinKorean.HANGUL;
                this.currentWordLen += 1;
                if (this.currentWordLen > 5) {
                    score += EUC_KR_LONG_WORD_PENALTY;
                }
            } else if ((u >= 0x4E00 && u < 0xAC00) || (u >= 0xF900 && u <= 0xFA0B)) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                score += EUC_KR_SCORE_PER_HANJA;
                if (this.prev == LatinKorean.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                } else if (this.prev == LatinKorean.HANGUL) {
                    score += EUC_KR_HANJA_AFTER_HANGUL_PENALTY;
                } else {
                }
                this.prev = LatinKorean.HANJA;
                this.currentWordLen += 1;
                if (this.currentWordLen > 5) {
                    score += EUC_KR_LONG_WORD_PENALTY;
                }
            } else {
                if (u >= 0x80) {
                    if (this.pendingScore != NO_PENDING_SCORE) {
                        long pending = this.pendingScore;
                        score += pending;
                        this.pendingScore = NO_PENDING_SCORE;
                    }
                    score += CJK_OTHER;
                } else {
                    this.pendingScore = NO_PENDING_SCORE;
                }
                this.prev = LatinKorean.OTHER;
                this.currentWordLen = 0;
            }
        }
        this.prevWasEucRange = inEucRange;
        this.prevByte = b;
        return score;
    }

    /// Defers an ambiguous ideograph score until another non-ASCII character follows.
    private long maybeSetAsPending(long value) {
        assert pendingScore == NO_PENDING_SCORE;
        if (prev == LatinKorean.HANGUL || !moreProblematicLead(prevByte)) {
            return value;
        }
        pendingScore = value;
        return 0;
    }
}
