// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;

/// Validates multibyte candidates and completes pending character sequences.
@NotNullByDefault
class DecodedCandidate extends Candidate {
    /// Indicates that no non-negative character score is being deferred.
    static final long NO_PENDING_SCORE = -1;
    /// Private incremental decoding workspace.
    final ByteDecoder decoder;

    /// Creates a candidate for a JDK charset.
    DecodedCandidate(String name) {
        super(name);
        decoder = new ByteDecoder(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    @Override
    long step(int value) {
        return decoder.accept(value) < 0 ? INVALID : 0;
    }

    @Override
    void finish() {
        if (score != INVALID && !decoder.finish()) {
            score = INVALID;
        }
    }

    /// Returns the upstream rank bonus for a common CJK character.
    static long cjkExtraScore(int value, char[] table) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value) {
                return (128 - i) / 16;
            }
        }
        return 0;
    }

    /// Tests lead bytes frequently confused with single-byte punctuation.
    static boolean problematicLead(int value) {
        return value >= 0x91 && value <= 0x97 || value == 0x9A || value == 0x8A
                || value == 0x9B || value == 0x8B || value == 0x9E || value == 0x8E || value == 0xB0;
    }

    /// Tests the additional ambiguous lead bytes used by GBK and Korean.
    static boolean moreProblematicLead(int value) {
        return problematicLead(value) || value == 0x82 || value == 0x84 || value == 0x85 || value == 0xA0;
    }
}
