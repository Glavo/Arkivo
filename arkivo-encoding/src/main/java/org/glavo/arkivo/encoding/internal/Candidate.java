// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;

/// Accumulates one encoding hypothesis without retaining input.
@NotNullByDefault
abstract class Candidate {
    /// Sentinel for an excluded candidate; ordinary scores can be negative.
    static final long INVALID = Long.MIN_VALUE;
    /// Available JDK charset, or null if the provider is absent.
    final @Nullable Charset charset;
    /// Accumulated score, or INVALID after rejection.
    long score;

    /// Creates a candidate, excluding unavailable optional charsets.
    Candidate(String name) {
        charset = Charset.isSupported(name) ? Charset.forName(name) : null;
        score = charset == null ? INVALID : 0;
    }

    /// Adds the contribution of one unsigned byte unless already excluded.
    final void accept(int value) {
        if (score != INVALID) {
            add(step(value));
        }
    }

    /// Adds a score delta or permanently excludes the candidate.
    final void add(long delta) {
        if (delta == INVALID) {
            score = INVALID;
        } else if (score != INVALID) {
            // Keep the rejection sentinel distinct even for extremely long streams.
            if (delta > 0 && score > Long.MAX_VALUE - delta) {
                score = Long.MAX_VALUE;
            } else if (delta < 0 && score < Long.MIN_VALUE + 1 - delta) {
                score = Long.MIN_VALUE + 1;
            } else {
                score += delta;
            }
        }
    }

    /// Scores an unsigned byte, returning INVALID on rejection.
    abstract long step(int value);

    /// Completes any end-of-input validation.
    abstract void finish();

    /// Returns the score after candidate-specific eligibility checks.
    long eligibleScore() {
        return score;
    }
}
