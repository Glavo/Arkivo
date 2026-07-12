// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one physical packed stream consumed by a 7z folder.
///
/// @param offset the absolute archive offset of the packed bytes
/// @param size the packed byte count
/// @param crc32 the expected packed CRC-32, or `UNKNOWN_CRC32` when absent
@NotNullByDefault
record SevenZipPackedStream(long offset, long size, long crc32) {
    /// Creates a validated physical packed stream descriptor.
    SevenZipPackedStream {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (crc32 < SevenZipEntryMetadata.UNKNOWN_CRC32 || crc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
        }
    }
}
