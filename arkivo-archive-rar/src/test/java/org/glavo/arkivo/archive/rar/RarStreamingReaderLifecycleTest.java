// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR streaming-entry boundaries and owned-source cleanup retry behavior.
@NotNullByDefault
final class RarStreamingReaderLifecycleTest {
    /// Verifies stored entry streams implement bounded reads, skips, availability, and close-time draining.
    @Test
    void storedEntryStreamsPreserveBoundariesAcrossPartialConsumption() throws IOException {
        byte[] first = {1, 2, 3, 4, 5};
        byte[] second = {6, 7, 8, 9};
        byte[] third = {10, 11};
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("first.bin", first);
        entries.put("second.bin", second);
        entries.put("third.bin", third);
        byte[] archive = RarTestArchiveFixtures.storedArchive(entries);

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                int initialAvailable = body.available();
                assertTrue(initialAvailable >= 0 && initialAvailable <= first.length);
                assertEquals(0, body.read(new byte[0], 0, 0));
                assertEquals(0L, body.skip(0L));
                assertEquals(0L, body.skip(-1L));
                assertThrows(NullPointerException.class, () -> body.read(null, 0, 1));
                assertThrows(IndexOutOfBoundsException.class, () -> body.read(new byte[1], 1, 1));

                assertEquals(1, body.read());
                assertEquals(2L, body.skip(2L));
                int remainingAvailable = body.available();
                assertTrue(remainingAvailable >= 0 && remainingAvailable <= 2);
                byte[] tail = new byte[4];
                assertEquals(2, body.read(tail, 1, 2));
                assertArrayEquals(new byte[]{0, 4, 5, 0}, tail);
                assertEquals(0, body.available());
                assertEquals(-1, body.read());
                assertEquals(0L, body.skip(Long.MAX_VALUE));
            }

            assertTrue(reader.next());
            InputStream partiallyRead = reader.openInputStream();
            assertEquals(6, partiallyRead.read());
            partiallyRead.close();
            partiallyRead.close();

            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(third, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies that reader close can retry single-stream source cleanup after failure.
    @Test
    void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(
                RarTestArchiveFixtures.storedArchive(
                        "hello.txt",
                        "hello".getBytes(StandardCharsets.UTF_8)
                )
        );
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source);
        assertTrue(reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Verifies that reader close retries an owned volume source and closes every opened channel.
    @Test
    void volumeReaderCloseRetriesSourceCleanupAfterFailure() throws IOException {
        RetryingVolumeSource source = new RetryingVolumeSource(
                RarTestArchiveFixtures.storedArchive(
                        "hello.txt",
                        "hello".getBytes(StandardCharsets.UTF_8)
                )
        );
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source);
        assertTrue(reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("source close failed", exception.getMessage());
        assertEquals(1, source.closeCount());
        assertTrue(source.allOpenedChannelsClosed());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Input stream that fails its first close call.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream over the given bytes.
        ///
        /// @param bytes the immutable test content
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records all close attempts.
        ///
        /// @throws IOException on the first close attempt
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close calls.
        ///
        /// @return the number of attempted closes
        private int closeCount() {
            return closeCount;
        }
    }

    /// Provides one in-memory volume and fails its first source-close attempt.
    @NotNullByDefault
    private static final class RetryingVolumeSource implements ArkivoVolumeSource {
        /// The immutable archive bytes exposed as volume zero.
        private final byte @Unmodifiable [] archive;

        /// Every physical channel opened by the reader.
        private final List<SeekableByteChannel> openedChannels = new ArrayList<>();

        /// The number of source close calls.
        private int closeCount;

        /// Creates a volume source over a private archive snapshot.
        ///
        /// @param archive the complete single-volume RAR archive
        private RetryingVolumeSource(byte[] archive) {
            this.archive = archive.clone();
        }

        /// Opens the sole RAR volume while the source remains usable.
        ///
        /// @param index the zero-based volume index
        /// @return a new channel for index zero, or `null` for the next volume
        /// @throws IOException if cleanup has already been attempted
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closeCount != 0) {
                throw new ClosedChannelException();
            }
            if (index != 0L) {
                return null;
            }
            SeekableByteChannel channel = new ReadOnlyByteArrayChannel(archive);
            openedChannels.add(channel);
            return channel;
        }

        /// Fails the first source-close attempt and succeeds on subsequent calls.
        ///
        /// @throws IOException on the first close attempt
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("source close failed");
            }
        }

        /// Returns the number of source close calls.
        ///
        /// @return the number of attempted closes
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether every physical volume channel has closed.
        ///
        /// @return `true` when no opened channel remains open
        private boolean allOpenedChannelsClosed() {
            return openedChannels.stream().noneMatch(SeekableByteChannel::isOpen);
        }
    }
}
