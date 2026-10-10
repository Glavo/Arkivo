// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Scoring weights from chardetng 1.0.0.
@NotNullByDefault
final class Weights {
    /// Prevents instantiation.
    private Weights() {
    }

    /// Penalty for adjacent Latin and non-Latin letters.
    static final int LATIN_ADJACENCY_PENALTY = -50;

    /// Penalty represented by the reserved value 255 in a pair-score table.
    static final int IMPLAUSIBILITY_PENALTY = -220;

    /// Bonus for a plausible Western ordinal or abbreviation.
    static final int ORDINAL_BONUS = 300;

    /// Bonus for a copyright sign delimited by space-like characters.
    static final int COPYRIGHT_BONUS = 222;

    /// Penalty for implausible capitalization involving a non-ASCII Latin letter.
    static final int IMPLAUSIBLE_LATIN_CASE_TRANSITION_PENALTY = -180;

    /// Bonus for a non-Latin word with only its first letter capitalized.
    static final int NON_LATIN_CAPITALIZATION_BONUS = 40;

    /// Penalty for an all-capital KOI8-U word.
    static final int NON_LATIN_ALL_CAPS_PENALTY = -40;

    /// Per-letter penalty for mixed-case non-Latin words.
    static final int NON_LATIN_MIXED_CASE_PENALTY = -20;

    /// Base score for characters in commonly used CJK repertoire levels.
    static final int CJK_BASE_SCORE = 41;

    /// Base score for characters in secondary CJK repertoire levels.
    static final int CJK_SECONDARY_BASE_SCORE = 20;

    /// Score for a full-width Shift_JIS kana.
    static final int SHIFT_JIS_SCORE_PER_KANA = 20;

    /// Score for a first-level Shift_JIS ideograph.
    static final int SHIFT_JIS_SCORE_PER_LEVEL_1_KANJI = CJK_BASE_SCORE;

    /// Score for a second-level Shift_JIS ideograph.
    static final int SHIFT_JIS_SCORE_PER_LEVEL_2_KANJI = CJK_SECONDARY_BASE_SCORE;

    /// One-time penalty on the first Shift_JIS half-width katakana.
    static final int SHIFT_JIS_INITIAL_HALF_WIDTH_KATAKANA_PENALTY = -75;

    /// Score for a half-width katakana.
    static final int HALF_WIDTH_KATAKANA_SCORE = 1;

    /// Bonus for a half-width voicing mark after a compatible character.
    static final int HALF_WIDTH_KATAKANA_VOICING_SCORE = 10;

    /// Penalty for a Shift_JIS private-use character.
    static final int SHIFT_JIS_PUA_PENALTY = -(CJK_BASE_SCORE * 10);

    /// Score for a commonly used EUC-JP kana.
    static final int EUC_JP_SCORE_PER_KANA = CJK_BASE_SCORE + (CJK_BASE_SCORE / 3);

    /// Score for the historical wi and we kana.
    static final int EUC_JP_SCORE_PER_NEAR_OBSOLETE_KANA = CJK_BASE_SCORE - 1;

    /// Score for a first-level EUC-JP ideograph.
    static final int EUC_JP_SCORE_PER_LEVEL_1_KANJI = CJK_BASE_SCORE;

    /// Score for a second-level EUC-JP ideograph.
    static final int EUC_JP_SCORE_PER_LEVEL_2_KANJI = CJK_SECONDARY_BASE_SCORE;

    /// Score for an ideograph in the supplementary JIS plane.
    static final int EUC_JP_SCORE_PER_OTHER_KANJI = CJK_SECONDARY_BASE_SCORE / 4;

    /// Penalty when the first non-ASCII EUC-JP character is kana.
    static final int EUC_JP_INITIAL_KANA_PENALTY = -((CJK_BASE_SCORE / 3) + 1);

    /// Score for a first-level Big5 ideograph.
    static final int BIG5_SCORE_PER_LEVEL_1_HANZI = CJK_BASE_SCORE;

    /// Score for another Big5 ideograph.
    static final int BIG5_SCORE_PER_OTHER_HANZI = CJK_SECONDARY_BASE_SCORE;

    /// Score for a Hangul syllable encoded in the EUC range.
    static final int EUC_KR_SCORE_PER_EUC_HANGUL = CJK_BASE_SCORE + 1;

    /// Score for a Hangul syllable outside the EUC range.
    static final int EUC_KR_SCORE_PER_NON_EUC_HANGUL = CJK_SECONDARY_BASE_SCORE / 5;

    /// Score for a Korean ideograph.
    static final int EUC_KR_SCORE_PER_HANJA = CJK_SECONDARY_BASE_SCORE / 2;

    /// Penalty for a Korean ideograph immediately following Hangul.
    static final int EUC_KR_HANJA_AFTER_HANGUL_PENALTY = -(CJK_BASE_SCORE * 10);

    /// Per-character penalty after the fifth character of a Korean word.
    static final int EUC_KR_LONG_WORD_PENALTY = -6;

    /// Score for a first-level GBK ideograph.
    static final int GBK_SCORE_PER_LEVEL_1 = CJK_BASE_SCORE;

    /// Score for a second-level GBK ideograph.
    static final int GBK_SCORE_PER_LEVEL_2 = CJK_SECONDARY_BASE_SCORE;

    /// Score for a GBK ideograph outside the EUC range.
    static final int GBK_SCORE_PER_NON_EUC = CJK_SECONDARY_BASE_SCORE / 4;

    /// Penalty for a GB18030 private-use character.
    static final int GBK_PUA_PENALTY = -(CJK_BASE_SCORE * 10);

    /// Penalty for adjacent ASCII letters and CJK characters.
    static final int CJK_LATIN_ADJACENCY_PENALTY = -CJK_BASE_SCORE;

    /// Score for punctuation shared by Chinese and Japanese text.
    static final int CJ_PUNCTUATION = CJK_BASE_SCORE / 2;

    /// Score for other non-ASCII characters in CJK candidates.
    static final int CJK_OTHER = CJK_SECONDARY_BASE_SCORE / 4;

    /// Caseless Latin-letter class in non-Latin models.
    static final int LATIN_LETTER = 1;
}
