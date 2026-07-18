// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Stores the fixed 7z signature header fields.
@NotNullByDefault
public final class SevenZipSignatureHeader {
    /// The size in bytes of the fixed 7z signature header.
    public static final int SIZE = 32;

    /// The major 7z format version.
    private final int majorVersion;

    /// The minor 7z format version.
    private final int minorVersion;

    /// The offset of the next header relative to the first byte after the signature header.
    private final long nextHeaderOffset;

    /// The size in bytes of the next header.
    private final long nextHeaderSize;

    /// The expected CRC-32 value of the next header bytes.
    private final long nextHeaderCrc32;

    /// Creates a 7z signature header.
    ///
    /// @param majorVersion     the unsigned-byte major version
    /// @param minorVersion     the unsigned-byte minor version
    /// @param nextHeaderOffset the non-negative offset relative to the end of this fixed header
    /// @param nextHeaderSize   the non-negative next-header byte size
    /// @param nextHeaderCrc32  the unsigned 32-bit next-header CRC-32 value
    /// @throws IllegalArgumentException if a version or CRC is out of range, or an offset or size is negative
    public SevenZipSignatureHeader(
            int majorVersion,
            int minorVersion,
            long nextHeaderOffset,
            long nextHeaderSize,
            long nextHeaderCrc32
    ) {
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
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.nextHeaderOffset = nextHeaderOffset;
        this.nextHeaderSize = nextHeaderSize;
        this.nextHeaderCrc32 = nextHeaderCrc32;
    }

    /// Returns the major 7z format version.
    ///
    /// @return the unsigned-byte major version
    public int majorVersion() {
        return majorVersion;
    }

    /// Returns the minor 7z format version.
    ///
    /// @return the unsigned-byte minor version
    public int minorVersion() {
        return minorVersion;
    }

    /// Returns the offset of the next header relative to the first byte after the signature header.
    ///
    /// @return the non-negative relative next-header offset
    public long nextHeaderOffset() {
        return nextHeaderOffset;
    }

    /// Returns the size in bytes of the next header.
    ///
    /// @return the non-negative next-header size
    public long nextHeaderSize() {
        return nextHeaderSize;
    }

    /// Returns the expected CRC-32 value of the next header bytes.
    ///
    /// @return the unsigned 32-bit next-header CRC-32 value
    public long nextHeaderCrc32() {
        return nextHeaderCrc32;
    }
}
