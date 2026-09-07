// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stream/channel buffer windows, transfer boundaries, and partial write failures.
@NotNullByDefault
final class StreamChannelBufferTest {
    /// Verifies sliced targets preserve guards, marks, and limits across temporary-buffer boundaries and EOF.
    @Test
    void readsSlicedTargetsAcrossTransferBoundaries() throws IOException {
        for (int size : new int[]{8_191, 8_192, 8_193, 16_385}) {
            byte[] expected = new byte[size];
            for (int index = 0; index < size; index++) {
                expected[index] = (byte) (index * 31 + index / 251);
            }
            for (boolean direct : new boolean[]{false, true}) {
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(size + 9) : ByteBuffer.allocate(size + 9);
                byte[] initial = new byte[storage.capacity()];
                Arrays.fill(initial, (byte) 0x5a);
                storage.put(initial).position(3).limit(size + 7);
                ByteBuffer target = storage.slice().position(2).limit(size + 2).mark();
                CountingInputStream source = new CountingInputStream(expected);
                try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(source)) {
                    int total = 0;
                    while (target.hasRemaining()) {
                        int position = target.position();
                        int count = channel.read(target);
                        assertTrue(count > 0);
                        assertEquals(position + count, target.position());
                        assertEquals(size + 2, target.limit());
                        total += count;
                    }
                    assertEquals(size, total);
                    assertEquals(3, storage.position());
                    assertEquals(size + 7, storage.limit());
                    int reads = source.readCount();
                    assertEquals(0, channel.read(target));
                    assertEquals(reads, source.readCount());
                    assertEquals(2, target.reset().position());
                    assertEquals(-1, channel.read(target));
                    assertEquals(2, target.position());
                    System.arraycopy(expected, 0, initial, 5, size);
                    byte[] actual = new byte[initial.length];
                    storage.clear().get(actual);
                    assertArrayEquals(initial, actual);
                }
            }
        }
    }

    /// Verifies array offsets and read-only or direct sources across multiple temporary-buffer writes.
    @Test
    void writesSlicedSourcesAcrossTransferBoundaries() throws IOException {
        for (int size : new int[]{8_191, 8_192, 8_193, 16_385}) {
            byte[] bytes = new byte[size + 9];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (index * 17 + index / 127);
            }
            for (boolean direct : new boolean[]{false, true}) {
                for (boolean readOnly : new boolean[]{false, true}) {
                    ByteBuffer storage = direct ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
                    storage.put(bytes).position(3).limit(size + 7);
                    ByteBuffer slice = storage.slice();
                    ByteBuffer source = (readOnly ? slice.asReadOnlyBuffer() : slice).position(2).limit(size + 2).mark();
                    CountingOutputStream target = new CountingOutputStream();
                    try (WritableByteChannel channel = StreamChannelAdapters.writableChannel(target)) {
                        assertEquals(size, channel.write(source));
                        assertEquals(size + 2, source.position());
                        assertEquals(size + 2, source.limit());
                        assertEquals(3, storage.position());
                        assertEquals(size + 7, storage.limit());
                        int writes = target.writeCount();
                        assertEquals(0, channel.write(source));
                        assertEquals(writes, target.writeCount());
                        assertEquals(2, source.reset().position());
                        assertArrayEquals(Arrays.copyOfRange(bytes, 5, size + 5), target.toByteArray());
                        byte[] actual = new byte[bytes.length];
                        storage.clear().get(actual);
                        assertArrayEquals(bytes, actual);
                    }
                }
            }
        }
    }

    /// Verifies failed stream writes advance the source only for fully completed transfer chunks.
    @Test
    void failedWritesPreserveCompletedChunkProgress() throws IOException {
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean readOnly : new boolean[]{false, true}) {
                for (Throwable failure : List.of(new IOException("partial write failed"),
                        new IllegalStateException("partial write failed"), new AssertionError("partial write failed"))) {
                    byte[] expected = new byte[16_400];
                    for (int index = 0; index < expected.length; index++) {
                        expected[index] = (byte) (index * 31 + index / 251);
                    }
                    ByteBuffer storage = direct ? ByteBuffer.allocateDirect(expected.length) : ByteBuffer.allocate(expected.length);
                    storage.put(expected).position(3).limit(expected.length - 3);
                    ByteBuffer source = (readOnly ? storage.asReadOnlyBuffer() : storage.duplicate()).mark();
                    CountingOutputStream target = new CountingOutputStream();
                    target.writeFailure = failure;
                    target.failingWrite = source.hasArray() ? 1 : 2;
                    try (WritableByteChannel channel = StreamChannelAdapters.writableChannel(target)) {
                        assertSame(failure, assertThrows(failure.getClass(), () -> channel.write(source)));
                        int completed = source.hasArray() ? 0 : 8_192;
                        assertEquals(3 + completed, source.position());
                        assertEquals(expected.length - 3, source.limit());
                        assertEquals(3, source.reset().position());
                        assertEquals(3, storage.position());
                        assertEquals(target.failingWrite, target.writeCount());
                        assertArrayEquals(Arrays.copyOfRange(expected, 3, 3 + completed + 1), target.toByteArray());
                        byte[] actual = new byte[expected.length];
                        storage.clear().get(actual);
                        assertArrayEquals(expected, actual);
                    }
                }
            }
        }
    }

    /// Counts source read attempts.
    @NotNullByDefault
    private static final class CountingInputStream extends ByteArrayInputStream {
        /// Number of read attempts.
        private int readCount;

        /// Creates a counted byte-array source.
        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Reads and counts one byte range.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            readCount++;
            return super.read(bytes, offset, length);
        }

        /// Returns the number of read attempts.
        private int readCount() {
            return readCount;
        }
    }

    /// Counts target write attempts.
    @NotNullByDefault
    private static final class CountingOutputStream extends OutputStream {
        /// Collected output bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Number of write attempts.
        private int writeCount;

        /// Failure thrown once after accepting one byte on the selected bulk write.
        private @Nullable Throwable writeFailure;

        /// One-based bulk write on which to inject a failure.
        private int failingWrite = 1;

        /// Writes one byte to the collected output.
        @Override
        public void write(int value) {
            writeCount++;
            bytes.write(value);
        }

        /// Writes and counts one byte range.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            writeCount++;
            @Nullable Throwable failure = writeFailure;
            if (failure != null && writeCount == failingWrite && length > 0) {
                writeFailure = null;
                bytes.write(source[offset]);
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            bytes.write(source, offset, length);
        }

        /// Returns a snapshot of collected bytes.
        private byte[] toByteArray() {
            return bytes.toByteArray();
        }

        /// Returns the number of write attempts.
        private int writeCount() {
            return writeCount;
        }
    }
}
