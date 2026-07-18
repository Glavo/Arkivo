// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Provides helpers for parsing ZIP extra field data.
@NotNullByDefault
final class ZipExtraFields {
    /// Prevents instantiation.
    private ZipExtraFields() {
    }

    /// Validates that raw ZIP extra data contains complete field records followed by at most three zero padding bytes.
    static void validate(byte[] extraData) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            if (isShortZeroPadding(extraData, offset)) {
                return;
            }
            offset = read(extraData, offset).nextOffset();
        }
    }

    /// Validates readable records while treating only a truncated unknown trailing field as opaque raw metadata.
    static void validateForReading(byte[] extraData) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            if (isShortZeroPadding(extraData, offset)) {
                return;
            }
            @Nullable Field field = readForReading(extraData, offset);
            if (field == null) {
                return;
            }
            offset = field.nextOffset();
        }
    }

    /// Finds the first complete extra field with the given identifier.
    ///
    /// A truncated unknown trailing field is opaque compatibility metadata. Truncated recognized fields and nonzero
    /// fragments shorter than a field header remain malformed.
    static @Nullable Field find(byte[] extraData, int expectedId) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            if (isShortZeroPadding(extraData, offset)) {
                return null;
            }
            @Nullable Field field = readForReading(extraData, offset);
            if (field == null) {
                return null;
            }
            if (field.id() == expectedId) {
                return field;
            }
            offset = field.nextOffset();
        }
        return null;
    }

    /// Reads one extra field record starting at the given offset.
    static Field read(byte[] extraData, int offset) throws IOException {
        if (extraData.length - offset < Integer.BYTES) {
            throw new IOException("Invalid ZIP extra field length");
        }

        int id = ZipLittleEndian.readUnsignedShort(extraData, offset);
        int dataSize = ZipLittleEndian.readUnsignedShort(extraData, offset + Short.BYTES);
        int dataOffset = offset + Integer.BYTES;
        if (dataSize > extraData.length - dataOffset) {
            throw new IOException("Invalid ZIP extra field length");
        }
        return new Field(id, dataOffset, dataSize, dataOffset + dataSize);
    }

    /// Reads one complete field for a read path, or returns null for a truncated unknown trailing field.
    static @Nullable Field readForReading(byte[] extraData, int offset) throws IOException {
        if (isShortZeroPadding(extraData, offset)) {
            return null;
        }
        if (extraData.length - offset < Integer.BYTES) {
            throw new IOException("Invalid ZIP extra field length");
        }

        int id = ZipLittleEndian.readUnsignedShort(extraData, offset);
        int dataSize = ZipLittleEndian.readUnsignedShort(extraData, offset + Short.BYTES);
        int dataOffset = offset + Integer.BYTES;
        if (dataSize > extraData.length - dataOffset) {
            if (isRecognizedFieldId(id)) {
                throw new IOException("Invalid ZIP extra field length");
            }
            return null;
        }
        return new Field(id, dataOffset, dataSize, dataOffset + dataSize);
    }

    /// Returns whether Arkivo interprets the payload of the given extra field identifier.
    private static boolean isRecognizedFieldId(int id) {
        return id == ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID
                || id == ZipConstants.WINZIP_AES_EXTRA_FIELD_ID
                || id == ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID
                || id == ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID
                || id == 0x5455
                || id == 0x7875;
    }

    /// Returns whether the remaining one to three bytes are Android zipalign zero padding.
    private static boolean isShortZeroPadding(byte[] extraData, int offset) {
        int remaining = extraData.length - offset;
        if (remaining >= Integer.BYTES) {
            return false;
        }
        for (int index = offset; index < extraData.length; index++) {
            if (extraData[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /// Describes one ZIP extra field record.
    ///
    /// @param id the extra field identifier
    /// @param dataOffset the offset of the field payload inside the extra data buffer
    /// @param dataSize the payload size in bytes
    /// @param nextOffset the offset immediately after this field record
    record Field(int id, int dataOffset, int dataSize, int nextOffset) {
    }
}
