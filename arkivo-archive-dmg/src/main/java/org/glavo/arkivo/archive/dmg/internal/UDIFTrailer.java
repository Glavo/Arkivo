// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Stores the validated fields required from one flattened UDIF `koly` trailer.
///
/// @param dataForkOffset the physical data-fork offset
/// @param dataForkLength the physical data-fork length
/// @param xmlOffset the physical XML property-list offset
/// @param xmlLength the XML property-list byte length
/// @param sectorCount the decoded image sector count
@NotNullByDefault
record UDIFTrailer(
        long dataForkOffset,
        long dataForkLength,
        long xmlOffset,
        long xmlLength,
        long sectorCount
) {
    /// Parses and validates the fixed-size trailer.
    ///
    /// @param bytes the exact 512-byte trailer
    /// @param sourceSize the complete encoded source size
    /// @return the validated required fields
    /// @throws IOException if the trailer is malformed or describes an unsupported UDIF variant
    static UDIFTrailer parse(byte[] bytes, long sourceSize) throws IOException {
        if (bytes.length != UDIFConstants.TRAILER_SIZE) {
            throw new IOException("UDIF trailer must contain exactly 512 bytes");
        }
        if (ByteArrayAccess.readIntBigEndian(bytes, 0) != UDIFConstants.KOLY_SIGNATURE) {
            throw new IOException("Missing UDIF koly trailer signature");
        }
        long version = uint32(bytes, 4);
        long headerSize = uint32(bytes, 8);
        long flags = uint32(bytes, 12);
        if (version != 4L || headerSize != UDIFConstants.TRAILER_SIZE) {
            throw new IOException("Unsupported UDIF trailer version or size: " + version + "/" + headerSize);
        }
        if ((flags & 1L) == 0L) {
            throw new IOException("Only flattened UDIF images are supported");
        }

        long segmentNumber = uint32(bytes, 56);
        long segmentCount = uint32(bytes, 60);
        boolean unsegmented = segmentNumber == 0L && segmentCount == 0L
                || segmentNumber == 1L && segmentCount == 1L;
        if (!unsegmented) {
            throw new IOException(
                    "Multi-segment UDIF images are not supported: segment " + segmentNumber + " of " + segmentCount
            );
        }

        long dataForkOffset = nonNegativeInt64(bytes, 24, "UDIF data-fork offset");
        long dataForkLength = nonNegativeInt64(bytes, 32, "UDIF data-fork length");
        long xmlOffset = nonNegativeInt64(bytes, 216, "UDIF XML offset");
        long xmlLength = nonNegativeInt64(bytes, 224, "UDIF XML length");
        long sectorCount = nonNegativeInt64(bytes, 492, "UDIF sector count");
        ChannelIO.requireRange(dataForkOffset, dataForkLength, sourceSize, "UDIF data fork");
        ChannelIO.requireRange(xmlOffset, xmlLength, sourceSize, "UDIF XML property list");
        if (xmlOffset + xmlLength > sourceSize - UDIFConstants.TRAILER_SIZE) {
            throw new IOException("UDIF XML property list overlaps the trailer");
        }
        return new UDIFTrailer(dataForkOffset, dataForkLength, xmlOffset, xmlLength, sectorCount);
    }

    /// Reads an unsigned big-endian 32-bit field.
    private static long uint32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, offset));
    }

    /// Reads a signed Java representation of an on-disk unsigned 64-bit value and rejects values above `Long.MAX_VALUE`.
    private static long nonNegativeInt64(byte[] bytes, int offset, String description) throws IOException {
        long value = ByteArrayAccess.readLongBigEndian(bytes, offset);
        if (value < 0L) {
            throw new IOException(description + " exceeds the supported signed 64-bit range");
        }
        return value;
    }
}
