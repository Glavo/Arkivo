// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT16_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT32_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID;

/// Stores entry sizes and location resolved from a ZIP header and its ZIP64 extra field.
///
/// @param uncompressedSize the uncompressed size in bytes
/// @param compressedSize the compressed size in bytes
/// @param localHeaderOffset the local header offset within its disk
/// @param localHeaderDiskNumber the zero-based disk containing the local header
@NotNullByDefault
record Zip64Values(long uncompressedSize, long compressedSize, long localHeaderOffset, long localHeaderDiskNumber) {
    /// Resolves header fields whose values are ZIP64 sentinels, retaining all other values.
    ///
    /// Values are read in uncompressed size, compressed size, offset, and disk order, omitting
    /// fields whose header values are not sentinels. Excess payload bytes are ignored.
    /// Pass zero for location fields when resolving a local header.
    ///
    /// @throws IOException if a required field is missing, truncated, or exceeds a non-negative `long`
    static Zip64Values read(
            byte[] extraData,
            long uncompressedSize,
            long compressedSize,
            long localHeaderOffset,
            long localHeaderDiskNumber
    ) throws IOException {
        boolean needsUncompressedSize = uncompressedSize == UINT32_MAX;
        boolean needsCompressedSize = compressedSize == UINT32_MAX;
        boolean needsLocalHeaderOffset = localHeaderOffset == UINT32_MAX;
        boolean needsLocalHeaderDiskNumber = localHeaderDiskNumber == UINT16_MAX;
        if (!needsUncompressedSize
                && !needsCompressedSize
                && !needsLocalHeaderOffset
                && !needsLocalHeaderDiskNumber) {
            return new Zip64Values(
                    uncompressedSize,
                    compressedSize,
                    localHeaderOffset,
                    localHeaderDiskNumber
            );
        }

        @Nullable ZipExtraFields.Field field = ZipExtraFields.find(extraData, ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID);
        if (field == null) {
            throw new IOException("Required ZIP64 extended information extra field is missing");
        }

        ByteBuffer data = ByteBuffer.wrap(extraData, field.dataOffset(), field.dataSize())
                .order(ByteOrder.LITTLE_ENDIAN);
        if (needsUncompressedSize) {
            uncompressedSize = readZip64Long(data);
        }
        if (needsCompressedSize) {
            compressedSize = readZip64Long(data);
        }
        if (needsLocalHeaderOffset) {
            localHeaderOffset = readZip64Long(data);
        }
        if (needsLocalHeaderDiskNumber) {
            localHeaderDiskNumber = readZip64UnsignedInt(data);
        }
        return new Zip64Values(
                uncompressedSize,
                compressedSize,
                localHeaderOffset,
                localHeaderDiskNumber
        );
    }

    /// Reads one little-endian ZIP64 long value.
    private static long readZip64Long(ByteBuffer data) throws IOException {
        if (data.remaining() < Long.BYTES) {
            throw new IOException("Invalid ZIP64 extended information extra field");
        }
        long value = data.getLong();
        if (value < 0) {
            throw new IOException("ZIP64 extended information value is too large");
        }
        return value;
    }

    /// Reads one little-endian unsigned ZIP64 disk number.
    private static long readZip64UnsignedInt(ByteBuffer data) throws IOException {
        if (data.remaining() < Integer.BYTES) {
            throw new IOException("Invalid ZIP64 extended information extra field");
        }
        return Integer.toUnsignedLong(data.getInt());
    }
}
