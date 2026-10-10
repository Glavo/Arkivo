// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import static org.glavo.arkivo.encoding.internal.Weights.*;

/// Scores EUC-JP text using strict JDK decoding and the upstream character rules.
@NotNullByDefault
final class EucJpCandidate extends DecodedCandidate {
    /// Whether a non-ASCII character has already been decoded.
    private boolean nonAsciiSeen;
    /// Permitted voicing marks after the preceding half-width character.
    private HalfWidthKatakana halfWidthKatakanaState = HalfWidthKatakana.DAKUTEN_FORBIDDEN;
    /// Previous character class used for adjacency scoring.
    private LatinCj prev = LatinCj.OTHER;
    /// Previous raw byte used to distinguish character repertoire levels.
    private int prevByte;
    /// Raw byte preceding prevByte, used to identify JIS supplementary-plane sequences.
    private int prevPrevByte;

    /// Creates an independent multibyte candidate.
    EucJpCandidate() {
        super("EUC-JP");
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
            if (!this.nonAsciiSeen && u >= 0x80) {
                this.nonAsciiSeen = true;
                if (u >= 0x3040 && u < 0x3100) {
                    score += EUC_JP_INITIAL_KANA_PENALTY;
                }
            }
            if ((u >= 'a' && u <= 'z')
                    || (u >= 'A' && u <= 'Z')) {
                if (this.prev == LatinCj.CJ) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.ASCII_LETTER;
            } else if (u >= 0xFF61 && u <= 0xFF9F) {
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
                this.prev = LatinCj.OTHER;
            } else if ((u >= 0x3041 && u <= 0x3093) || (u >= 0x30A1 && u <= 0x30F6)) {
                if (u == 0x3090 || u == 0x3091 || u == 0x30F0 || u == 0x30F1) {
                    score += EUC_JP_SCORE_PER_NEAR_OBSOLETE_KANA;
                } else {
                    score += EUC_JP_SCORE_PER_KANA;
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else if ((u >= 0x3400 && u < 0xA000) || (u >= 0xF900 && u < 0xFB00)) {
                if (this.prevPrevByte == 0x8F) {
                    score += EUC_JP_SCORE_PER_OTHER_KANJI;
                } else if (this.prevByte < 0xD0) {
                    score += EUC_JP_SCORE_PER_LEVEL_1_KANJI;
                    score += cjkExtraScore(u, Models.FREQUENT_KANJI);
                } else {
                    score += EUC_JP_SCORE_PER_LEVEL_2_KANJI;
                }
                if (this.prev == LatinCj.ASCII_LETTER) {
                    score += CJK_LATIN_ADJACENCY_PENALTY;
                }
                this.prev = LatinCj.CJ;
            } else {
                if (u == 0x3000 || u == 0x3001 || u == 0x3002 || u == 0xFF08 || u == 0xFF09) {
                    score += CJ_PUNCTUATION;
                } else if ((u >= 0 && u <= 0x7F)) {
                } else {
                    score += CJK_OTHER;
                }
                this.prev = LatinCj.OTHER;
            }
        }
        this.prevPrevByte = this.prevByte;
        this.prevByte = b;
        return score;
    }
}
