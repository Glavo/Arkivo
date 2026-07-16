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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies automatic and explicit TAR stream compression with resource ownership.
@NotNullByDefault
final class TarArkivoCompressedStreamingTest {
    /// The entry content used by streaming round trips.
    private static final byte[] CONTENT = "compressed streaming TAR".getBytes(StandardCharsets.UTF_8);

    /// Verifies gzip streaming through ordinary non-seekable channels and a stable compression format name.
    @Test
    void roundTripsGzipThroughChannels() throws IOException {
        Map<String, Object> environment = Map.of(TarArkivoFileSystem.COMPRESSION.key(), "gzip");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        WritableByteChannel output = Channels.newChannel(archive);
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output, environment)) {
            writeEntry(writer);
        }
        assertFalse(output.isOpen());

        byte[] archiveBytes = archive.toByteArray();
        @Nullable CompressionFormat detected = CompressionFormats.detect(ByteBuffer.wrap(archiveBytes));
        assertNotNull(detected);
        assertEquals("gzip", detected.name());

        ReadableByteChannel input = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(input, environment)) {
            assertEntry(reader);
        }
        assertFalse(input.isOpen());
    }

    /// Verifies gzip is detected through the channel factory when no compression option is present.
    @Test
    void detectsGzipThroughForwardOnlyChannel() throws IOException {
        Map<String, Object> environment = Map.of(TarArkivoFileSystem.COMPRESSION.key(), "gzip");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, environment)) {
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
        Map<String, Object> environment = Map.of(TarArkivoFileSystem.COMPRESSION.key(), "zlib");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, environment)) {
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
        Map<String, Object> environment = Map.of(TarArkivoFileSystem.COMPRESSION.key(), codec);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        TarArkivoFormat format = TarArkivoFormat.instance();
        try (TarArkivoStreamingWriter writer = format.openStreamingWriter(archive, environment)) {
            writeEntry(writer);
        }
        assertNull(CompressionFormats.detect(ByteBuffer.wrap(archive.toByteArray())));

        try (TarArkivoStreamingReader reader = format.openStreamingReader(
                new ByteArrayInputStream(archive.toByteArray()),
                environment
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies compressed path creation with caller-selected body storage.
    @Test
    void createsCompressedPathWithOwnedBodyStorage() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-streaming-", ".tar.gz");
        Map<String, Object> environment = Map.of(TarArkivoFileSystem.COMPRESSION.key(), "gzip");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(
                    archivePath,
                    ArkivoEditStorage.memory(),
                    environment
            )) {
                writeEntry(writer);
            }
            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                    Files.newInputStream(archivePath),
                    environment
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
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, Map.of())) {
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
            writer.beginFile(entryName);
            try (OutputStream body = writer.openOutputStream()) {
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
            assertTrue(reader.next());
            assertEquals(entryName, reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(CONTENT, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies that option conversion failures leave caller-owned streams open.
    @Test
    void leavesStreamsOpenWhenEnvironmentValidationFails() {
        Map<String, Object> environment = Map.of(
                TarArkivoFileSystem.COMPRESSION.key(),
                "missing-codec"
        );
        TrackingInputStream input = new TrackingInputStream();
        TrackingOutputStream output = new TrackingOutputStream();

        assertThrows(IllegalArgumentException.class, () -> TarArkivoStreamingReader.open(input, environment));
        assertThrows(IllegalArgumentException.class, () -> TarArkivoStreamingWriter.open(output, environment));
        assertFalse(input.closed());
        assertFalse(output.closed());
    }

    /// Writes one regular file entry.
    private static void writeEntry(TarArkivoStreamingWriter writer) throws IOException {
        writer.beginFile("value.txt");
        try (OutputStream body = writer.openOutputStream()) {
            body.write(CONTENT);
        }
    }

    /// Verifies and consumes one regular file entry.
    private static void assertEntry(TarArkivoStreamingReader reader) throws IOException {
        assertTrue(reader.next());
        TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
        assertEquals("value.txt", attributes.path());
        try (InputStream body = reader.openInputStream()) {
            assertArrayEquals(CONTENT, body.readAllBytes());
        }
        assertFalse(reader.next());
    }

    /// Tracks whether an input stream has been closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Creates an empty tracked input stream.
        private TrackingInputStream() {
            super(new byte[0]);
        }

        /// Closes this stream.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether this stream has closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Tracks whether an output stream has been closed.
    @NotNullByDefault
    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Closes this stream.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether this stream has closed.
        private boolean closed() {
            return closed;
        }
    }
}
