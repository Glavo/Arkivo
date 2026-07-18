// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one Zstandard skippable frame header.
///
/// The payload is opaque to the codec. Its size contributes to complete-frame size calculations but the header record
/// does not retain or expose payload bytes.
///
/// @param id skippable frame identifier from zero through fifteen
/// @param payloadSize unsigned 32-bit payload size
@NotNullByDefault
public record ZstdSkippableFrameInfo(int id, long payloadSize) implements ZstdFrameInfo {
    /// The fixed skippable frame-header size.
    public static final int HEADER_SIZE = 8;

    /// Validates skippable-frame fields.
    ///
    /// @throws IllegalArgumentException if {@code id} or {@code payloadSize} is outside its encoded domain
    public ZstdSkippableFrameInfo {
        if (id < 0 || id > 15) {
            throw new IllegalArgumentException("id must be between zero and fifteen");
        }
        if (payloadSize < 0L || payloadSize > 0xffff_ffffL) {
            throw new IllegalArgumentException("payloadSize must fit an unsigned 32-bit field");
        }
    }

    /// Returns the fixed eight-byte header size.
    @Override
    public int headerSize() {
        return HEADER_SIZE;
    }

    /// Returns true for a skippable frame.
    @Override
    public boolean skippable() {
        return true;
    }
}
