// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one physical packed byte range used to read a 7z entry.
///
/// Offsets are absolute within the logical archive, including the signature header. A split archive uses the same
/// logical offsets as its concatenated volume view.
///
/// @param offset the absolute logical archive offset of the packed bytes
/// @param size the packed byte count in this range
/// @param crc32 the expected packed CRC-32, or `UNKNOWN_CRC32` when absent
@NotNullByDefault
public record SevenZipPackedStream(long offset, long size, long crc32) {
    /// The CRC-32 value used when a packed stream has no declared digest.
    public static final long UNKNOWN_CRC32 = -1L;

    /// Creates a validated physical packed stream descriptor.
    public SevenZipPackedStream {
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (size < 0L) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (crc32 < UNKNOWN_CRC32 || crc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
        }
    }
}
