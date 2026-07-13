// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts compression codec channel operations to bounded and allocating ByteBuffer operations.
@NotNullByDefault
public final class ByteBufferCodecSupport {
    /// The minimum nonempty capacity used by allocating operations.
    private static final int MINIMUM_INITIAL_CAPACITY = 64;

    /// The largest speculative initial allocation before output size is known.
    private static final int MAXIMUM_INITIAL_CAPACITY = 1 << 20;

    /// Creates ByteBuffer codec adapters.
    private ByteBufferCodecSupport() {
    }

    /// Compresses remaining source bytes into a dynamically growing heap buffer.
    public static ByteBuffer compressAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");

        int initialCapacity = compressionInitialCapacity(codec, source.remaining());
        GrowingByteBuffer bytes = new GrowingByteBuffer(initialCapacity, Integer.MAX_VALUE);
        try (GrowingByteBufferChannel output = new GrowingByteBufferChannel(bytes);
             CompressionEncoder compressor = codec.openEncoder(
                     output,
                     options,
                     ChannelOwnership.RETAIN
             )) {
            while (source.hasRemaining()) {
                int sourcePosition = source.position();
                int written = compressor.write(source);
                if (written <= 0 || source.position() == sourcePosition) {
                    throw new IOException("Compression codec made no progress");
                }
            }
        }
        return bytes.toReadableBuffer();
    }

    /// Decompresses remaining source bytes into a dynamically growing bounded heap buffer.
    public static ByteBuffer decompressAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            long maximumOutputSize,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        if (maximumOutputSize < 0L || maximumOutputSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maximumOutputSize must be between zero and " + Integer.MAX_VALUE
            );
        }

        int maximumCapacity = (int) maximumOutputSize;
        GrowingByteBuffer bytes = new GrowingByteBuffer(
                decompressionInitialCapacity(source.remaining(), maximumCapacity),
                maximumCapacity
        );
        ByteBufferReadableChannel input = new ByteBufferReadableChannel(source);
        CompressionDecoder decompressor = openDecoder(codec, source, input, options);
        try (input; decompressor) {
            try {
                while (true) {
                    if (bytes.size() == maximumCapacity) {
                        ByteBuffer probe = ByteBuffer.allocate(1);
                        int read = decompressor.read(probe);
                        if (read < 0) {
                            return bytes.toReadableBuffer();
                        }
                        if (read == 0) {
                            throw new IOException("Decompression codec made no progress");
                        }
                        throw new DecompressionLimitException(maximumOutputSize);
                    }

                    ByteBuffer target = bytes.writableBuffer();
                    int targetPosition = target.position();
                    int read = decompressor.read(target);
                    if (read < 0) {
                        return bytes.toReadableBuffer();
                    }
                    if (read == 0 || target.position() == targetPosition) {
                        throw new IOException("Decompression codec made no progress");
                    }
                }
            } finally {
                restoreUnconsumedInput(source, decompressor);
            }
        }
    }

    /// Decompresses one frame into a dynamically growing bounded heap buffer.
    public static ByteBuffer decompressFrameAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            long maximumOutputSize,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        if (maximumOutputSize < 0L || maximumOutputSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maximumOutputSize must be between zero and " + Integer.MAX_VALUE
            );
        }

        int maximumCapacity = (int) maximumOutputSize;
        GrowingByteBuffer bytes = new GrowingByteBuffer(
                decompressionInitialCapacity(source.remaining(), maximumCapacity),
                maximumCapacity
        );
        ByteBufferReadableChannel input = new ByteBufferReadableChannel(source);
        CompressionDecoder decompressor = openDecoder(codec, source, input, options);
        try (input; decompressor) {
            try {
                while (true) {
                    if (bytes.size() == maximumCapacity) {
                        ByteBuffer probe = ByteBuffer.allocate(1);
                        CodecResult result = decompressor.decode(probe, DecodeDirective.STOP_AT_FRAME);
                        if (result.outputBytes() > 0L) {
                            throw new DecompressionLimitException(maximumOutputSize);
                        }
                        if (frameComplete(result)) {
                            return bytes.toReadableBuffer();
                        }
                        requireDecodeProgress(result);
                        continue;
                    }

                    ByteBuffer target = bytes.writableBuffer();
                    int targetPosition = target.position();
                    CodecResult result = decompressor.decode(target, DecodeDirective.STOP_AT_FRAME);
                    if (frameComplete(result)) {
                        return bytes.toReadableBuffer();
                    }
                    if (target.position() == targetPosition && result.inputBytes() == 0L) {
                        throw new IOException("Decompression codec made no progress");
                    }
                }
            } finally {
                restoreUnconsumedInput(source, decompressor);
            }
        }
    }

    /// Returns an initial compression capacity using a codec bound when available.
    private static int compressionInitialCapacity(CompressionCodec codec, int sourceSize) {
        long bound = codec.maxCompressedSize(sourceSize);
        if (bound >= 0L && bound <= Integer.MAX_VALUE) {
            return (int) bound;
        }
        return Math.min(
                MAXIMUM_INITIAL_CAPACITY,
                Math.max(MINIMUM_INITIAL_CAPACITY, sourceSize)
        );
    }

    /// Returns an initial decompression capacity within the caller's strict maximum.
    private static int decompressionInitialCapacity(int sourceSize, int maximumCapacity) {
        long suggested = Math.max(
                MINIMUM_INITIAL_CAPACITY,
                Math.min(MAXIMUM_INITIAL_CAPACITY, (long) sourceSize * 2L)
        );
        return (int) Math.min(maximumCapacity, suggested);
    }

    /// Compresses all remaining source bytes through the codec's channel API.
    public static void compress(CompressionCodec codec, ByteBuffer source, ByteBuffer target) throws IOException {
        compress(codec, source, target, CodecOptions.EMPTY);
    }

    /// Compresses all remaining source bytes through a configured encoder context.
    public static void compress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        validateBuffers(source, target);

        try (ByteBufferWritableChannel output = new ByteBufferWritableChannel(target);
             CompressionEncoder compressor = codec.openEncoder(output, options, ChannelOwnership.RETAIN)) {
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
        decompress(codec, source, target, CodecOptions.EMPTY);
    }

    /// Decompresses all remaining source bytes through a configured decoder context.
    public static void decompress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        validateBuffers(source, target);

        ByteBufferReadableChannel input = new ByteBufferReadableChannel(source);
        CompressionDecoder decompressor = openDecoder(codec, source, input, options);
        try (input; decompressor) {
            try {
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
            } finally {
                restoreUnconsumedInput(source, decompressor);
            }
        }
    }

    /// Decompresses one complete frame into a caller-provided target buffer.
    public static void decompressFrame(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        validateBuffers(source, target);

        ByteBufferReadableChannel input = new ByteBufferReadableChannel(source);
        CompressionDecoder decompressor = openDecoder(codec, source, input, options);
        try (input; decompressor) {
            try {
                while (target.hasRemaining()) {
                    int targetPosition = target.position();
                    CodecResult result = decompressor.decode(target, DecodeDirective.STOP_AT_FRAME);
                    if (frameComplete(result)) {
                        return;
                    }
                    if (target.position() == targetPosition && result.inputBytes() == 0L) {
                        throw new IOException("Decompression codec made no progress");
                    }
                }

                while (true) {
                    ByteBuffer probe = ByteBuffer.allocate(1);
                    CodecResult result = decompressor.decode(probe, DecodeDirective.STOP_AT_FRAME);
                    if (result.outputBytes() > 0L) {
                        throw new BufferOverflowException();
                    }
                    if (frameComplete(result)) {
                        return;
                    }
                    requireDecodeProgress(result);
                }
            } finally {
                restoreUnconsumedInput(source, decompressor);
            }
        }
    }

    /// Returns whether one frame-aware operation completed its frame or physical input.
    private static boolean frameComplete(CodecResult result) {
        return result.status() == CodecStatus.FRAME_FINISHED
                || result.status() == CodecStatus.END_OF_INPUT;
    }

    /// Rejects a frame-aware operation that consumed and produced no bytes.
    private static void requireDecodeProgress(CodecResult result) throws IOException {
        if (result.inputBytes() == 0L && result.outputBytes() == 0L) {
            throw new IOException("Decompression codec made no progress");
        }
    }

    /// Opens a decoder while restoring the source position if context construction fails.
    private static CompressionDecoder openDecoder(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBufferReadableChannel input,
            CodecOptions options
    ) throws IOException {
        int initialPosition = source.position();
        try {
            return codec.openDecoder(input, options, ChannelOwnership.RETAIN);
        } catch (IOException | RuntimeException | Error exception) {
            input.close();
            source.position(initialPosition);
            throw exception;
        }
    }

    /// Restores bytes read ahead but not logically consumed by the decoder.
    private static void restoreUnconsumedInput(ByteBuffer source, CompressionDecoder decoder) {
        int count = decoder.unconsumedInput().remaining();
        source.position(source.position() - count);
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

    /// Owns one dynamically growing heap buffer with a strict maximum capacity.
    @NotNullByDefault
    private static final class GrowingByteBuffer {
        /// The strict maximum capacity.
        private final int maximumCapacity;

        /// The writable output buffer.
        private ByteBuffer buffer;

        /// Creates a buffer with validated initial and maximum capacities.
        private GrowingByteBuffer(int initialCapacity, int maximumCapacity) {
            if (initialCapacity < 0 || initialCapacity > maximumCapacity) {
                throw new IllegalArgumentException("Invalid growing buffer capacities");
            }
            this.maximumCapacity = maximumCapacity;
            buffer = ByteBuffer.allocate(initialCapacity);
        }

        /// Returns the number of bytes currently stored.
        private int size() {
            return buffer.position();
        }

        /// Returns writable storage, growing by at least one byte when needed.
        private ByteBuffer writableBuffer() {
            ensureCapacity((long) buffer.position() + 1L);
            return buffer;
        }

        /// Appends all remaining source bytes.
        private void append(ByteBuffer source) {
            ensureCapacity((long) buffer.position() + source.remaining());
            buffer.put(source);
        }

        /// Returns a position-zero view limited to the stored bytes.
        private ByteBuffer toReadableBuffer() {
            ByteBuffer result = buffer.duplicate();
            result.flip();
            return result;
        }

        /// Grows storage to include the requested absolute byte count.
        private void ensureCapacity(long requiredCapacity) {
            if (requiredCapacity > maximumCapacity) {
                throw new BufferOverflowException();
            }
            if (requiredCapacity <= buffer.capacity()) {
                return;
            }

            int current = buffer.capacity();
            long grown = current + Math.max(current >>> 1, MINIMUM_INITIAL_CAPACITY);
            int newCapacity = (int) Math.min(
                    maximumCapacity,
                    Math.max(requiredCapacity, grown)
            );
            ByteBuffer replacement = ByteBuffer.allocate(newCapacity);
            buffer.flip();
            replacement.put(buffer);
            buffer = replacement;
        }
    }

    /// Exposes a dynamically growing buffer as a writable channel.
    @NotNullByDefault
    private static final class GrowingByteBufferChannel implements WritableByteChannel {
        /// The output storage.
        private final GrowingByteBuffer output;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a channel over dynamic storage.
        private GrowingByteBufferChannel(GrowingByteBuffer output) {
            this.output = output;
        }

        /// Appends all remaining source bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            int count = source.remaining();
            output.append(source);
            return count;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel without discarding stored bytes.
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
