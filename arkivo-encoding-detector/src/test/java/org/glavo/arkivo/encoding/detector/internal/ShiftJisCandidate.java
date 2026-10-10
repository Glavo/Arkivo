// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.detector.internal.Weights.*;

/// Scores windows-31j text using strict JDK decoding and the upstream character rules.
@NotNullByDefault
final class ShiftJisCandidate extends DecodedCandidate {
    /// Whether the initial half-width-katakana penalty has been applied.
    private boolean halfWidthKatakanaSeen;
    /// Permitted voicing marks after the preceding half-width character.
    private HalfWidthKatakana halfWidthKatakanaState = HalfWidthKatakana.DAKUTEN_FORBIDDEN;
    /// Previous character class used for adjacency scoring.
    private LatinCj prev = LatinCj.OTHER;
    /// Previous raw byte used to distinguish character repertoire levels.
    private int prevByte;
    /// Deferred score for an ambiguous character, or NO_PENDING_SCORE.
    private long pendingScore = NO_PENDING_SCORE;

    /// Creates an independent multibyte candidate.
    ShiftJisCandidate() {
        super("windows-31j");
    }

    @Override
    long step(int b) {
        long score = 0;
        int written = decoder.accept(b);
        if (written < 0) {
            return INVALID;
        }
        if (written > 0) {
            var halfWidthKatakanaState = this.halfWidthKatakanaState;
            this.halfWidthKatakanaState = HalfWidthKatakana.DAKUTEN_FORBIDDEN;
            var u = decoder.first();
            if ((u >= 'a' && u <= 'z')
                    || (u >= 'A' && u <= 'Z')) {
                this.pendingScore = NO_PENDING_SCORE;
                if (this.prev == LatinCj.CJ) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.ASCII_LETTER;
            } else if (u >= 0xFF61 && u <= 0xFF9F) {
                if (!this.halfWidthKatakanaSeen) {
                    this.halfWidthKatakanaSeen = true;
                    score += SHIFT_JIS_INITIAL_HALF_WIDTH_KATAKANA_PENALTY;
                }
                this.pendingScore = NO_PENDING_SCORE;
                score += HALF_WIDTH_KATAKANA_SCORE;
                if ((u >= 0xFF76 && u <= 0xFF84) || u == 0xFF73) {
                    this.halfWidthKatakanaState = HalfWidthKatakana.DAKUTEN_ALLOWED;
                } else if (u >= 0xFF8A && u <= 0xFF8E) {
                    this.halfWidthKatakanaState =
                            HalfWidthKatakana.DAKUTEN_OR_HANDAKUTEN_ALLOWED;
                } else if (u == 0xFF9E) {
                    if (halfWidthKatakanaState == HalfWidthKatakana.DAKUTEN_FORBIDDEN) {
                        score += IMPLAUSIBILITY_PENALTY;
                    } else {
                        score += HALF_WIDTH_KATAKANA_VOICING_SCORE;
                    }
                } else if (u == 0xFF9F) {
                    if (halfWidthKatakanaState
                            != HalfWidthKatakana.DAKUTEN_OR_HANDAKUTEN_ALLOWED) {
                        score += IMPLAUSIBILITY_PENALTY;
                    } else {
                        score += HALF_WIDTH_KATAKANA_VOICING_SCORE;
                    }
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else if (u >= 0x3040 && u < 0x3100) {
                if (this.pendingScore != NO_PENDING_SCORE) {
                    long pending = this.pendingScore;
                    score += pending;
                    this.pendingScore = NO_PENDING_SCORE;
                }
                score += SHIFT_JIS_SCORE_PER_KANA;
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
                if (this.prevByte < 0x98 || (this.prevByte == 0x98 && b < 0x73)) {
                    score += this.maybeSetAsPending(
                            SHIFT_JIS_SCORE_PER_LEVEL_1_KANJI
                                    + cjkExtraScore(u, Models.FREQUENT_KANJI));
                } else {
                    score += this.maybeSetAsPending(SHIFT_JIS_SCORE_PER_LEVEL_2_KANJI);
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
                score += SHIFT_JIS_PUA_PENALTY;
                this.prev = LatinCj.OTHER;
            } else {
                if (u == 0x3000 || u == 0x3001 || u == 0x3002 || u == 0xFF08 || u == 0xFF09) {
                    if (this.pendingScore != NO_PENDING_SCORE) {
                        long pending = this.pendingScore;
                        score += pending;
                        this.pendingScore = NO_PENDING_SCORE;
                    }
                    score += CJ_PUNCTUATION;
                } else if ((u >= 0 && u <= 0x7F)) {
                    this.pendingScore = NO_PENDING_SCORE;
                } else if (u == 0x80) {
                    this.pendingScore = NO_PENDING_SCORE;
                    score += IMPLAUSIBILITY_PENALTY;
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
