// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the integrity check stored after each XZ Block.
///
/// The choice is part of the XZ Stream flags and applies independently to every Block in the Stream. Decoder checksum
/// verification is configured separately by [XZCodec].
@NotNullByDefault
public enum XZCheckType {
    /// Stores no block integrity check.
    NONE(0),

    /// Stores a CRC-32 block check.
    CRC32(1),

    /// Stores a CRC-64 block check.
    CRC64(4),

    /// Stores a SHA-256 block check.
    SHA256(10);

    /// The XZ stream flag value.
    private final int flag;

    /// Creates a check type with its XZ stream flag.
    XZCheckType(int flag) {
        this.flag = flag;
    }

    /// Returns the XZ stream flag value.
    int flag() {
        return flag;
    }
}
