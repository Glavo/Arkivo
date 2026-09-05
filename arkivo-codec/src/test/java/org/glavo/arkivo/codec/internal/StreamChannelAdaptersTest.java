// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies progress-safe and close-retryable stream/channel adaptation.
@NotNullByDefault
final class StreamChannelAdaptersTest {
    /// Verifies input streams read into heap and direct buffers without hiding zero progress.
    @Test
    void adaptsInputStreamsToChannels() throws IOException {
        try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        )) {
            ByteBuffer heap = ByteBuffer.allocate(2);
            ByteBuffer direct = ByteBuffer.allocateDirect(2);
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertEquals(2, channel.read(heap));
            assertEquals(2, channel.read(direct));
            heap.flip();
            direct.flip();
            assertEquals(1, heap.get());
            assertEquals(2, heap.get());
            assertEquals(3, direct.get());
            assertEquals(4, direct.get());
        }

        CountingInputStream source = new CountingInputStream(new byte[]{9});
        try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(source)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
            );
            assertEquals(0, source.readCount());
        }

        try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(
                new ZeroProgressInputStream()
        )) {
            assertEquals(0, channel.read(ByteBuffer.allocate(1)));
        }
    }

    /// Verifies output streams receive heap and direct buffers with exact source advancement.
    @Test
    void adaptsOutputStreamsToChannels() throws IOException {
        CountingOutputStream output = new CountingOutputStream();
        try (WritableByteChannel channel = StreamChannelAdapters.writableChannel(output)) {
            assertEquals(0, channel.write(ByteBuffer.allocate(0)));
            assertEquals(0, output.writeCount());

            ByteBuffer heap = ByteBuffer.wrap(new byte[]{1, 2});
            ByteBuffer direct = ByteBuffer.allocateDirect(2).put(new byte[]{3, 4}).flip();
            assertEquals(2, channel.write(heap));
            assertEquals(2, channel.write(direct));
            assertEquals(heap.limit(), heap.position());
            assertEquals(direct.limit(), direct.position());
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4}, output.toByteArray());
    }

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

    /// Verifies channel-backed streams reject zero progress instead of spinning.
    @Test
    void rejectsZeroProgressChannels() throws IOException {
        try (InputStream input = StreamChannelAdapters.inputStream(new ZeroProgressReadableChannel())) {
            IOException failure = assertThrows(IOException.class, () -> input.read(new byte[1]));
            assertEquals("Readable channel made no progress", failure.getMessage());
        }

        try (OutputStream output = StreamChannelAdapters.outputStream(new ZeroProgressWritableChannel())) {
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[]{1}));
            assertEquals("Writable channel made no progress", failure.getMessage());
        }
    }

    /// Verifies output-stream flush reaches a channel-first compression encoder.
    @Test
    void flushesCompressingWritableByteChannelThroughOutputStream() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        try (OutputStream output = StreamChannelAdapters.outputStream(encoder)) {
            output.write(new byte[]{1, 2, 3});
            output.flush();
            assertEquals(1, encoder.flushCount());
            assertEquals(3L, encoder.inputBytes());
            assertTrue(encoder.isOpen());
        }
        assertFalse(encoder.isOpen());
    }

    /// Verifies an explicit flush reaches a borrowed downstream stream and remains retryable after failure.
    @Test
    void flushesBorrowedDownstreamAndRetainsStreamAfterFailure() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
        OutputStream output = StreamChannelAdapters.outputStream(
                encoder,
                downstream,
                ResourceOwnership.BORROWED
        );

        IOException failure = assertThrows(IOException.class, output::flush);
        assertEquals("flush failed", failure.getMessage());
        assertEquals(1, encoder.flushCount());
        assertEquals(1, downstream.flushCount());

        output.flush();
        assertEquals(2, encoder.flushCount());
        assertEquals(2, downstream.flushCount());
        output.close();
        assertEquals(1, encoder.finishCount());
        assertEquals(3, downstream.flushCount());
    }

    /// Verifies codec flush failures retain their identity while downstream flush is attempted and remains retryable.
    @Test
    void composesCheckedAndUncheckedFlushFailures() throws IOException {
        for (ResourceOwnership ownership : ResourceOwnership.values()) {
            for (boolean sharedFailure : new boolean[]{false, true}) {
                for (Throwable primary : List.of(new IOException("encoder flush failed"),
                        new IllegalStateException("encoder flush failed"), new AssertionError("encoder flush failed"))) {
                    TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
                    encoder.flushFailure = primary;
                    FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
                    if (sharedFailure) {
                        downstream.flushFailure = primary;
                    }
                    Throwable secondary = downstream.flushFailure;
                    OutputStream output = StreamChannelAdapters.outputStream(encoder, downstream, ownership);
                    assertSame(primary, assertThrows(primary.getClass(), output::flush));
                    assertEquals(1, encoder.flushCount());
                    assertEquals(1, downstream.flushCount());
                    assertEquals(sharedFailure ? 0 : 1, primary.getSuppressed().length);
                    if (!sharedFailure) {
                        assertSame(secondary, primary.getSuppressed()[0]);
                    }
                    output.write(7);
                    output.flush();
                    assertEquals(1, encoder.inputBytes());
                    assertEquals(2, encoder.flushCount());
                    assertEquals(2, downstream.flushCount());
                    output.close();
                    output.close();
                    assertEquals(1, encoder.finishCount());
                    assertEquals(ownership == ResourceOwnership.BORROWED ? 3 : 2, downstream.flushCount());
                }
            }
        }
    }

    /// Verifies every adapter retries a failed endpoint close.
    @Test
    void retriesFailedEndpointClosure() throws IOException {
        FailingCloseInputStream inputStream = new FailingCloseInputStream();
        ReadableByteChannel inputChannel = StreamChannelAdapters.readableChannel(inputStream);
        assertThrows(IOException.class, inputChannel::close);
        assertTrue(inputChannel.isOpen());
        inputChannel.close();
        assertFalse(inputChannel.isOpen());
        assertEquals(2, inputStream.closeCount());

        FailingCloseOutputStream outputStream = new FailingCloseOutputStream();
        WritableByteChannel outputChannel = StreamChannelAdapters.writableChannel(outputStream);
        assertThrows(IOException.class, outputChannel::close);
        assertTrue(outputChannel.isOpen());
        outputChannel.close();
        assertFalse(outputChannel.isOpen());
        assertEquals(2, outputStream.closeCount());

        FailingCloseReadableChannel readableChannel = new FailingCloseReadableChannel();
        InputStream input = StreamChannelAdapters.inputStream(readableChannel);
        assertThrows(IOException.class, input::close);
        input.close();
        assertEquals(2, readableChannel.closeCount());

        FailingCloseWritableChannel writableChannel = new FailingCloseWritableChannel();
        OutputStream output = StreamChannelAdapters.outputStream(writableChannel);
        assertThrows(IOException.class, output::close);
        output.close();
        assertEquals(2, writableChannel.closeCount());
    }

    /// Verifies successful closure is idempotent and every adapter rejects subsequent I/O.
    @Test
    void rejectsOperationsAfterSuccessfulClosure() throws IOException {
        ReadableByteChannel inputChannel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1})
        );
        inputChannel.close();
        inputChannel.close();
        assertFalse(inputChannel.isOpen());
        assertThrows(ClosedChannelException.class, () -> inputChannel.read(ByteBuffer.allocate(1)));

        WritableByteChannel outputChannel = StreamChannelAdapters.writableChannel(new ByteArrayOutputStream());
        outputChannel.close();
        outputChannel.close();
        assertFalse(outputChannel.isOpen());
        assertThrows(ClosedChannelException.class, () -> outputChannel.write(ByteBuffer.wrap(new byte[]{1})));

        InputStream input = StreamChannelAdapters.inputStream(new ZeroProgressReadableChannel());
        assertEquals(0, input.read(new byte[0]));
        input.close();
        input.close();
        IOException readFailure = assertThrows(IOException.class, input::read);
        assertEquals("Stream closed", readFailure.getMessage());

        OutputStream output = StreamChannelAdapters.outputStream(new ZeroProgressWritableChannel());
        output.write(new byte[0]);
        output.close();
        output.close();
        IOException writeFailure = assertThrows(IOException.class, () -> output.write(1));
        assertEquals("Stream closed", writeFailure.getMessage());
    }

    /// Verifies close retains checked or unchecked target failures without suppressing an exception onto itself.
    @Test
    void composesTargetCloseAndBorrowedDownstreamFlushFailures() throws IOException {
        for (boolean sharedFailure : new boolean[]{false, true}) {
            for (Throwable primary : List.of(new IOException("close failed"),
                    new IllegalStateException("close failed"), new AssertionError("close failed"))) {
                FailingCloseWritableChannel target = new FailingCloseWritableChannel();
                target.closeFailure = primary;
                FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
                if (sharedFailure) {
                    downstream.flushFailure = primary;
                }
                Throwable secondary = downstream.flushFailure;
                OutputStream output = StreamChannelAdapters.outputStream(target, downstream, ResourceOwnership.BORROWED);

                assertSame(primary, assertThrows(primary.getClass(), output::close));
                assertEquals(sharedFailure ? 0 : 1, primary.getSuppressed().length);
                if (!sharedFailure) {
                    assertSame(secondary, primary.getSuppressed()[0]);
                }
                assertEquals(1, target.closeCount());
                assertEquals(1, downstream.flushCount());

                output.close();
                assertEquals(2, target.closeCount());
                assertEquals(2, downstream.flushCount());
                output.close();
                assertEquals(2, target.closeCount());
                assertEquals(2, downstream.flushCount());
                assertThrows(IOException.class, () -> output.write(1));
                assertThrows(IOException.class, output::flush);
            }
        }
    }

    /// Verifies close retries only a failed downstream flush after codec finalization has completed.
    @Test
    void retriesBorrowedDownstreamFlushWithoutRefinalizingCodec() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
        OutputStream output = StreamChannelAdapters.outputStream(
                encoder,
                downstream,
                ResourceOwnership.BORROWED
        );
        output.write(new byte[]{1, 2, 3});

        IOException failure = assertThrows(IOException.class, output::close);
        assertEquals("flush failed", failure.getMessage());
        assertEquals(1, encoder.finishCount());
        assertEquals(1, downstream.flushCount());

        output.close();
        assertEquals(1, encoder.finishCount());
        assertEquals(2, downstream.flushCount());
        output.close();
        assertEquals(2, downstream.flushCount());
    }

    /// Records calls made through a stream view of a compression encoder.
    @NotNullByDefault
    private static final class TrackingCompressingWritableByteChannel implements CompressingWritableByteChannel.Flushable {
        /// The number of accepted source bytes.
        private long inputBytes;

        /// The number of flush calls.
        private int flushCount;

        /// Failure thrown once by the next compression flush, or `null` when disabled.
        private @Nullable Throwable flushFailure;

        /// Number of encoder finalizations.
        private int finishCount;

        /// Whether this encoder remains open.
        private boolean open = true;

        /// Consumes all source bytes.
        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
            inputBytes += count;
            return count;
        }

        /// Records one compression flush.
        @Override
        public void flush() throws IOException {
            flushCount++;
            @Nullable Throwable failure = flushFailure;
            flushFailure = null;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        /// Finishes this encoder.
        @Override
        public void finish() {
            if (open) {
                finishCount++;
                open = false;
            }
        }

        /// Returns the number of accepted source bytes.
        @Override
        public long inputBytes() {
            return inputBytes;
        }

        /// Returns zero because this test encoder emits no bytes.
        @Override
        public long outputBytes() {
            return 0L;
        }

        /// Returns whether this encoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Finishes this encoder.
        @Override
        public void close() {
            finish();
        }

        /// Returns the number of recorded flush calls.
        private int flushCount() {
            return flushCount;
        }

        /// Returns the number of encoder finalizations.
        private int finishCount() {
            return finishCount;
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

    /// Returns zero progress for every read.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends InputStream {
        /// Returns zero progress.
        @Override
        public int read() {
            return 0;
        }

        /// Returns zero progress.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return 0;
        }
    }

    /// Returns zero progress for every channel read.
    @NotNullByDefault
    private static final class ZeroProgressReadableChannel implements ReadableByteChannel {
        /// Returns zero progress.
        @Override
        public int read(ByteBuffer target) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Closes this channel.
        @Override
        public void close() {
        }
    }

    /// Returns zero progress for every channel write.
    @NotNullByDefault
    private static final class ZeroProgressWritableChannel implements WritableByteChannel {
        /// Returns zero progress.
        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Closes this channel.
        @Override
        public void close() {
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends InputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Reports end of input.
        @Override
        public int read() {
            return -1;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Accepts one byte.
        @Override
        public void write(int value) {
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first flush while accepting all writes.
    @NotNullByDefault
    private static final class FailingOnceFlushOutputStream extends OutputStream {
        /// Failure thrown by the first flush attempt.
        private Throwable flushFailure = new IOException("flush failed");

        /// Number of flush attempts.
        private int flushCount;

        /// Accepts one byte.
        @Override
        public void write(int value) {
        }

        /// Fails the first flush attempt.
        @Override
        public void flush() throws IOException {
            flushCount++;
            if (flushCount == 1) {
                if (flushFailure instanceof IOException exception) {
                    throw exception;
                }
                if (flushFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) flushFailure;
            }
        }

        /// Returns the number of flush attempts.
        private int flushCount() {
            return flushCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Number of close attempts.
        private int closeCount;

        /// Reports end of input.
        @Override
        public int read(ByteBuffer target) {
            return -1;
        }

        /// Returns whether closure has not succeeded.
        @Override
        public boolean isOpen() {
            return closeCount < 2;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Failure thrown by the first close attempt.
        private Throwable closeFailure = new IOException("close failed");

        /// Number of close attempts.
        private int closeCount;

        /// Consumes all source bytes.
        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether closure has not succeeded.
        @Override
        public boolean isOpen() {
            return closeCount < 2;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                if (closeFailure instanceof IOException exception) {
                    throw exception;
                }
                if (closeFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) closeFailure;
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
