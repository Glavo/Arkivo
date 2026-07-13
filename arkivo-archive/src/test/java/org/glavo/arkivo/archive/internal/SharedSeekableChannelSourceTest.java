// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies independent logical views over one owned seekable channel.
@NotNullByDefault
final class SharedSeekableChannelSourceTest {
    /// Verifies logical origin, size, position isolation, and view-only closure.
    @Test
    void exposesIndependentViewsFromCurrentPosition() throws IOException {
        TrackingSeekableByteChannel backing = new TrackingSeekableByteChannel(new byte[]{9, 8, 1, 2, 3, 4});
        backing.position(2L);
        ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(backing);
        SeekableByteChannel first = source.openChannel();
        SeekableByteChannel second = source.openChannel();

        assertEquals(4L, first.size());
        assertEquals(4L, second.size());
        assertArrayEquals(new byte[]{1, 2}, read(first, 2));
        second.position(3L);
        assertArrayEquals(new byte[]{4}, read(second, 2));
        assertArrayEquals(new byte[]{3, 4}, read(first, 2));
        assertEquals(4L, first.position());
        assertEquals(4L, second.position());

        first.close();
        assertFalse(first.isOpen());
        assertTrue(second.isOpen());
        assertTrue(backing.isOpen());
        source.close();
        assertFalse(second.isOpen());
        assertFalse(backing.isOpen());
    }

