// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the actionable reason that a buffer-driven codec operation returned.
@NotNullByDefault
public enum CodecOutcome {
    /// The supplied source was exhausted before the current encoding completed.
    NEEDS_INPUT,

    /// The supplied target was filled while the codec still has work to perform.
    NEEDS_OUTPUT,

    /// Decoding cannot continue until the requested preset dictionary is supplied.
    NEEDS_DICTIONARY,

    /// Pending encoded output reached a decodable boundary without ending the encoding.
    FLUSHED,

    /// A non-terminal format boundary completed and the codec can process a following unit.
    BOUNDARY_REACHED,

    /// The current encoding or decoding operation completed.
    FINISHED
}
