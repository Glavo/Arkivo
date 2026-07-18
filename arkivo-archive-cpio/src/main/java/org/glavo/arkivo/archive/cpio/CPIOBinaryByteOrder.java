// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the byte order of each 16-bit word in an old binary CPIO header.
///
/// Historical binary CPIO stores 32-bit values as two high-word-first 16-bit words. `LITTLE_ENDIAN` swaps the bytes
/// inside each word and therefore is intentionally more specific than `java.nio.ByteOrder`.
@NotNullByDefault
public enum CPIOBinaryByteOrder {
    /// Stores each 16-bit word most-significant byte first.
    BIG_ENDIAN,

    /// Stores each 16-bit word least-significant byte first.
    LITTLE_ENDIAN
}
