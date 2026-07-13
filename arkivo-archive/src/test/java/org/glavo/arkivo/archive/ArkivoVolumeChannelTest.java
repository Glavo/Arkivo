// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shared logical channel for finite multi-volume archive sources.
@NotNullByDefault
final class ArkivoVolumeChannelTest {
    /// Temporary volume directory for each test invocation.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies boundary metadata, empty volumes, cross-volume reads, and logical seeking.
    @Test
    void readsAndSeeksAcrossPhysicalVolumes() throws IOException {
        @Unmodifiable List<Path> paths = List.of(
                writeVolume("00", ""),
                writeVolume("01", "abc"),
                writeVolume("02", ""),
                writeVolume("03", "def")
        );

        try (ArkivoVolumeChannel channel = ArkivoVolumeChannel.open(ArkivoVolumeSource.of(paths))) {
            assertEquals(4L, channel.volumeCount());
            assertEquals(0L, channel.volumeStartOffset(0L));
            assertEquals(0L, channel.volumeStartOffset(1L));
            assertEquals(3L, channel.volumeStartOffset(2L));
            assertEquals(3L, channel.volumeStartOffset(3L));
            assertEquals(0L, channel.volumeSize(0L));
            assertEquals(3L, channel.volumeSize(1L));
            assertEquals(0L, channel.volumeSize(2L));
            assertEquals(3L, channel.volumeSize(3L));
            assertEquals(6L, channel.size());

            ByteBuffer all = ByteBuffer.allocate(6);
            assertEquals(6, channel.read(all));
            assertArrayEquals(bytes("abcdef"), all.array());
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

            channel.position(2L);
            ByteBuffer middle = ByteBuffer.allocate(3);
            assertEquals(3, channel.read(middle));
            assertArrayEquals(bytes("cde"), middle.array());

            channel.position(100L);
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertEquals(100L, channel.position());
        }
    }

