// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies progress-safe and close-retryable archive stream/channel adaptation.
@NotNullByDefault
final class StreamChannelAdaptersTest {
    /// Verifies heap and direct targets receive input bytes with exact position advancement.
    @Test
    void adaptsInputStreamsToChannels() throws IOException {
        try (ReadableByteChannel heapChannel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1, 2})
        )) {
            ByteBuffer target = ByteBuffer.allocate(4);
            assertEquals(2, heapChannel.read(target));
            assertEquals(2, target.position());
            assertEquals(-1, heapChannel.read(target));
            assertEquals(2, target.position());
        }

        try (ReadableByteChannel directChannel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{3, 4})
        )) {
            ByteBuffer target = ByteBuffer.allocateDirect(4);
            assertEquals(2, directChannel.read(target));
            assertEquals(2, target.position());
            assertEquals(-1, directChannel.read(target));
            assertEquals(2, target.position());
        }
    }

    /// Verifies input-stream zero progress is returned without internal spinning.
    @Test
    void preservesZeroProgressFromInputStreams() throws IOException {
        try (ReadableByteChannel channel =
                     StreamChannelAdapters.readableChannel(new ZeroProgressInputStream())) {
            assertEquals(0, channel.read(ByteBuffer.allocate(1)));
            assertEquals(0, channel.read(ByteBuffer.allocateDirect(1)));
        }
    }

    /// Verifies channel adapters validate nulls, accept empty buffers, and reject access after closure.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesChannelArgumentsAndClosedState() throws IOException {
        ReadableByteChannel input = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1})
        );
        assertThrows(NullPointerException.class, () -> input.read(null));
        assertEquals(0, input.read(ByteBuffer.allocate(0)));
        input.close();
        assertThrows(ClosedChannelException.class, () -> input.read(ByteBuffer.allocate(1)));

        WritableByteChannel output = StreamChannelAdapters.writableChannel(new ByteArrayOutputStream());
        assertThrows(NullPointerException.class, () -> output.write(null));
        output.close();
        assertThrows(ClosedChannelException.class, () -> output.write(ByteBuffer.allocate(1)));
    }

    /// Verifies a read-only target is rejected before the input stream is consumed.
    @Test
    void rejectsReadOnlyChannelTargetBeforeReading() throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1});
        try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(source)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
            );
            assertEquals(1, source.available());
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

    /// Verifies channel-backed streams implement single-byte, empty-request, and closed-state behavior.
    @Test
    void adaptsSingleByteStreamOperations() throws IOException {
        ReadableByteChannel source = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{(byte) 0xfe})
        );
        InputStream input = StreamChannelAdapters.inputStream(source);
        assertEquals(0, input.read(new byte[0], 0, 0));
        assertEquals(0xfe, input.read());
        assertEquals(-1, input.read());
        input.close();
        assertFalse(source.isOpen());
        assertEquals("Stream closed", assertThrows(IOException.class, input::read).getMessage());
        assertEquals(
                "Stream closed",
                assertThrows(IOException.class, () -> input.read(new byte[1])).getMessage()
        );
        assertEquals("Stream closed", assertThrows(IOException.class, () -> input.skip(1L)).getMessage());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel target = StreamChannelAdapters.writableChannel(bytes);
        OutputStream output = StreamChannelAdapters.outputStream(target);
        output.write(0x101);
        output.write(new byte[0]);
        output.close();
        assertFalse(target.isOpen());
        assertArrayEquals(new byte[]{1}, bytes.toByteArray());
        assertEquals("Stream closed", assertThrows(IOException.class, () -> output.write(2)).getMessage());
        assertEquals(
                "Stream closed",
                assertThrows(IOException.class, () -> output.write(new byte[]{2})).getMessage()
        );
    }

    /// Verifies skip uses channel positioning when available and bounded reads otherwise.
    @Test
    void skipsSeekableAndStreamingSources() throws IOException {
        ReadOnlyByteArrayChannel seekableSource = new ReadOnlyByteArrayChannel(new byte[]{1, 2, 3, 4});
        try (InputStream input = StreamChannelAdapters.inputStream(seekableSource)) {
            assertEquals(0L, input.skip(0L));
            assertEquals(0L, input.skip(-1L));
            assertEquals(1, input.read());
            assertEquals(2L, input.skip(2L));
            assertEquals(4, input.read());
            assertEquals(0L, input.skip(Long.MAX_VALUE));

            seekableSource.position(10L);
            assertEquals(0L, input.skip(3L));
            assertEquals(10L, seekableSource.position());
        }

        ReadableByteChannel streamingSource = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{5, 6, 7, 8})
        );
        try (InputStream input = StreamChannelAdapters.inputStream(streamingSource)) {
            assertEquals(2L, input.skip(2L));
            assertEquals(7, input.read());
            assertEquals(1L, input.skip(Long.MAX_VALUE));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies streaming skips return each successful fragment before a later physical read can fail.
    @Test
    void streamingSkipPreservesProgressBeforeFailure() throws IOException {
        for (Throwable failure : List.of(new IOException("read failed"),
                new IllegalStateException("read failed"), new AssertionError("read failed"))) {
            FragmentedFailingReadableChannel source = new FragmentedFailingReadableChannel(failure);
            try (InputStream input = StreamChannelAdapters.inputStream(source)) {
                assertEquals(0, input.skip(0));
                assertEquals(0, input.skip(-1));
                assertEquals(0, source.readCalls);
                assertEquals(1, input.skip(Long.MAX_VALUE));
                assertEquals(1, source.readCalls);
                assertSame(failure, assertThrows(failure.getClass(), () -> input.skip(Long.MAX_VALUE)));
                assertEquals(2, source.readCalls);
                assertEquals(1, input.skip(Long.MAX_VALUE));
                assertEquals(3, input.read());
                assertEquals(0, input.skip(Long.MAX_VALUE));
                assertEquals(-1, input.read());
            }
            assertFalse(source.isOpen());
        }
    }

    /// Verifies every adapter retries a failed endpoint close and then becomes idempotent.
    @Test
    void retriesFailedEndpointClosure() throws IOException {
        FailingCloseInputStream inputStream = new FailingCloseInputStream();
        ReadableByteChannel inputChannel = StreamChannelAdapters.readableChannel(inputStream);
        assertThrows(IOException.class, inputChannel::close);
        assertTrue(inputChannel.isOpen());
        inputChannel.close();
        inputChannel.close();
        assertFalse(inputChannel.isOpen());
        assertEquals(2, inputStream.closeCount());

        FailingCloseOutputStream outputStream = new FailingCloseOutputStream();
        WritableByteChannel outputChannel = StreamChannelAdapters.writableChannel(outputStream);
        assertThrows(IOException.class, outputChannel::close);
        assertTrue(outputChannel.isOpen());
        outputChannel.close();
        outputChannel.close();
        assertFalse(outputChannel.isOpen());
        assertEquals(2, outputStream.closeCount());

        FailingCloseReadableChannel readableChannel = new FailingCloseReadableChannel();
        InputStream input = StreamChannelAdapters.inputStream(readableChannel);
        assertThrows(IOException.class, input::close);
        input.close();
        input.close();
        assertEquals(2, readableChannel.closeCount());

        FailingCloseWritableChannel writableChannel = new FailingCloseWritableChannel();
        OutputStream output = StreamChannelAdapters.outputStream(writableChannel);
        assertThrows(IOException.class, output::close);
        output.close();
        output.close();
        assertEquals(2, writableChannel.closeCount());
    }

    /// Supplies three bytes one at a time and fails its second physical read.
    @NotNullByDefault
    private static final class FragmentedFailingReadableChannel implements ReadableByteChannel {
        /// Failure emitted by the second read attempt.
        private final Throwable failure;

        /// Number of physical read attempts.
        private int readCalls;

        /// Next unsigned byte value to return.
        private int nextValue = 1;

        /// Whether this source remains open.
        private boolean open = true;

        /// Creates a fragmented source with the selected failure.
        private FragmentedFailingReadableChannel(Throwable failure) {
            this.failure = failure;
        }

        /// Returns one byte or reports the injected failure without consuming that byte.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            readCalls++;
            if (readCalls == 2) {
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            if (nextValue > 3) {
                return -1;
            }
            target.put((byte) nextValue++);
            return 1;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Counts target write attempts.
    @NotNullByDefault
    private static final class CountingOutputStream extends ByteArrayOutputStream {
        /// Number of write attempts.
        private int writeCount;

        /// Writes and counts one byte range.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            writeCount++;
            super.write(bytes, offset, length);
        }

        /// Returns the number of write attempts.
        private int writeCount() {
            return writeCount;
        }
    }

    /// Returns zero progress for every stream read.
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
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
