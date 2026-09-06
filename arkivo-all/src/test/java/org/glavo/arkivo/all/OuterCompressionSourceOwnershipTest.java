// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies source ownership when generic readers and file systems adapt non-native outer compression.
@NotNullByDefault
final class OuterCompressionSourceOwnershipTest {
    /// Archive-local path of the generated ZIP entry.
    private static final String ENTRY_PATH = "payload.txt";

    /// Content stored in the generated ZIP entry.
    private static final byte @Unmodifiable [] CONTENT =
            "outer compression contract".getBytes(StandardCharsets.UTF_8);

    /// Directory containing repeatable compressed test sources.
    @TempDir
    private Path temporaryDirectory;

    /// Next unique file index within one test instance.
    private int sourceIndex;

    /// Verifies a repeatable source is released before its materialized file system is returned.
    @Test
    void materializesRepeatableSourceAndReleasesItImmediately() throws IOException {
        TrackingSource source = source(gzip(zipArchive()), null);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(source)) {
            assertInstanceOf(ZipArkivoFileSystem.class, fileSystem);
            assertEquals(1, source.closeCount());
            assertTrue(source.allOpenedChannelsClosed());
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
        }

        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies the volume-source overload applies the same materialization and early-release contract.
    @Test
    void materializesVolumeSourceAndReleasesItImmediately() throws IOException {
        TrackingSource source = source(gzip(zipArchive()), null);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem((ArkivoVolumeSource) source)) {
            assertInstanceOf(ZipArkivoFileSystem.class, fileSystem);
            assertEquals(1, source.closeCount());
            assertTrue(source.allOpenedChannelsClosed());
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
        }

        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies repeatable-source close failure aborts the open without a second ownership close attempt.
    @Test
    void propagatesRepeatableSourceCloseFailureOnce() throws IOException {
        IOException closeFailure = new IOException("source close failed");
        TrackingSource source = source(gzip(zipArchive()), closeFailure);

        IOException failure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem(source)
        );
        assertSame(closeFailure, failure);
        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies volume-source close failure aborts the open without a second ownership close attempt.
    @Test
    void propagatesVolumeSourceCloseFailureOnce() throws IOException {
        IOException closeFailure = new IOException("source close failed");
        TrackingSource source = source(gzip(zipArchive()), closeFailure);

        IOException failure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem((ArkivoVolumeSource) source)
        );
        assertSame(closeFailure, failure);
        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies GZIP checksum failures release every opened source channel and retain source-close failures.
    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void closesSourcesAfterOuterChecksumFailure(boolean volumeSource, boolean failClose) throws IOException {
        byte[] compressed = gzip(zipArchive());
        compressed[compressed.length - 8] ^= 1;
        @Nullable IOException closeFailure = failClose ? new IOException("source close failed") : null;
        TrackingSource source = source(compressed, closeFailure);

        IOException failure = assertThrows(IOException.class, () -> {
            try (ArkivoFileSystem ignored = volumeSource
                    ? ArkivoFormats.openFileSystem((ArkivoVolumeSource) source)
                    : ArkivoFormats.openFileSystem(source)) {
                throw new AssertionError("Corrupt outer checksum was accepted");
            }
        });

        if (closeFailure != null) {
            assertNotSame(closeFailure, failure);
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(closeFailure));
        }
        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies truncated GZIP trailers cannot produce a file system or leak either source overload's channels.
    @ParameterizedTest
    @ValueSource(ints = {1, 4, 8})
    void closesSourcesAfterTruncatedOuterTrailer(int missingBytes) throws IOException {
        byte[] compressed = gzip(zipArchive());
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - missingBytes);
        TrackingSource repeatable = source(truncated, null);
        assertThrows(IOException.class, () -> {
            try (ArkivoFileSystem ignored = ArkivoFormats.openFileSystem(repeatable)) {
                throw new AssertionError("Truncated outer trailer was accepted");
            }
        });
        assertEquals(1, repeatable.closeCount());
        assertTrue(repeatable.allOpenedChannelsClosed());

        TrackingSource volumes = source(truncated, null);
        assertThrows(IOException.class, () -> {
            try (ArkivoFileSystem ignored = ArkivoFormats.openFileSystem((ArkivoVolumeSource) volumes)) {
                throw new AssertionError("Truncated outer trailer was accepted");
            }
        });
        assertEquals(1, volumes.closeCount());
        assertTrue(volumes.allOpenedChannelsClosed());
    }

    /// Verifies a transformed streaming reader remains usable after its volume source is released.
    @Test
    void opensStreamingReaderAndReleasesVolumeSourceImmediately() throws IOException {
        TrackingSource source = source(gzip(zipArchive()), null);

        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader((ArkivoVolumeSource) source)) {
            assertEquals(1, source.closeCount());
            assertFalse(source.allOpenedChannelsClosed());
            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(CONTENT, body.readAllBytes());
            }
            assertFalse(reader.next());
        }

        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Verifies a transformed streaming reader is discarded when volume-source cleanup fails.
    @Test
    void propagatesStreamingVolumeSourceCloseFailureOnce() throws IOException {
        IOException closeFailure = new IOException("source close failed");
        TrackingSource source = source(gzip(zipArchive()), closeFailure);

        IOException failure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader((ArkivoVolumeSource) source)
        );

        assertSame(closeFailure, failure);
        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());
    }

    /// Creates one path-backed test source over immutable compressed bytes.
    private TrackingSource source(byte[] bytes, @Nullable IOException closeFailure) throws IOException {
        Path path = temporaryDirectory.resolve("source-" + sourceIndex++ + ".bin");
        Files.write(path, bytes);
        return new TrackingSource(path, closeFailure);
    }

    /// Creates a ZIP archive containing the shared test entry.
    private static byte[] zipArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            try (OutputStream body = writer.beginFile(ENTRY_PATH).openOutputStream()) {
                body.write(CONTENT);
            }
        }
        return output.toByteArray();
    }

    /// Wraps one byte sequence in a GZIP stream.
    private static byte[] gzip(byte[] source) throws IOException {
        ByteBuffer encoded = CompressionFormats.require("gzip")
                .defaultCodec()
                .compress(ByteBuffer.wrap(source));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Opens independent channels for one path and records source-ownership closure.
    @NotNullByDefault
    private static final class TrackingSource implements ArkivoSeekableChannelSource {
        /// Path containing the immutable source bytes.
        private final Path path;

        /// Failure reported by source close, or `null` for successful cleanup.
        private final @Nullable IOException closeFailure;

        /// Channels returned to archive consumers.
        private final ArrayList<SeekableByteChannel> openedChannels = new ArrayList<>();

        /// Number of source close attempts.
        private int closeCount;

        /// Whether source cleanup has completed successfully.
        private boolean closed;

        /// Creates a tracked source for one path.
        private TrackingSource(Path path, @Nullable IOException closeFailure) {
            this.path = path;
            this.closeFailure = closeFailure;
        }

        /// Opens an independent channel while source cleanup has not completed.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new ClosedChannelException();
            }
            SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
            openedChannels.add(channel);
            return channel;
        }

        /// Records one close attempt and reports the configured failure when present.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            closed = true;
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether at least one channel was opened and every opened channel is closed.
        private boolean allOpenedChannelsClosed() {
            return !openedChannels.isEmpty() && openedChannels.stream().noneMatch(SeekableByteChannel::isOpen);
        }
    }
}
