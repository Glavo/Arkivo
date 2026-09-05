// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies streaming writer support for optional ZIP compression methods.
@NotNullByDefault
public final class ZipStreamingWriterCompressionTest {
    /// The data-descriptor general-purpose flag.
    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;

    /// The ZIP LZMA general-purpose flag indicating an end-of-stream marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The ZIP version needed to extract LZMA entries.
    private static final int LZMA_VERSION_NEEDED = 63;

    /// Writes one entry and verifies it through both streaming and indexed readers.
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ZipMethod.class,
            names = {"BZIP2", "DEPRECATED_ZSTANDARD", "LZMA", "XZ", "ZSTANDARD"}
    )
    public void writesOptionalCompressionMethod(ZipMethod method) throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-compressed-");
        byte @Unmodifiable [] content = ("streaming writer content for " + method + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                ZipArkivoStreamingWriter.Entry entry = writer.beginFile("content.bin");
                ZipArkivoEntryAttributeView view = entry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(method);
                try (var output = entry.openOutputStream()) {
                    output.write(content);
                }
            }

            byte @Unmodifiable [] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("content.bin", attributes.path());
                assertEquals(method, attributes.compressionMethod());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertFalse(reader.next());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/content.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(method, attributes.compressionMethod());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertTrue((attributes.generalPurposeFlags() & DATA_DESCRIPTOR_FLAG) != 0);
                if (method == ZipMethod.LZMA) {
                    assertTrue((attributes.generalPurposeFlags() & LZMA_EOS_MARKER_FLAG) != 0);
                    assertEquals(LZMA_VERSION_NEEDED, attributes.versionNeededToExtract());
                }
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Computes the unsigned standard ZIP CRC-32 value.
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }
}
