// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Verifies ZIP entry codecs receive reusable archive resource limits.
@NotNullByDefault
public final class ZipDecoderLimitsTest {
    /// A window ceiling smaller than every supported raw LZMA dictionary.
    private static final long MAXIMUM_WINDOW_SIZE = 1L;

    /// The logical path written into the test archive.
    private static final String ENTRY_PATH = "payload.bin";

    /// Known STORED sizes bound the complete plaintext payload, including encrypted and seekable entry paths.
    @ParameterizedTest
    @EnumSource(ZipEncryption.class)
    void rejectsUnderstatedStoredSizesBeforeReadingBodies(ZipEncryption encryption, @TempDir Path directory)
            throws IOException {
        byte[] body = new byte[16_384];
        Path archive = directory.resolve("stored.zip");
        var password = ArkivoPasswordProvider.fixed("password".toCharArray(), StandardCharsets.UTF_8);
        CRC32 checksum = new CRC32();
        checksum.update(body);
        try (var writer = ZipArkivoStreamingWriter.create(archive,
                ZipArchiveOptions.CREATE_DEFAULTS.withPasswordProvider(password))) {
            var entry = writer.beginFile(ENTRY_PATH);
            var attributes = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            attributes.setMethod(ZipMethod.STORED);
            attributes.setEncryption(encryption);
            attributes.setUncompressedSizeAndCrc32(body.length, checksum.getValue());
            try (var output = entry.openOutputStream()) {
                output.write(body);
            }
        }
        var validOptions = readOptions(ArchiveReadLimits.builder()
                .maximumEntrySize(body.length).maximumTotalEntrySize(body.length).build()).withPasswordProvider(password);
        try (var fileSystem = ZipArkivoFileSystem.open(archive, validOptions)) {
            assertArrayEquals(body, Files.readAllBytes(fileSystem.getPath(ENTRY_PATH)));
        }
        try (var reader = ZipArkivoStreamingReader.open(archive, validOptions)) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(body, input.readAllBytes());
            }
        }

        byte[] bytes = Files.readAllBytes(archive);
        int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        assertEquals(0, ByteArrayAccess.readShortLittleEndian(bytes, 6) & ZipConstants.DATA_DESCRIPTOR_FLAG);
        ByteArrayAccess.writeIntLittleEndian(bytes, 22, 1);
        ByteArrayAccess.writeIntLittleEndian(bytes, central + 24, 1);
        Files.write(archive, bytes);
        var limited = readOptions(ArchiveReadLimits.builder().maximumEntrySize(1).maximumTotalEntrySize(1).build())
                .withPasswordProvider(request -> { throw new AssertionError("Invalid sizes must precede password lookup"); });
        try (var fileSystem = ZipArkivoFileSystem.open(archive, limited)) {
            Path entry = fileSystem.getPath(ENTRY_PATH);
            assertThrows(IOException.class, () -> Files.newInputStream(entry));
            assertThrows(IOException.class, () -> Files.newByteChannel(entry));
            assertThrows(IOException.class, () -> Files.size(entry));
        }
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        int remaining;
        try (var reader = ZipArkivoStreamingReader.open(source, limited)) {
            assertThrows(IOException.class, reader::next);
            remaining = source.available();
            int headerSize = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 26))
                    + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 28));
            assertEquals(bytes.length - headerSize, remaining);
        }
        assertEquals(remaining, source.available());
    }

    /// Verifies ZIP maps decoded size, effective window size, and decoder memory independently.
    @Test
    void mapsArchiveLimitsToCodecConfiguration() {
        ArchiveReadLimits archiveLimits = ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(8192L)
                .maximumDecoderMemorySize(4096L)
                .build();

        CompressionCodec<?> codec = ZipCompressionFormats.withDecodingLimits(
                new RawLZMACodec(),
                123L,
                archiveLimits
        );

        assertEquals(123L, codec.maximumOutputSize());
        assertEquals(4096L, codec.maximumWindowSize());
        assertEquals(4096L, codec.maximumMemorySize());
    }

    /// Verifies strongly typed ZIP read and update options retain their archive read limits.
    @Test
    void retainsReadAndUpdateLimitsInZipConfiguration() {
        ArchiveReadLimits limits = limitedWindow();
        ZipArchiveOptions.Read readOptions = readOptions(limits);
        ZipArchiveOptions.Update updateOptions = new ZipArchiveOptions.Update(
                ArchiveUpdateOptions.DEFAULT.withLimits(limits),
                ZipArchiveOptions.UPDATE_DEFAULTS.defaultEncryption()
        );

        assertSame(limits, ZipArkivoFileSystemConfig.fromReadOptions(readOptions).readLimits());
        assertSame(limits, ZipArkivoFileSystemConfig.fromUpdateOptions(updateOptions).readLimits());
    }

    /// Verifies a streaming raw LZMA entry cannot allocate beyond the configured window ceiling.
    @Test
    void streamingReaderEnforcesLzmaWindowLimit() throws Exception {
        byte[] archive = createLzmaArchive();
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                readOptions(limitedWindow())
        )) {
            assertTrue(reader.next());
            DecompressionWindowLimitException exception = assertThrows(
                    DecompressionWindowLimitException.class,
                    reader::openInputStream
            );
            assertWindowLimit(exception);
        }
    }

    /// Verifies a seekable raw LZMA entry cannot allocate beyond the configured window ceiling.
    @Test
    void seekableReaderEnforcesLzmaWindowLimit() throws Exception {
        Path archive = Files.createTempFile("arkivo-zip-decoder-limits-", ".zip");
        try {
            Files.write(archive, createLzmaArchive());
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archive,
                    readOptions(limitedWindow())
            )) {
                DecompressionWindowLimitException exception = assertThrows(
                        DecompressionWindowLimitException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH))
                );
                assertWindowLimit(exception);
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Creates one complete ZIP archive containing an LZMA-compressed regular file.
    private static byte[] createLzmaArchive() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            var entry = writer.beginFile(ENTRY_PATH);
            ZipArkivoEntryAttributeView attributes = Objects.requireNonNull(
                    entry.attributeView(ZipArkivoEntryAttributeView.class)
            );
            attributes.setMethod(ZipMethod.LZMA);
            try (OutputStream output = entry.openOutputStream()) {
                output.write("resource-limited ZIP LZMA content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return archive.toByteArray();
    }

    /// Returns a ZIP read configuration containing the given limits.
    private static ZipArchiveOptions.Read readOptions(ArchiveReadLimits limits) {
        return new ZipArchiveOptions.Read(ArchiveReadOptions.DEFAULT.withLimits(limits));
    }

    /// Returns archive limits with only a deliberately tiny compression window ceiling.
    private static ArchiveReadLimits limitedWindow() {
        return ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(MAXIMUM_WINDOW_SIZE)
                .build();
    }

    /// Verifies one decoder failure reports the configured ceiling and a larger required window.
    private static void assertWindowLimit(DecompressionWindowLimitException exception) {
        assertEquals(MAXIMUM_WINDOW_SIZE, exception.maximumWindowSize());
        assertTrue(exception.requiredWindowSize() > MAXIMUM_WINDOW_SIZE);
    }
}
