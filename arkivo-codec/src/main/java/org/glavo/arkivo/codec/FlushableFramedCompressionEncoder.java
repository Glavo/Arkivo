// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Encodes independently terminated frames and supports nonterminal flushing within each active frame.
@NotNullByDefault
public interface FlushableFramedCompressionEncoder
        extends FramedCompressionEncoder, FlushableCompressionEncoder {
}
