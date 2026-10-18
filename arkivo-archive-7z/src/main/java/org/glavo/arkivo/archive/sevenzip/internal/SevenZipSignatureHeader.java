// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Stores the fixed 7z signature header fields.
///
/// @param majorVersion the unsigned-byte major version
/// @param minorVersion the unsigned-byte minor version
/// @param nextHeaderOffset the non-negative offset relative to the end of this fixed header
/// @param nextHeaderSize the non-negative next-header byte size
/// @param nextHeaderCrc32 the unsigned 32-bit next-header CRC-32 value
@NotNullByDefault
public record SevenZipSignatureHeader(
        int majorVersion,
        int minorVersion,
        long nextHeaderOffset,
        long nextHeaderSize,
        long nextHeaderCrc32
) {
    /// The size in bytes of the fixed 7z signature header.
    public static final int SIZE = 32;

    /// Creates a signature header with validated unsigned field values.
    ///
    /// @throws IllegalArgumentException if a version or CRC is out of range, or an offset or size is negative
    public SevenZipSignatureHeader {
        if (majorVersion < 0 || majorVersion > 0xff) {
            throw new IllegalArgumentException("majorVersion must fit in an unsigned byte");
        }
        if (minorVersion < 0 || minorVersion > 0xff) {
            throw new IllegalArgumentException("minorVersion must fit in an unsigned byte");
        }
        if (nextHeaderOffset < 0) {
            throw new IllegalArgumentException("nextHeaderOffset must be non-negative");
        }
        if (nextHeaderSize < 0) {
            throw new IllegalArgumentException("nextHeaderSize must be non-negative");
        }
        if (nextHeaderCrc32 < 0 || nextHeaderCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("nextHeaderCrc32 must fit in an unsigned int");
        }
    }
}