    /// Verifies the logical channel remains read-only and validates volume indexes and closed state.
    @Test
    void rejectsMutationAndInvalidMetadataAccess() throws IOException {
        Path volume = writeVolume("archive", "content");
        ArkivoVolumeChannel channel = ArkivoVolumeChannel.open(ArkivoVolumeSource.of(List.of(volume)));

        assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.wrap(bytes("x"))));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertThrows(IOException.class, () -> channel.volumeStartOffset(1L));

        channel.close();
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, channel::volumeCount);
    }

    /// Verifies setup failure closes every channel already obtained without closing the source contract.
    @Test
    void closesOpenedChannelsAfterSetupFailure() throws IOException {
        TrackingChannel first = new TrackingChannel(openVolume(writeVolume("first", "a")), false, false);
        TrackingChannel second = new TrackingChannel(openVolume(writeVolume("second", "b")), true, false);
        TrackingVolumeSource source = new TrackingVolumeSource(List.of(first, second));

        IOException exception = assertThrows(IOException.class, () -> ArkivoVolumeChannel.open(source));

        assertEquals("size failure", exception.getMessage());
        assertFalse(first.isOpen());
        assertFalse(second.isOpen());
        assertFalse(source.closed);
    }

    /// Verifies close failures are collected and channels left open are retried by a later close call.
    @Test
    void retriesIncompleteVolumeCleanup() throws IOException {
        TrackingChannel first = new TrackingChannel(openVolume(writeVolume("first", "a")), false, true);
        TrackingChannel second = new TrackingChannel(openVolume(writeVolume("second", "b")), false, true);
        ArkivoVolumeChannel channel = ArkivoVolumeChannel.open(new TrackingVolumeSource(List.of(first, second)));

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals("close failure", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertFalse(channel.isOpen());
        assertTrue(first.isOpen());
        assertTrue(second.isOpen());
        assertThrows(ClosedChannelException.class, channel::size);

        channel.close();
        assertFalse(first.isOpen());
        assertFalse(second.isOpen());
        assertEquals(2, first.closeAttempts);
        assertEquals(2, second.closeAttempts);
    }

    /// Verifies a volume ending before its snapshotted size is rejected instead of silently skipping bytes.
    @Test
    void rejectsPrematureVolumeEnd() throws IOException {
        PrematureEndChannel truncated = new PrematureEndChannel();
        try (ArkivoVolumeChannel channel = ArkivoVolumeChannel.open(index -> index == 0L ? truncated : null)) {
            ByteBuffer target = ByteBuffer.allocate(3);
            assertThrows(EOFException.class, () -> channel.read(target));
            assertEquals(2, target.position());
        }
    }

    /// Writes one UTF-8 test volume and returns its path.
    private Path writeVolume(String name, String content) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, bytes(content));
        return path;
    }

    /// Opens one test volume for read-only access.
    private static SeekableByteChannel openVolume(Path path) throws IOException {
        return Files.newByteChannel(path, StandardOpenOption.READ);
    }

    /// Encodes one test value as UTF-8 bytes.
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// Supplies a fixed sequence of tracking channels and records source closure.
    @NotNullByDefault
    private static final class TrackingVolumeSource implements ArkivoVolumeSource {
        /// Channels returned in volume order.
        private final @Unmodifiable List<TrackingChannel> channels;

        /// Whether the source itself was closed.
        private boolean closed;

        /// Creates a source for the given channels.
        private TrackingVolumeSource(List<TrackingChannel> channels) {
            this.channels = List.copyOf(channels);
        }

        /// Returns one configured volume or signals the end of the sequence.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return index >= 0L && index < channels.size() ? channels.get((int) index) : null;
        }

        /// Records explicit source closure.
        @Override
        public void close() {
            closed = true;
        }
    }

    /// Wraps a test channel with configurable size and first-close failures.
    @NotNullByDefault
    private static final class TrackingChannel implements SeekableByteChannel {
        /// Backing file channel.
        private final SeekableByteChannel delegate;

        /// Whether size lookup fails.
        private final boolean failSize;

        /// Whether the first close attempt fails while leaving the channel open.
        private final boolean failFirstClose;

        /// Number of close attempts.
        private int closeAttempts;

        /// Creates a tracking wrapper.
        private TrackingChannel(SeekableByteChannel delegate, boolean failSize, boolean failFirstClose) {
            this.delegate = delegate;
            this.failSize = failSize;
            this.failFirstClose = failFirstClose;
        }

        /// Reads from the backing channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate.read(destination);
        }

        /// Delegates writes to the backing channel.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns the backing position.
        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        /// Sets the backing position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        /// Returns the backing size or the configured setup failure.
        @Override
        public long size() throws IOException {
            if (failSize) {
                throw new IOException("size failure");
            }
            return delegate.size();
        }

        /// Delegates truncation to the backing channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        /// Returns whether the backing channel remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the configured first close attempt, then closes the backing channel.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (failFirstClose && closeAttempts == 1) {
                throw new IOException("close failure");
            }
            delegate.close();
        }
    }

    /// Reports three bytes at setup but exposes only two bytes while reading.
    @NotNullByDefault
    private static final class PrematureEndChannel implements SeekableByteChannel {
        /// Readable test content.
        private final ByteBuffer content = ByteBuffer.wrap(bytes("ab"));

        /// Whether this channel remains open.
        private boolean open = true;

        /// Reads available test bytes.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(content.remaining(), destination.remaining());
            ByteBuffer slice = content.slice();
            slice.limit(count);
            destination.put(slice);
            content.position(content.position() + count);
            return count;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the current read position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return content.position();
        }

        /// Sets the current read position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L || newPosition > content.limit()) {
                throw new IllegalArgumentException("invalid position");
            }
            content.position((int) newPosition);
            return this;
        }

        /// Returns the larger snapshotted size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return 3L;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
