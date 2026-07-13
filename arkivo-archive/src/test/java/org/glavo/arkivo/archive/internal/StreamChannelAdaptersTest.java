// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies progress-safe and close-retryable archive stream/channel adaptation.
@NotNullByDefault
final class StreamChannelAdaptersTest {
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
