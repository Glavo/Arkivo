// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Provides helpers for parsing ZIP extra field data.
@NotNullByDefault
final class ZipExtraFields {
    /// Prevents instantiation.
    private ZipExtraFields() {
    }

    /// Validates that raw ZIP extra data contains only complete extra field records.
    static void validate(byte[] extraData) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            offset = read(extraData, offset).nextOffset();
        }
    }

    /// Finds the first extra field record with the given identifier.
    static @Nullable Field find(byte[] extraData, int expectedId) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            Field field = read(extraData, offset);
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

    /// Describes one ZIP extra field record.
    ///
    /// @param id the extra field identifier
    /// @param dataOffset the offset of the field payload inside the extra data buffer
    /// @param dataSize the payload size in bytes
    /// @param nextOffset the offset immediately after this field record
    record Field(int id, int dataOffset, int dataSize, int nextOffset) {
    }
}
