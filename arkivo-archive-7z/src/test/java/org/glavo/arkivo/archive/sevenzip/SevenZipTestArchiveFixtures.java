// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/// Creates small in-memory 7z archives shared by focused contract tests.
@NotNullByDefault
final class SevenZipTestArchiveFixtures {
    /// Creates no instances.
    private SevenZipTestArchiveFixtures() {
    }

    /// Returns a new minimal 7z archive with an empty next header.
    static byte[] minimalArchive() {
        return archiveWithNextHeader(new byte[0]);
    }

    /// Returns a new 7z archive containing empty regular files with the given raw paths.
    static byte[] emptyFileArchive(String... paths) {
        if (paths.length == 0) {
            throw new IllegalArgumentException("At least one path is required");
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x01);
        header.write(0x05);
        writeNumber(header, paths.length);

        byte[] emptyFileBits = setBits(paths.length);
        header.write(0x0e);
        writeNumber(header, emptyFileBits.length);
        header.writeBytes(emptyFileBits);
        header.write(0x0f);
        writeNumber(header, emptyFileBits.length);
        header.writeBytes(emptyFileBits);

        byte[] names = namesProperty(paths);
        header.write(0x11);
        writeNumber(header, names.length);
        header.writeBytes(names);
        header.write(0x00);
        header.write(0x00);
        return archiveWithNextHeader(header.toByteArray());
    }

    /// Returns a new 7z archive containing the given next header and its computed CRC-32.
    static byte[] archiveWithNextHeader(byte[] nextHeader) {
        return archiveWithNextHeader(nextHeader, crc32(nextHeader));
    }

    /// Returns a new 7z archive containing the given next header and declared CRC-32.
    static byte[] archiveWithNextHeader(byte[] nextHeader, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + nextHeader.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(0L);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) nextHeaderCrc32);
        buffer.put(nextHeader);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns a fixed 7z signature header containing caller-controlled unsigned next-header fields.
    static byte[] rawSignatureHeader(long nextHeaderOffset, long nextHeaderSize, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(nextHeaderOffset);
        buffer.putLong(nextHeaderSize);
        buffer.putInt((int) nextHeaderCrc32);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns a new 7z archive containing one Copy-compressed file named `hello.txt`.
    static byte[] copyFileArchive(byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(output)) {
            var entry = writer.beginFile("hello.txt");
            try (var body = entry.openOutputStream()) {
                body.write(content);
            }
        }
        return output.toByteArray();
    }

    /// Splits archive bytes at the given strictly increasing logical offsets.
    static byte[][] splitArchive(byte[] archive, int... offsets) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(offsets, "offsets");
        byte[][] result = new byte[offsets.length + 1][];
        int previous = 0;
        for (int index = 0; index < offsets.length; index++) {
            int offset = offsets[index];
            if (offset <= previous || offset >= archive.length) {
                throw new IllegalArgumentException("split offsets must be strictly inside the archive");
            }
            result[index] = Arrays.copyOfRange(archive, previous, offset);
            previous = offset;
        }
        result[offsets.length] = Arrays.copyOfRange(archive, previous, archive.length);
        return result;
    }

    /// Returns a repeatable volume source over defensive copies of the given byte arrays.
    static ArkivoVolumeSource volumeSource(byte[][] volumes) {
        Objects.requireNonNull(volumes, "volumes");
        byte[][] snapshots = new byte[volumes.length][];
        for (int index = 0; index < volumes.length; index++) {
            snapshots[index] = Objects.requireNonNull(volumes[index], "volume").clone();
        }
        return index -> {
            if (index < 0L || index >= snapshots.length) {
                return null;
            }
            return new ReadOnlyByteArrayChannel(snapshots[(int) index]);
        };
    }

    /// Returns a 7z bit vector whose first `bitCount` bits are set.
    private static byte[] setBits(int bitCount) {
        byte[] bits = new byte[(bitCount + 7) / 8];
        for (int index = 0; index < bitCount; index++) {
            bits[index >>> 3] |= (byte) (0x80 >>> (index & 7));
        }
        return bits;
    }

    /// Returns an inline 7z names property for the given paths.
    private static byte[] namesProperty(String... paths) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        for (String path : paths) {
            output.writeBytes(path.getBytes(StandardCharsets.UTF_16LE));
            output.write(0);
            output.write(0);
        }
        return output.toByteArray();
    }

    /// Writes a non-negative 7z variable-length integer.
    private static void writeNumber(ByteArrayOutputStream output, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Test value is out of range");
        }
        if (value < 0x80) {
            output.write(value);
        } else if (value < 0x4000) {
            output.write(0x80 | (value >>> 8));
            output.write(value);
        } else if (value < 0x20_0000) {
            output.write(0xc0 | (value >>> 16));
            output.write(value);
            output.write(value >>> 8);
        } else {
            throw new IllegalArgumentException("Test value is out of range");
        }
    }

    /// Returns the unsigned CRC-32 of the given bytes.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }
}
