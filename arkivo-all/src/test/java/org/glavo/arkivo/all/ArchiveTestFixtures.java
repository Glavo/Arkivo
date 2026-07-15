// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/// Creates binary-free archive fixtures shared by aggregate tests.
@NotNullByDefault
final class ArchiveTestFixtures {
    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE = {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00};

    /// Prevents utility-class construction.
    private ArchiveTestFixtures() {
    }

    /// Creates one stored RAR4 archive from entries in map iteration order.
    static byte[] createRar4Archive(Map<String, byte[]> entries) {
        Objects.requireNonNull(entries, "entries");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0L, new byte[6]);

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "entry path");
            byte[] content = Objects.requireNonNull(entry.getValue(), "entry content");
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            if (name.length > 0xffff) {
                throw new IllegalArgumentException("RAR4 entry name exceeds 65535 encoded bytes");
            }

            ByteArrayOutputStream fileFields = new ByteArrayOutputStream();
            writeUInt32(fileFields, content.length);
            writeUInt32(fileFields, content.length);
            fileFields.write(3);
            writeUInt32(fileFields, crc32(content));
            writeUInt32(fileFields, 0x0021_0000L);
            fileFields.write(29);
            fileFields.write(0x30);
            writeUInt16(fileFields, name.length);
            writeUInt32(fileFields, 0100644L);
            fileFields.writeBytes(name);
            writeRar4Block(output, 0x74, 0x8000L, fileFields.toByteArray());
            output.writeBytes(content);
        }

        writeRar4Block(output, 0x7b, 0L, new byte[0]);
        return output.toByteArray();
    }

    /// Creates one single-entry stored RAR4 archive.
    static byte[] createRar4Archive(String path, byte[] content) {
        return createRar4Archive(Map.of(path, content));
    }

    /// Writes one complete RAR4 header block.
    private static void writeRar4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields
    ) {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7L + fields.length);
        headerData.writeBytes(fields);

        byte[] protectedHeader = headerData.toByteArray();
        writeUInt16(output, crc32(protectedHeader));
        output.writeBytes(protectedHeader);
    }

    /// Writes one little-endian unsigned 16-bit value.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
    }

    /// Writes one little-endian unsigned 32-bit value.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
    }

    /// Returns the unsigned CRC-32 of the supplied bytes.
    private static long crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }
}