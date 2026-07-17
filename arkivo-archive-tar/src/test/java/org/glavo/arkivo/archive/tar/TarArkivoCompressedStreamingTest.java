// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies automatic and explicit TAR stream compression with resource ownership.
@NotNullByDefault
final class TarArkivoCompressedStreamingTest {
    /// The entry content used by streaming round trips.
    private static final byte[] CONTENT = "compressed streaming TAR".getBytes(StandardCharsets.UTF_8);

    /// Verifies gzip streaming through ordinary non-seekable channels and a stable compression format name.
    @Test
    void roundTripsGzipThroughChannels() throws IOException {
        TarArchiveOptions.Create createOptions = createOptions("gzip");
        TarArchiveOptions.Read readOptions = readOptions("gzip");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        WritableByteChannel output = Channels.newChannel(archive);
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output, createOptions)) {
            writeEntry(writer);
        }
        assertFalse(output.isOpen());

        byte[] archiveBytes = archive.toByteArray();
        @Nullable CompressionFormat detected = CompressionFormats.detect(ByteBuffer.wrap(archiveBytes));
        assertNotNull(detected);
        assertEquals("gzip", detected.name());

        ReadableByteChannel input = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(input, readOptions)) {
            assertEntry(reader);
        }
        assertFalse(input.isOpen());
    }

    /// Verifies gzip is detected through the channel factory when no compression option is present.
    @Test
    void detectsGzipThroughForwardOnlyChannel() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, createOptions("gzip"))) {
            writeEntry(writer);
        }

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(archive.toByteArray()));
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source)) {
            assertEntry(reader);
        }
        assertFalse(source.isOpen());
    }

    /// Verifies zlib is detected through the input-stream adapter without an explicit option.
    @Test
    void detectsZlibThroughForwardOnlyStream() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, createOptions("zlib"))) {
            writeEntry(writer);
        }

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray())
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies codec-object configuration through the TAR format facade.
    @Test
    void roundTripsRawDeflateThroughFormatFacade() throws IOException {
        CompressionCodec codec = CompressionFormats.require("deflate").defaultCodec();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(
                archive,
                TarArchiveOptions.CREATE_DEFAULTS.withCompression(codec)
        )) {
            writeEntry(writer);
        }
        assertNull(CompressionFormats.detect(ByteBuffer.wrap(archive.toByteArray())));

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray()),
                TarArchiveOptions.READ_DEFAULTS.withCompression(codec)
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies compressed path creation with caller-selected body storage.
    @Test
    void createsCompressedPathWithOwnedBodyStorage() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-streaming-", ".tar.gz");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(
                    archivePath,
                    ArkivoEditStorage.memory(),
                    createOptions("gzip")
            )) {
                writeEntry(writer);
            }
            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                    Files.newInputStream(archivePath),
                    readOptions("gzip")
            )) {
                assertEntry(reader);
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that empty environments preserve uncompressed streaming behavior.
    @Test
    void keepsRawTarAsTheStreamingDefault() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive)) {
            writeEntry(writer);
        }
        byte[] archiveBytes = archive.toByteArray();
        assertNull(CompressionFormats.detect(ByteBuffer.wrap(archiveBytes)));
        assertTrue(TarArkivoFormat.instance().matches(ByteBuffer.wrap(archiveBytes)));

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archiveBytes)
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies a validated raw TAR header wins over a zlib-like filename prefix during streaming detection.
    @Test
    void rejectsFalsePositiveStreamingCompression() throws IOException {
        String entryName = "x\u0001value.txt";
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive)) {
            var writerEntry167 = writer.beginFile(entryName);
            try (OutputStream body = writerEntry167.openOutputStream()) {
                body.write(CONTENT);
            }
        }
        byte[] archiveBytes = archive.toByteArray();
        @Nullable CompressionFormat detected = CompressionFormats.detect(ByteBuffer.wrap(archiveBytes));
        assertNotNull(detected);
        assertEquals("zlib", detected.name());

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archiveBytes)
        )) {
            var readerEntry180 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals(entryName, readerEntry180.attributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = readerEntry180.openInputStream()) {
                assertArrayEquals(CONTENT, body.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Returns typed TAR creation options for one registered compression format.
    private static TarArchiveOptions.Create createOptions(String formatName) {
        return TarArchiveOptions.CREATE_DEFAULTS.withCompression(
                CompressionFormats.require(formatName).defaultCodec()
        );
    }

    /// Returns typed TAR read options for one registered compression format.
    private static TarArchiveOptions.Read readOptions(String formatName) {
        return TarArchiveOptions.READ_DEFAULTS.withCompression(
                CompressionFormats.require(formatName).defaultCodec()
        );
    }

    /// Writes one regular file entry.
    private static void writeEntry(TarArkivoStreamingWriter writer) throws IOException {
        var writerEntry207 = writer.beginFile("value.txt");
        try (OutputStream body = writerEntry207.openOutputStream()) {
            body.write(CONTENT);
        }
    }

    /// Verifies and consumes one regular file entry.
    private static void assertEntry(TarArkivoStreamingReader reader) throws IOException {
        var readerEntry215 = java.util.Objects.requireNonNull(reader.nextEntry());
        TarArkivoEntryAttributes attributes = readerEntry215.attributes(TarArkivoEntryAttributes.class);
        assertEquals("value.txt", attributes.path());
        try (InputStream body = readerEntry215.openInputStream()) {
            assertArrayEquals(CONTENT, body.readAllBytes());
        }
        org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
    }
}
