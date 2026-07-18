// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Creates progress-safe, close-retryable adapters between streams and channels.
@NotNullByDefault
public final class StreamChannelAdapters {
    /// The largest temporary transfer used for a non-array buffer.
    private static final int TRANSFER_SIZE = 8192;

    /// Creates no instances.
    private StreamChannelAdapters() {
    }

    /// Returns a channel that owns and reads directly from the input stream.
    ///
    /// @param source the input stream whose ownership is transferred to the channel
    /// @return a readable channel preserving close-retry behavior
    public static ReadableByteChannel readableChannel(InputStream source) {
        return new InputStreamChannel(Objects.requireNonNull(source, "source"));
    }

    /// Returns a channel that owns and writes directly to the output stream.
    ///
    /// @param target the output stream whose ownership is transferred to the channel
    /// @return a writable channel preserving close-retry behavior
    public static WritableByteChannel writableChannel(OutputStream target) {
        return new OutputStreamChannel(Objects.requireNonNull(target, "target"));
    }

    /// Returns an input stream that owns the readable channel.
    ///
    /// @param source the channel whose ownership is transferred to the stream
    /// @return an input stream that rejects zero-progress nonempty reads
    public static InputStream inputStream(ReadableByteChannel source) {
        return new ChannelInputStream(Objects.requireNonNull(source, "source"));
    }

    /// Returns an output stream that owns the writable channel.
    ///
    /// @param target the channel whose ownership is transferred to the stream
    /// @return an output stream that rejects zero-progress writes and forwards codec flushes
    public static OutputStream outputStream(WritableByteChannel target) {
        return new ChannelOutputStream(Objects.requireNonNull(target, "target"));
    }

    /// Adapts one input stream to a readable channel.
    @NotNullByDefault
    private static final class InputStreamChannel implements ReadableByteChannel {
        /// The backing input stream.
        private final InputStream source;

        /// The reusable transfer buffer for direct targets.
        private final byte[] transferBuffer = new byte[TRANSFER_SIZE];

        /// Whether this adapter remains open.
        private boolean open = true;

        /// Creates an input-stream channel.
        private InputStreamChannel(InputStream source) {
            this.source = source;
        }

        /// Performs one stream read while preserving a zero-progress result.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            if (target.isReadOnly()) {
                throw new IllegalArgumentException("target must be writable");
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (target.hasArray()) {
                int position = target.position();
                int read = source.read(
                        target.array(),
                        target.arrayOffset() + position,
                        target.remaining()
                );
                if (read > 0) {
                    target.position(position + read);
                }
                return read;
            }

            int requested = Math.min(target.remaining(), transferBuffer.length);
            int read = source.read(transferBuffer, 0, requested);
            if (read > 0) {
                target.put(transferBuffer, 0, read);
            }
            return read;
        }

        /// Returns whether this adapter remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the stream and commits closure only after success.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            source.close();
            open = false;
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Adapts one output stream to a writable channel.
    @NotNullByDefault
    private static final class OutputStreamChannel implements WritableByteChannel {
        /// The backing output stream.
        private final OutputStream target;

        /// The reusable transfer buffer for direct sources.
        private final byte[] transferBuffer = new byte[TRANSFER_SIZE];

        /// Whether this adapter remains open.
        private boolean open = true;

        /// Creates an output-stream channel.
        private OutputStreamChannel(OutputStream target) {
            this.target = target;
        }

        /// Writes all remaining source bytes and advances only successfully written chunks.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            if (!source.hasRemaining()) {
                return 0;
            }
            int start = source.position();
            if (source.hasArray()) {
                int count = source.remaining();
                target.write(
                        source.array(),
                        source.arrayOffset() + source.position(),
                        count
                );
                source.position(source.limit());
                return count;
            }

            while (source.hasRemaining()) {
                int count = Math.min(source.remaining(), transferBuffer.length);
                ByteBuffer chunk = source.duplicate();
                chunk.limit(chunk.position() + count);
                chunk.get(transferBuffer, 0, count);
                target.write(transferBuffer, 0, count);
                source.position(source.position() + count);
            }
            return source.position() - start;
        }

        /// Returns whether this adapter remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the stream and commits closure only after success.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            target.close();
            open = false;
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Adapts one readable channel to an input stream.
    @NotNullByDefault
    private static final class ChannelInputStream extends InputStream {
        /// The backing readable channel.
        private final ReadableByteChannel source;

        /// The reusable single-byte target.
        private final byte[] singleByte = new byte[1];

        /// Whether this adapter remains open.
        private boolean open = true;

        /// Creates a readable-channel input stream.
        private ChannelInputStream(ReadableByteChannel source) {
            this.source = source;
        }

        /// Reads one byte from the channel.
        @Override
        public int read() throws IOException {
            int read = read(singleByte, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Performs one channel read and rejects zero progress for a nonempty request.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            int read = source.read(ByteBuffer.wrap(bytes, offset, length));
            if (read == 0) {
                throw new IOException("Readable channel made no progress");
            }
            return read;
        }

        /// Closes the channel and commits closure only after success.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            source.close();
            open = false;
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("Stream closed");
            }
        }
    }

    /// Adapts one writable channel to an output stream.
    @NotNullByDefault
    private static final class ChannelOutputStream extends OutputStream {
        /// The backing writable channel.
        private final WritableByteChannel target;

        /// The reusable single-byte source.
        private final byte[] singleByte = new byte[1];

        /// Whether this adapter remains open.
        private boolean open = true;

        /// Creates a writable-channel output stream.
        private ChannelOutputStream(WritableByteChannel target) {
            this.target = target;
        }

        /// Writes one byte to the channel.
        @Override
        public void write(int value) throws IOException {
            singleByte[0] = (byte) value;
            write(singleByte, 0, 1);
        }

        /// Writes all requested bytes and rejects a zero-progress channel.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            ByteBuffer source = ByteBuffer.wrap(bytes, offset, length);
            while (source.hasRemaining()) {
                if (target.write(source) == 0) {
                    throw new IOException("Writable channel made no progress");
                }
            }
        }

        /// Flushes a codec encoder when the backing channel exposes compression flush semantics.
        @Override
        public void flush() throws IOException {
            ensureOpen();
            if (target instanceof CompressingWritableByteChannel.Flushable encoder) {
                encoder.flush();
            }
        }

        /// Closes the channel and commits closure only after success.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            target.close();
            open = false;
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("Stream closed");
            }
        }
    }
}
