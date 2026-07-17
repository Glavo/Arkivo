// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP entry decoders receive operation-scoped archive resource limits.
@NotNullByDefault
public final class ZipDecoderLimitsTest {
    /// A window ceiling smaller than every supported raw LZMA dictionary.
    private static final long MAXIMUM_WINDOW_SIZE = 1L;

    /// The logical path written into the test archive.
    private static final String ENTRY_PATH = "payload.bin";

    /// Verifies ZIP maps decoded size, effective window size, and decoder memory independently.
    @Test
    void mapsArchiveLimitsToCodecLimits() {
        ArchiveReadLimits archiveLimits = ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(8192L)
                .maximumDecoderMemorySize(4096L)
                .build();

        DecompressionLimits codecLimits = ZipCompressionFormats.decompressionLimits(123L, archiveLimits);

        assertEquals(123L, codecLimits.maximumOutputSize());
        assertEquals(4096L, codecLimits.maximumWindowSize());
        assertEquals(4096L, codecLimits.maximumMemorySize());
    }

    /// Verifies strongly typed ZIP read and update options retain their archive read limits.
    @Test
    void retainsReadAndUpdateLimitsInZipConfiguration() {
        ArchiveReadLimits limits = limitedWindow();
        ZipArchiveOptions.Read readOptions = readOptions(limits);
        ZipArchiveOptions.Update updateOptions = new ZipArchiveOptions.Update(
                ArchiveUpdateOptions.DEFAULT.withLimits(limits),
                null,
                ZipArchiveOptions.UPDATE_DEFAULTS.defaultEncryption(),
                ZipArchiveOptions.DEFAULT_LEGACY_CHARSET_DETECTOR
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
        return new ZipArchiveOptions.Read(
                ArchiveReadOptions.DEFAULT.withLimits(limits),
                null,
                ZipArchiveOptions.DEFAULT_LEGACY_CHARSET_DETECTOR
        );
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
