// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the actionable reason that a buffer-driven codec operation returned.
@NotNullByDefault
public enum CodecOutcome {
    /// The supplied source was exhausted before the current encoding completed; preserve codec state and supply input.
    NEEDS_INPUT,

    /// The supplied target was filled while the codec still has work to perform; preserve codec state and supply space.
    NEEDS_OUTPUT,

    /// Decoding cannot continue until the requested preset dictionary is supplied to a dictionary-aware decoder.
    NEEDS_DICTIONARY,

    /// Pending encoded output reached a decodable boundary without ending the encoding; more source may follow.
    FLUSHED,

    /// A non-terminal frame boundary completed and the codec can process a following frame.
    BOUNDARY_REACHED,

    /// The current encoding or decoding stream completed; reset is required before processing another stream.
    FINISHED
}
