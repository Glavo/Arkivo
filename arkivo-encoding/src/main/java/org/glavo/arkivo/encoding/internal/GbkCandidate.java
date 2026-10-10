// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.internal.Weights.*;

/// Scores GB18030 text using strict JDK decoding and the upstream character rules.
@NotNullByDefault
final class GbkCandidate extends DecodedCandidate {
    /// Previous raw byte used to distinguish character repertoire levels.
    private int prevByte;
    /// Previous character class used for adjacency scoring.
    private LatinCj prev = LatinCj.OTHER;
    /// Deferred score for an ambiguous character, or NO_PENDING_SCORE.
    private long pendingScore = NO_PENDING_SCORE;

    /// Creates an independent multibyte candidate.
    GbkCandidate() {
        super("GB18030");
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
            } else if (u == 0x20AC) {
                this.pendingScore = NO_PENDING_SCORE;
                this.prev = LatinCj.OTHER;
            } else if (u >= 0x4E00 && u <= 0x9FA5) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                if (b >= 0xA1 && b <= 0xFE) {
                    if ((this.prevByte >= 0xA1 && this.prevByte <= 0xD7)) {
                        score += GBK_SCORE_PER_LEVEL_1;
                        score +=
                                cjkExtraScore(u, Models.FREQUENT_SIMPLIFIED);
                    } else if ((this.prevByte >= 0xD8 && this.prevByte <= 0xFE)) {
                        score += GBK_SCORE_PER_LEVEL_2;
                    } else {
                        score += GBK_SCORE_PER_NON_EUC;
                    }
                } else {
                    score += this.maybeSetAsPending(GBK_SCORE_PER_NON_EUC);
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else if ((u >= 0x3400 && u < 0xA000) || (u >= 0xF900 && u < 0xFB00)) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else if (u >= 0xE000 && u < 0xF900) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                // These historical GB18030 private-use mappings receive ordinary ideograph scores.
                if ((u >= 0xE78D && u <= 0xE796) || (u >= 0xE816 && u <= 0xE818)
                        || u == 0xE81E || u == 0xE826 || u == 0xE82B || u == 0xE82C
                        || u == 0xE831 || u == 0xE832 || u == 0xE83B || u == 0xE843
                        || u == 0xE854 || u == 0xE855 || u == 0xE864) {
                    score += GBK_SCORE_PER_NON_EUC;
                    if (this.prev == LatinCj.ASCII_LETTER) {
                        score += CJK_LATIN_ADJACENCY_PENALTY;
                    }
                    this.prev = LatinCj.CJ;
                } else {
                    score += GBK_PUA_PENALTY;
                    this.prev = LatinCj.OTHER;
                }
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
            var u = decoder.first();
            if (u >= 0xDB80 && u <= 0xDBFF) {
                score += GBK_PUA_PENALTY;
                this.prev = LatinCj.OTHER;
            } else if (u >= 0xD480 && u < 0xD880) {
                score += GBK_SCORE_PER_NON_EUC;
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else {
                score += CJK_OTHER;
                this.prev = LatinCj.OTHER;
            }
        }
        this.prevByte = b;
        return score;
    }

    /// Defers an ambiguous ideograph score until another non-ASCII character follows.
    private long maybeSetAsPending(long value) {
        assert pendingScore == NO_PENDING_SCORE;
        if (prev == LatinCj.CJ || !moreProblematicLead(prevByte)) {
            return value;
        }
        pendingScore = value;
        return 0;
    }
}