    /// Verifies all logical views can read concurrently without sharing positions.
    @Test
    void serializesPhysicalAccessForConcurrentViews() throws Exception {
        byte[] expected = new byte[64 * 1024];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31);
        }
        TrackingSeekableByteChannel backing = new TrackingSeekableByteChannel(expected);
        try (ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(backing)) {
            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                List<Future<byte[]>> futures = new ArrayList<>();
                for (int index = 1; index <= 6; index++) {
                    int chunkSize = index * 37;
                    futures.add(executor.submit(() -> readAll(source, chunkSize)));
                }
                for (Future<byte[]> future : futures) {
                    assertArrayEquals(expected, get(future));
                }
            } finally {
                executor.shutdownNow();
            }
        }
        assertEquals(1, backing.maximumConcurrentAccess());
    }

    /// Verifies read-only operations and closed-channel precedence.
    @Test
    void enforcesReadOnlyViewSemantics() throws IOException {
        try (ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(
                new TrackingSeekableByteChannel(new byte[]{1, 2})
        )) {
            SeekableByteChannel view = source.openChannel();
            assertThrows(ReadOnlyBufferException.class, () -> view.read(ByteBuffer.allocate(1).asReadOnlyBuffer()));
            assertEquals(0L, view.position());
            assertThrows(UnsupportedOperationException.class, () -> view.write(ByteBuffer.allocate(1)));
            assertThrows(UnsupportedOperationException.class, () -> view.truncate(0L));
            assertThrows(IllegalArgumentException.class, () -> view.position(-1L));
            view.position(3L);
            assertEquals(-1, view.read(ByteBuffer.allocate(1)));
            view.close();
            assertThrows(ClosedChannelException.class, view::position);
            assertThrows(ClosedChannelException.class, () -> view.write(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> view.truncate(0L));
        }
    }

    /// Verifies failed source closure and source construction can release ownership safely.
    @Test
    void retriesClosureAndCleansUpConstructionFailure() throws IOException {
        TrackingSeekableByteChannel backing = new TrackingSeekableByteChannel(new byte[]{1, 2});
        backing.failFirstClose = true;
        ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(backing);
        SeekableByteChannel view = source.openChannel();
        assertThrows(IOException.class, source::close);
        assertTrue(view.isOpen());
        assertArrayEquals(new byte[]{1}, read(view, 1));
        source.close();
        source.close();
        assertEquals(2, backing.closeCount());
        assertThrows(ClosedChannelException.class, () -> view.read(ByteBuffer.allocate(1)));

        TrackingSeekableByteChannel invalid = new TrackingSeekableByteChannel(new byte[0]);
        invalid.failPositionQuery = true;
        assertThrows(IOException.class, () -> ArkivoSeekableChannelSource.of(invalid));
        assertFalse(invalid.isOpen());
        assertEquals(1, invalid.closeCount());
    }

    /// Verifies archive opener failures close the owned channel without hiding cleanup failures.
    @Test
    void closesOwnedChannelAfterArchiveOpenFailure() throws IOException {
        TrackingSeekableByteChannel backing = new TrackingSeekableByteChannel(new byte[0]);
        IOException failure = assertThrows(
                IOException.class,
                () -> SeekableChannelSources.open(backing, source -> {
                    throw new IOException("open failed");
                })
        );
        assertEquals("open failed", failure.getMessage());
        assertFalse(backing.isOpen());
        assertEquals(1, backing.closeCount());

        TrackingSeekableByteChannel closeFailing = new TrackingSeekableByteChannel(new byte[0]);
        closeFailing.failFirstClose = true;
        IOException failureWithCleanup = assertThrows(
                IOException.class,
                () -> SeekableChannelSources.open(closeFailing, source -> {
                    throw new IOException("open failed");
                })
        );
        assertEquals("open failed", failureWithCleanup.getMessage());
        assertEquals(1, failureWithCleanup.getSuppressed().length);
        assertEquals("close failed", failureWithCleanup.getSuppressed()[0].getMessage());
        assertTrue(closeFailing.isOpen());
        closeFailing.close();
    }
    /// Reads at most one target-sized chunk from a channel.
    private static byte @Unmodifiable [] read(SeekableByteChannel channel, int size) throws IOException {
        ByteBuffer target = ByteBuffer.allocate(size);
        int count = channel.read(target);
        if (count < 0) {
            return new byte[0];
        }
        target.flip();
        byte[] result = new byte[target.remaining()];
        target.get(result);
        return result;
    }

    /// Reads all bytes from one newly opened logical view.
    private static byte @Unmodifiable [] readAll(ArkivoSeekableChannelSource source, int chunkSize) throws IOException {
        try (SeekableByteChannel channel = source.openChannel()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            while (true) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    Thread.onSpinWait();
                    continue;
                }
                output.write(buffer.array(), 0, read);
            }
        }
    }

    /// Returns a completed future value while preserving its original failure type.
    private static byte @Unmodifiable [] get(Future<byte[]> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    /// Provides an intentionally unsynchronized in-memory seekable channel with lifecycle instrumentation.
    @NotNullByDefault
    private static final class TrackingSeekableByteChannel implements SeekableByteChannel {
        /// Immutable channel content.
        private final byte @Unmodifiable [] content;

        /// Current physical position.
        private long position;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Whether the first close call fails.
        private boolean failFirstClose;

        /// Whether querying the position fails.
        private boolean failPositionQuery;

        /// Number of close attempts.
        private int closeCount;

        /// Number of physical operations currently active.
        private int activeAccess;

        /// Largest number of concurrent physical operations observed.
        private int maximumConcurrentAccess;

        /// Creates a channel over copied bytes.
        private TrackingSeekableByteChannel(byte[] content) {
            this.content = content.clone();
        }

        /// Reads from the current physical position.
        @Override
        public int read(ByteBuffer target) throws IOException {
            beginAccess();
            try {
                ensureOpen();
                if (!target.hasRemaining()) {
                    return 0;
                }
                if (position >= content.length) {
                    return -1;
                }
                int count = (int) Math.min((long) target.remaining(), content.length - position);
                target.put(content, (int) position, count);
                position += count;
                return count;
            } finally {
                endAccess();
            }
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns the current physical position.
        @Override
        public long position() throws IOException {
            beginAccess();
            try {
                ensureOpen();
                if (failPositionQuery) {
                    throw new IOException("position failed");
                }
                return position;
            } finally {
                endAccess();
            }
        }

        /// Changes the current physical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            beginAccess();
            try {
                ensureOpen();
                if (newPosition < 0L) {
                    throw new IllegalArgumentException("newPosition must not be negative");
                }
                position = newPosition;
                return this;
            } finally {
                endAccess();
            }
        }

        /// Returns the immutable physical size.
        @Override
        public long size() throws IOException {
            beginAccess();
            try {
                ensureOpen();
                return content.length;
            } finally {
                endAccess();
            }
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel, optionally failing its first attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns the maximum concurrent physical operation count.
        private int maximumConcurrentAccess() {
            return maximumConcurrentAccess;
        }

        /// Records the start of one intentionally unsynchronized physical operation.
        private void beginAccess() {
            activeAccess++;
            maximumConcurrentAccess = Math.max(maximumConcurrentAccess, activeAccess);
            Thread.yield();
        }

        /// Records the end of one physical operation.
        private void endAccess() {
            activeAccess--;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
