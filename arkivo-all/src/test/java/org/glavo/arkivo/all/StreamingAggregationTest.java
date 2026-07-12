// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingFormat;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies unified forward-only archive detection and reader dispatch through the aggregate module.
@NotNullByDefault
final class StreamingAggregationTest {
    /// The path stored in generated streaming archives.
    private static final String ENTRY_PATH = "value.txt";

    /// The content stored in generated streaming archives.
    private static final byte[] CONTENT = "unified streaming archive".getBytes(StandardCharsets.UTF_8);

    /// Verifies the installed formats that advertise forward-only readers.
    @Test
    void discoversStreamingFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoStreamingFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("ar", "rar", "tar", "zip"), formatNames);
    }

    /// Verifies lossless prefix replay for every format with a streaming writer.
    @Test
    void opensWritableStreamingFormatsFromInputStreams() throws IOException {
        assertArchive(tarArchive());
        assertArchive(arArchive());
        assertArchive(zipArchive());
    }

    /// Verifies successful reader ownership of a non-seekable channel.
    @Test
    void opensAndClosesReadableChannel() throws IOException {
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(tarArchive()));
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(channel)) {
            assertEntry(reader);
        }
        assertFalse(channel.isOpen());
    }

    /// Verifies that a fully validated TAR header wins over a short ZIP-like filename prefix.
    @Test
    void prefersValidatedTarOverShortZipPrefix() throws IOException {
        byte[] archive = tarArchive("PK\u0003\u0004value.txt");
        @Nullable ArkivoFormat detected = ArkivoFormats.detect(ByteBuffer.wrap(archive));
        assertNotNull(detected);
        assertEquals("tar", detected.name());
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive)
        )) {
            assertInstanceOf(TarArkivoStreamingReader.class, reader);
            assertEntry(reader);
        }
    }

    /// Verifies that RAR signatures dispatch to the RAR streaming reader.
    @Test
    void dispatchesRarSignature() throws IOException {
        byte[] signature = {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(signature)
        )) {
            assertInstanceOf(RarArkivoStreamingReader.class, reader);
        }
    }

    /// Verifies that detected seekable-only formats are rejected and their sources are closed.
    @Test
    void rejectsSeekableOnlyFormatAndClosesSource() {
        TrackingInputStream source = new TrackingInputStream(
                new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c}
        );
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader(source)
        );
        assertTrue(exception.getMessage().contains("7z"));
        assertTrue(source.closed());
    }

    /// Verifies unknown-format and zero-progress failures close their consumed sources.
    @Test
    void closesSourcesAfterProbeFailures() {
        TrackingInputStream unknown = new TrackingInputStream(new byte[]{1, 2, 3, 4});
        IOException unknownException = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader(unknown)
        );
        assertEquals("Unrecognized archive format", unknownException.getMessage());
        assertTrue(unknown.closed());

        ZeroProgressInputStream stalled = new ZeroProgressInputStream();
        IOException stalledException = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader(stalled)
        );
        assertEquals("Archive stream probe made no progress", stalledException.getMessage());
        assertTrue(stalled.closed());
    }

    /// Verifies environment options reach the detected format and setup failures close the source.
    @Test
    void forwardsEnvironmentAndClosesSourceAfterSetupFailure() throws IOException {
        TrackingInputStream source = new TrackingInputStream(zipArchive());
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.openStreamingReader(
                        source,
                        Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), "invalid")
                )
        );
        assertTrue(source.closed());
    }

    /// Creates a TAR archive with one entry.
    private static byte[] tarArchive() throws IOException {
        return tarArchive(ENTRY_PATH);
    }

    /// Creates a TAR archive with one entry at the given path.
    private static byte[] tarArchive(String entryPath) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writeEntry(writer, entryPath);
        }
        return output.toByteArray();
    }

    /// Creates an AR archive with one entry.
    private static byte[] arArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Creates a ZIP archive with one entry.
    private static byte[] zipArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Writes one regular file entry to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer) throws IOException {
        writeEntry(writer, ENTRY_PATH);
    }

    /// Writes one regular file entry with the given path to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer, String entryPath) throws IOException {
        writer.beginFile(entryPath);
        try (OutputStream body = writer.openOutputStream()) {
            body.write(CONTENT);
        }
    }

    /// Opens and verifies the single entry in an automatically detected archive.
    private static void assertArchive(byte[] archive) throws IOException {
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive)
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies and consumes one streaming archive entry.
    private static void assertEntry(ArkivoStreamingReader reader) throws IOException {
        assertTrue(reader.next());
        BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
        assertTrue(attributes.isRegularFile());
        try (InputStream body = reader.openInputStream()) {
            assertArrayEquals(CONTENT, body.readAllBytes());
        }
        assertFalse(reader.next());
    }

    /// Tracks whether a byte-array input stream has been closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Creates a tracked source over the given bytes.
        private TrackingInputStream(byte[] content) {
            super(content);
        }

        /// Closes this source.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closed;
        }

    }

    /// Reports zero progress for every bulk read.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends InputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Returns no byte for single-byte reads.
        @Override
        public int read() {
            return 0;
        }

        /// Reports no progress for bulk reads.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return 0;
        }

        /// Closes this source.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closed;
        }
    }
}
