// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.internal.StreamCodecAdapters;

import org.jetbrains.annotations.NotNullByDefault;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Verifies stream codec factory failures apply endpoint ownership after validation.
    @Test
    void appliesOwnershipAfterCodecFactoryFailure() {
        WritableByteChannel ownedTarget = Channels.newChannel(new ByteArrayOutputStream());
        IOException encoderFailure = assertThrows(
                IOException.class,
                () -> StreamCodecAdapters.newWritableByteChannel(
                        ownedTarget,
                        ResourceOwnership.OWNED,
                        output -> {
                            throw new IOException("encoder setup failed");
                        }
                )
        );
        assertEquals("encoder setup failed", encoderFailure.getMessage());
        assertFalse(ownedTarget.isOpen());

        ReadableByteChannel retainedSource = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        IOException decoderFailure = assertThrows(
                IOException.class,
                () -> StreamCodecAdapters.newReadableByteChannel(
                        retainedSource,
                        ResourceOwnership.BORROWED,
                        input -> {
                            throw new IOException("decoder setup failed");
                        }
                )
        );
        assertEquals("decoder setup failed", decoderFailure.getMessage());
        assertTrue(retainedSource.isOpen());
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

    /// Records calls made through a stream view of a compression encoder.
    @NotNullByDefault
    private static final class TrackingCompressingWritableByteChannel implements CompressingWritableByteChannel.Flushable {
        /// The number of accepted source bytes.
        private long inputBytes;

        /// The number of flush calls.
        private int flushCount;

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
        public void flush() {
            flushCount++;
        }

        /// Finishes this encoder.
        @Override
        public void finish() {
            open = false;
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
