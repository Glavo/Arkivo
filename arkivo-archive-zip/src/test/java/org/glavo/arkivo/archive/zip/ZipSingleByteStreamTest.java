// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP entry streams preserve state when callers use only single-byte I/O operations.
@NotNullByDefault
final class ZipSingleByteStreamTest {
    /// Temporary storage used for indexed file-system round trips.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies indexed entry streams implement single-byte writes and reads through archive finalization.
    @Test
    void roundTripsFileSystemEntryOneByteAtATime() throws IOException {
        byte[] expected = payload();
        Path archive = temporaryDirectory.resolve("single-byte-file-system.zip");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archive);
             OutputStream output = Files.newOutputStream(fileSystem.getPath("/payload.bin"))) {
            writeOneByteAtATime(output, expected);
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive);
             InputStream input = Files.newInputStream(fileSystem.getPath("/payload.bin"))) {
            assertArrayEquals(expected, readOneByteAtATime(input));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies a deflated streaming entry drains its unknown-size data descriptor after single-byte reads.
    @Test
    void readsDeflatedDataDescriptorOneByteAtATime() throws IOException {
        byte[] expected = payload();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            var entry = writer.beginFile("payload.bin");
            try (OutputStream output = entry.openOutputStream()) {
                writeOneByteAtATime(output, expected);
            }
        }

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray())
        )) {
            assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(expected, readOneByteAtATime(input));
                assertEquals(-1, input.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies encrypted stored entries distinguish embedded descriptor signatures during single-byte reads.
    ///
    /// @param encryption the encryption mode applied to the generated entry
    @ParameterizedTest
    @EnumSource(value = ZipEncryption.class, names = {"ZIP_CRYPTO", "WINZIP_AES_256"})
    void readsEncryptedStoredDataDescriptorOneByteAtATime(ZipEncryption encryption) throws IOException {
        byte[] expected = payload();
        byte[] password = "single-byte password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ZipArchiveOptions.Create createOptions = ZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(password))
                .withDefaultEncryption(encryption);
        ZipArchiveOptions.Read readOptions = ZipArchiveOptions.READ_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(password));

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, createOptions)) {
            var entry = writer.beginFile("encrypted.bin");
            ZipArkivoEntryAttributeView view = entry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            try (OutputStream output = entry.openOutputStream()) {
                writeOneByteAtATime(output, expected);
            }
        }

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray()),
                readOptions
        )) {
            assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals(ZipMethod.STORED, attributes.compressionMethod());
            assertEquals(encryption, attributes.encryption());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(expected, readOneByteAtATime(input));
                assertEquals(-1, input.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Writes every source byte through [OutputStream#write(int)].
    private static void writeOneByteAtATime(OutputStream output, byte[] source) throws IOException {
        for (byte value : source) {
            output.write(Byte.toUnsignedInt(value));
        }
    }

    /// Reads the complete stream through [InputStream#read()].
    private static byte[] readOneByteAtATime(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            output.write(value);
        }
        return output.toByteArray();
    }

    /// Returns content containing descriptor-signature bytes, zeroes, and unsigned high bytes.
    private static byte[] payload() {
        return new byte[]{
                0,
                1,
                0x50,
                0x4b,
                0x07,
                0x08,
                0x7f,
                (byte) 0x80,
                (byte) 0xfe,
                (byte) 0xff,
                0,
                42
        };
    }
}
