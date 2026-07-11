// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts compression codec channel operations to bounded ByteBuffer operations.
@NotNullByDefault
public final class ByteBufferCodecSupport {
    /// Creates ByteBuffer codec adapters.
    private ByteBufferCodecSupport() {
    }

    /// Compresses all remaining source bytes through the codec's channel API.
    public static void compress(CompressionCodec codec, ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(codec, "codec");
        validateBuffers(source, target);

        try (ByteBufferWritableChannel output = new ByteBufferWritableChannel(target);
             WritableByteChannel compressor = codec.compressTo(output)) {
            while (source.hasRemaining()) {
                int sourcePosition = source.position();
                int written = compressor.write(source);
                if (written <= 0 || source.position() == sourcePosition) {
                    throw new IOException("Compression codec made no progress");
                }
            }
        }
    }

    /// Decompresses all remaining source bytes through the codec's channel API.
    public static void decompress(CompressionCodec codec, ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(codec, "codec");
        validateBuffers(source, target);

        try (ByteBufferReadableChannel input = new ByteBufferReadableChannel(source);
             ReadableByteChannel decompressor = codec.decompressFrom(input)) {
            while (target.hasRemaining()) {
                int targetPosition = target.position();
                int read = decompressor.read(target);
                if (read < 0) {
                    return;
                }
                if (read == 0 || target.position() == targetPosition) {
                    throw new IOException("Decompression codec made no progress");
                }
            }

            ByteBuffer overflowProbe = ByteBuffer.allocate(1);
            int read = decompressor.read(overflowProbe);
            if (read == 0) {
                throw new IOException("Decompression codec made no progress");
            }
            if (read > 0) {
                throw new BufferOverflowException();
            }
        }
    }

    /// Validates source and target buffer constraints shared by both operations.
    private static void validateBuffers(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException("source and target must be different buffers");
        }
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
    }

    /// Exposes the remaining bytes of a ByteBuffer as a readable channel.
    @NotNullByDefault
    private static final class ByteBufferReadableChannel implements ReadableByteChannel {
        /// The source buffer advanced by reads.
        private final ByteBuffer source;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a readable channel over the source buffer.
        private ByteBufferReadableChannel(ByteBuffer source) {
            this.source = source;
        }

        /// Copies source bytes into the destination buffer.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!source.hasRemaining()) {
                return -1;
            }
            if (!destination.hasRemaining()) {
                return 0;
            }

            int count = Math.min(source.remaining(), destination.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            destination.put(chunk);
            source.position(source.position() + count);
            return count;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel without changing the buffer.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Exposes the remaining capacity of a ByteBuffer as a writable channel.
    @NotNullByDefault
    private static final class ByteBufferWritableChannel implements WritableByteChannel {
        /// The target buffer advanced by writes.
        private final ByteBuffer target;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a writable channel over the target buffer.
        private ByteBufferWritableChannel(ByteBuffer target) {
            this.target = target;
        }

        /// Copies source bytes into the target buffer.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            if (!source.hasRemaining()) {
                return 0;
            }
            if (!target.hasRemaining()) {
                throw new BufferOverflowException();
            }

            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return count;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel without changing the buffer.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
