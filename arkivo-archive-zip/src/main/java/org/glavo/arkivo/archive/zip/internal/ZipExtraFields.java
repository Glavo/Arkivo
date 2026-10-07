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
    /// Data following the first matching record is not examined.
    static @Nullable Field find(byte[] extraData, int expectedId) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
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

    /// Removes all fields with the given identifier, preserving other records and short zero padding.
    ///
    /// Returns the input array unchanged if no matching field is present; otherwise returns a new array.
    /// The input contents are not modified. All records are validated before a result is returned.
    ///
    /// @throws IOException if the data contains a truncated record or a nonzero short tail
    static byte[] remove(byte[] extraData, int excludedId) throws IOException {
        int retainedSize = extraData.length;
        int recordsEnd = 0;
        while (recordsEnd < extraData.length && !isShortZeroPadding(extraData, recordsEnd)) {
            Field field = read(extraData, recordsEnd);
            if (field.id() == excludedId) {
                retainedSize -= field.nextOffset() - recordsEnd;
            }
            recordsEnd = field.nextOffset();
        }
        if (retainedSize == extraData.length) {
            return extraData;
        }

        byte[] retained = new byte[retainedSize];
        int targetOffset = 0;
        int offset = 0;
        while (offset < recordsEnd) {
            Field field = read(extraData, offset);
            int fieldSize = field.nextOffset() - offset;
            if (field.id() != excludedId) {
                System.arraycopy(extraData, offset, retained, targetOffset, fieldSize);
                targetOffset += fieldSize;
            }
            offset = field.nextOffset();
        }
        System.arraycopy(extraData, recordsEnd, retained, targetOffset, extraData.length - recordsEnd);
        return retained;
    }

    /// Reads one extra field record starting at the given offset; padding is not a record.
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

    /// Reads a complete field, or returns `null` for short zero padding or a truncated unknown trailing field.
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
                || id == ZipConstants.EXTENDED_TIMESTAMP_EXTRA_FIELD_ID
                || id == ZipConstants.NEW_UNIX_EXTRA_FIELD_ID;
    }

    /// Returns whether no bytes remain or the remaining one to three bytes are Android zipalign zero padding.
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
    @NotNullByDefault
    record Field(int id, int dataOffset, int dataSize, int nextOffset) {
    }
}
