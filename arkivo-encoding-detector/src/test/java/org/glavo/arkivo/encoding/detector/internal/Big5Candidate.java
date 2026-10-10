// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.detector.internal.Weights.*;

/// Scores Big5-HKSCS text using strict JDK decoding and the upstream character rules.
@NotNullByDefault
final class Big5Candidate extends DecodedCandidate {
    /// Previous character class used for adjacency scoring.
    private LatinCj prev = LatinCj.OTHER;
    /// Previous raw byte used to distinguish character repertoire levels.
    private int prevByte;
    /// Deferred score for an ambiguous character, or NO_PENDING_SCORE.
    private long pendingScore = NO_PENDING_SCORE;

    /// Creates an independent multibyte candidate.
    Big5Candidate() {
        super("Big5-HKSCS");
    }

    @Override
    long step(int b) {
        long score = 0;
        int written = decoder.accept(b);
        if (written < 0) {
            return INVALID;
        }
        if (written == 1) {
            var u = decoder.first();
            if ((u >= 'a' && u <= 'z')
                    || (u >= 'A' && u <= 'Z')) {
                this.pendingScore = NO_PENDING_SCORE;
                if (this.prev == LatinCj.CJ) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.ASCII_LETTER;
            } else if ((u >= 0x3400 && u < 0xA000) || (u >= 0xF900 && u < 0xFB00)) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                if ((this.prevByte >= 0xA4 && this.prevByte <= 0xC6)) {
                    score += this.maybeSetAsPending(BIG5_SCORE_PER_LEVEL_1_HANZI);
                } else {
                    score += this.maybeSetAsPending(BIG5_SCORE_PER_OTHER_HANZI);
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else {
                if (u == 0x3000 || u == 0x3001 || u == 0x3002 || u == 0xFF08 || u == 0xFF09 || u == 0xFF01 || u == 0xFF0C || u == 0xFF1B || u == 0xFF1F) {
                    if (this.pendingScore != NO_PENDING_SCORE) {
                        long pending = this.pendingScore;
                        score += pending;
                        this.pendingScore = NO_PENDING_SCORE;
                    }
                    score += CJ_PUNCTUATION;
                } else if ((u >= 0 && u <= 0x7F)) {
                    this.pendingScore = NO_PENDING_SCORE;
                } else {
                    if (this.pendingScore != NO_PENDING_SCORE) {
                        long pending = this.pendingScore;
                        score += pending;
                        this.pendingScore = NO_PENDING_SCORE;
                    }
                    score += CJK_OTHER;
                }
                this.prev = LatinCj.OTHER;
            }
        } else if (written == 2) {
            if (this.pendingScore != NO_PENDING_SCORE) {
                long pending = this.pendingScore;
                score += pending;
                this.pendingScore = NO_PENDING_SCORE;
            }
            if (decoder.first() == 0xCA || decoder.first() == 0xEA) {
                score += CJK_OTHER;
                this.prev = LatinCj.OTHER;
            } else {
                score += this.maybeSetAsPending(BIG5_SCORE_PER_OTHER_HANZI);
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            }
        }
        this.prevByte = b;
        return score;
    }

    /// Defers an ambiguous ideograph score until another non-ASCII character follows.
    private long maybeSetAsPending(long value) {
        assert pendingScore == NO_PENDING_SCORE;
        if (prev == LatinCj.CJ || !problematicLead(prevByte)) {
            return value;
        }
        pendingScore = value;
        return 0;
    }
}
