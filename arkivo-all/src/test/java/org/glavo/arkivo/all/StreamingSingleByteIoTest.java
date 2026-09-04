// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies writable archive formats through genuine single-byte entry I/O.
@NotNullByDefault
final class StreamingSingleByteIoTest {
    /// Temporary directory used to reopen non-stream-readable archives.
    @TempDir
    private Path temporaryDirectory;

    /// Round trips a boundary-crossing entry without invoking either bulk stream operation.
    ///
    /// @param formatName the installed archive format to exercise
    @ParameterizedTest
    @ValueSource(strings = {"ar", "cpio", "tar", "zip"})
    void roundTripsEntryOneByteAtATime(String formatName) throws IOException {
        byte[] expected = payload();
        byte[] archive = writeArchive(formatName, expected);

        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                formatName,
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            ArchiveEntryAttributes attributes = reader.readAttributes();
            assertEquals("payload.bin", attributes.path());

            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(expected, readOneByteAtATime(input));
            }
            assertFalse(reader.next());
        }
    }

    /// Round trips a 7z entry through its streaming writer and seekable file-system reader.
    @Test
    void roundTripsSevenZipEntryOneByteAtATime() throws IOException {
        byte[] expected = payload();
        Path archive = temporaryDirectory.resolve("single-byte.7z");
        Files.write(archive, writeArchive("7z", expected));

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem("7z", archive);
             InputStream input = Files.newInputStream(fileSystem.getPath("/payload.bin"))) {
            assertArrayEquals(expected, readOneByteAtATime(input));
        }
    }

    /// Writes one archive entry using only the single-byte output operation.
    private static byte[] writeArchive(String formatName, byte[] content) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, archive)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("payload.bin");
            try (OutputStream output = entry.openOutputStream()) {
                for (byte value : content) {
                    output.write(Byte.toUnsignedInt(value));
                }
            }
        }
        return archive.toByteArray();
    }

    /// Reads one entry body using only the single-byte input operation.
    private static byte[] readOneByteAtATime(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            output.write(value);
        }
        assertEquals(-1, input.read());
        return output.toByteArray();
    }

    /// Creates deterministic content that crosses 512-byte archive-record boundaries.
    private static byte[] payload() {
        byte[] result = new byte[513];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (index * 31 + 7);
        }
        return result;
    }
}
