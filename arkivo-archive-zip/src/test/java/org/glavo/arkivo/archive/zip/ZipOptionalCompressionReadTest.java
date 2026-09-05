// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.compress;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies known-size ZIP entries using independently encoded optional-compression payloads.
@NotNullByDefault
public final class ZipOptionalCompressionReadTest {
    /// The ZIP LZMA general-purpose flag indicating an end-of-stream marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The ZIP version needed to extract Deflate64 entries.
    private static final int DEFLATE64_VERSION_NEEDED = 21;

    /// The ZIP version needed to extract LZMA entries.
    private static final int LZMA_VERSION_NEEDED = 63;

    /// Decodes a local-record-only stream and the equivalent indexed archive.
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ZipMethod.class,
            names = {"BZIP2", "DEFLATE64", "DEPRECATED_ZSTANDARD", "LZMA", "XZ", "ZSTANDARD"}
    )
    public void readsKnownSizeEntry(ZipMethod method) throws IOException {
        byte @Unmodifiable [] name = "content.bin".getBytes(StandardCharsets.US_ASCII);
        byte @Unmodifiable [] content = ("known-size ZIP content for " + method + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] compressed = compress(method, content);
        long checksum = crc32(content);

        byte @Unmodifiable [] localRecord = localRecord(name, method, compressed, checksum, content.length);
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(localRecord)
        )) {
            assertTrue(reader.next());
            assertEntryMetadata(
                    reader.readAttributes(ZipArkivoEntryAttributes.class),
                    method,
                    compressed.length,
                    content.length
            );
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertFalse(reader.next());
        }

        Path archivePath = createTemporaryArchivePath("optional-compression-read-");
        try {
            Files.write(archivePath, seekableArchive(name, method, compressed, checksum, content.length));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/content.bin");
                assertEntryMetadata(
                        Files.readAttributes(file, ZipArkivoEntryAttributes.class),
                        method,
                        compressed.length,
                        content.length
                );
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer output = ByteBuffer.allocate(content.length);
                    while (output.hasRemaining() && channel.read(output) >= 0) {
                        // Continue until the decoded entry is exhausted or the destination is full.
                    }
                    assertArrayEquals(content, output.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies metadata shared by streaming and indexed readers.
    private static void assertEntryMetadata(
            ZipArkivoEntryAttributes attributes,
            ZipMethod method,
            int compressedSize,
            int contentSize
    ) {
        assertEquals("content.bin", attributes.path());
        assertEquals(method, attributes.compressionMethod());
        assertEquals(compressedSize, attributes.compressedSize());
        assertEquals(contentSize, attributes.size());
        if (method == ZipMethod.LZMA) {
            assertEquals(LZMA_VERSION_NEEDED, attributes.versionNeededToExtract());
        }
    }

    /// Returns one complete local file record with known sizes.
    private static byte[] localRecord(
            byte @Unmodifiable [] name,
            ZipMethod method,
            byte @Unmodifiable [] compressed,
            long checksum,
            int contentSize
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + compressed.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, method, checksum, compressed.length, contentSize);
        buffer.put(compressed);
        return buffer.array();
    }

    /// Returns a complete single-entry archive containing the given compressed payload.
    private static byte[] seekableArchive(
            byte @Unmodifiable [] name,
            ZipMethod method,
            byte @Unmodifiable [] compressed,
            long checksum,
            int contentSize
    ) {
        int localRecordSize = 30 + name.length + compressed.length;
        int centralDirectorySize = 46 + name.length;
        ByteBuffer buffer = ByteBuffer.allocate(localRecordSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, method, checksum, compressed.length, contentSize);
        buffer.put(compressed);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) versionNeeded(method));
        buffer.putShort((short) flags(method));
        buffer.putShort((short) method.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) checksum);
        buffer.putInt(compressed.length);
        buffer.putInt(contentSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put(name);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(localRecordSize);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Writes the fixed local-header fields for one known-size entry.
    private static void writeLocalHeader(
            ByteBuffer buffer,
            byte @Unmodifiable [] name,
            ZipMethod method,
            long checksum,
            int compressedSize,
            int contentSize
    ) {
        buffer.putInt(0x04034b50);
        buffer.putShort((short) versionNeeded(method));
        buffer.putShort((short) flags(method));
        buffer.putShort((short) method.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) checksum);
        buffer.putInt(compressedSize);
        buffer.putInt(contentSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
    }

    /// Returns the general-purpose flags needed by the compressed payload.
    private static int flags(ZipMethod method) {
        return method == ZipMethod.LZMA ? LZMA_EOS_MARKER_FLAG : 0;
    }

    /// Returns the minimum extraction version used by the test archive.
    private static int versionNeeded(ZipMethod method) {
        return switch (method) {
            case DEFLATE64 -> DEFLATE64_VERSION_NEEDED;
            case LZMA -> LZMA_VERSION_NEEDED;
            default -> 20;
        };
    }

    /// Computes the unsigned standard ZIP CRC-32 value.
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }
}
