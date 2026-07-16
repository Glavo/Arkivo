// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.PledgedSourceSizeCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/// Drives transport-independent codec engines directly between caller-owned byte buffers.
@NotNullByDefault
final class DirectByteBufferCodecSupport {
    /// The minimum nonempty capacity used by allocating operations.
    private static final int MINIMUM_INITIAL_CAPACITY = 64;

    /// The largest speculative initial allocation before output size is known.
    private static final int MAXIMUM_INITIAL_CAPACITY = 1 << 20;

    /// Creates no instances.
    private DirectByteBufferCodecSupport() {
    }

    /// Compresses all source bytes into a dynamically growing heap buffer.
    static ByteBuffer compressAllocating(
            CompressionCodec codec,
            ByteBuffer source
    ) throws IOException {
        int sourceSize = source.remaining();
        GrowingByteBuffer output = new GrowingByteBuffer(
                compressionInitialCapacity(codec, sourceSize),
                Integer.MAX_VALUE
        );
        try (CompressionEncoder encoder = newEncoder(codec, sourceSize)) {
            encode(encoder, source, output);
        }
        return output.toReadableBuffer();
    }

    /// Compresses all source bytes into the fixed caller-owned target.
    static void compress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target
    ) throws IOException {
        int sourceSize = source.remaining();
        try (CompressionEncoder encoder = newEncoder(codec, sourceSize)) {
            encode(encoder, source, target);
        }
    }

    /// Decompresses complete input into a dynamically growing bounded heap buffer.
    static ByteBuffer decompressAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            int maximumOutputSize,
            DecompressionLimits limits
    ) throws IOException {
        return decodeAllocating(codec, source, maximumOutputSize, limits, false);
    }

    /// Decompresses complete input into the fixed caller-owned target.
    static void decompress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        decode(codec, source, target, limits, false);
    }

    /// Decompresses one frame into a dynamically growing bounded heap buffer.
    static ByteBuffer decompressFrameAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            int maximumOutputSize,
            DecompressionLimits limits
    ) throws IOException {
        return decodeAllocating(codec, source, maximumOutputSize, limits, true);
    }

    /// Decompresses one frame into the fixed caller-owned target.
    static void decompressFrame(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        decode(codec, source, target, limits, true);
    }

    /// Creates an encoder and supplies the exact one-shot source size when the codec supports it.
    private static CompressionEncoder newEncoder(CompressionCodec codec, long sourceSize) throws IOException {
        if (codec instanceof PledgedSourceSizeCodec pledgedSourceSizeCodec) {
            return pledgedSourceSizeCodec.newEncoder(sourceSize);
        }
        return codec.newEncoder();
    }

    /// Drives one encoder into dynamically growing output.
    private static void encode(
            CompressionEncoder encoder,
            ByteBuffer source,
            GrowingByteBuffer output
    ) throws IOException {
        boolean inputComplete = !source.hasRemaining();
        while (!inputComplete) {
            ByteBuffer target = output.writableBuffer();
            int sourcePosition = source.position();
            int targetPosition = target.position();
            CodecOutcome outcome = encoder.encode(source, target);
            requireProgress(sourcePosition, source.position(), targetPosition, target.position(), "encoder");

            if (outcome == CodecOutcome.NEEDS_INPUT) {
                if (source.hasRemaining()) {
                    throw new IOException("Compression encoder requested input before consuming its source buffer");
                }
                inputComplete = true;
                continue;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected compression encode outcome: " + outcome);
            }
            if (target.hasRemaining()) {
                throw new IOException("Compression encoder requested output without filling its target buffer");
            }
        }

        while (true) {
            ByteBuffer target = output.currentBuffer();
            int targetPosition = target.position();
            CodecOutcome outcome = encoder.finish(target);
            if (outcome == CodecOutcome.FINISHED) {
                return;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected compression finish outcome: " + outcome);
            }
            if (!target.hasRemaining()) {
                output.writableBuffer();
                continue;
            }
            if (target.position() == targetPosition) {
                throw new IOException("Compression encoder made no finishing progress");
            }
            throw new IOException("Compression encoder requested output without filling its target buffer");
        }
    }

    /// Drives one encoder into fixed output.
    private static void encode(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteBuffer target
    ) throws IOException {
        boolean inputComplete = !source.hasRemaining();
        while (!inputComplete) {
            int sourcePosition = source.position();
            int targetPosition = target.position();
            CodecOutcome outcome = encoder.encode(source, target);

            if (outcome == CodecOutcome.NEEDS_INPUT) {
                requireProgress(sourcePosition, source.position(), targetPosition, target.position(), "encoder");
                if (source.hasRemaining()) {
                    throw new IOException("Compression encoder requested input before consuming its source buffer");
                }
                inputComplete = true;
                continue;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected compression encode outcome: " + outcome);
            }
            if (!target.hasRemaining()) {
                throw new BufferOverflowException();
            }
            requireProgress(sourcePosition, source.position(), targetPosition, target.position(), "encoder");
            throw new IOException("Compression encoder requested output without filling its target buffer");
        }

        while (true) {
            int targetPosition = target.position();
            CodecOutcome outcome = encoder.finish(target);
            if (outcome == CodecOutcome.FINISHED) {
                return;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected compression finish outcome: " + outcome);
            }
            if (!target.hasRemaining()) {
                throw new BufferOverflowException();
            }
            if (target.position() == targetPosition) {
                throw new IOException("Compression encoder made no finishing progress");
            }
            throw new IOException("Compression encoder requested output without filling its target buffer");
        }
    }

    /// Drives one decoder into dynamically growing bounded output.
    private static ByteBuffer decodeAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            int maximumOutputSize,
            DecompressionLimits limits,
            boolean singleFrame
    ) throws IOException {
        GrowingByteBuffer output = new GrowingByteBuffer(
                decompressionInitialCapacity(source.remaining(), maximumOutputSize),
                maximumOutputSize
        );
        ByteBuffer overflowProbe = ByteBuffer.allocate(1);
        DecompressionLimits engineLimits =
                limits.withMaximumOutputSize(DecompressionLimits.UNLIMITED_SIZE);

        try (CompressionDecoder decoder = codec.newDecoder(engineLimits)) {
            boolean supportsConcatenation = decoder instanceof CompressionDecoder.Framed;
            boolean continueFrames = supportsConcatenation && !singleFrame;
            if (supportsConcatenation && !source.hasRemaining()) {
                return output.toReadableBuffer();
            }
            while (true) {
                boolean probing = output.size() == maximumOutputSize;
                ByteBuffer target = probing ? overflowProbe.clear() : output.writableBuffer();
                int sourcePosition = source.position();
                int targetPosition = target.position();
                CodecOutcome outcome = decoder.decode(source, target, true);
                int produced = target.position() - targetPosition;
                if (probing && produced != 0) {
                    throw new DecompressionLimitException(maximumOutputSize);
                }

                if (outcome == CodecOutcome.FINISHED) {
                    if (!continueFrames || !source.hasRemaining()) {
                        return output.toReadableBuffer();
                    }
                    requireProgress(sourcePosition, source.position(), targetPosition, target.position(), "decoder");
                    decoder.reset();
                    continue;
                }
                handleDecodeContinuation(
                        decoder,
                        source,
                        target,
                        outcome,
                        sourcePosition,
                        targetPosition
                );
            }
        }
    }

    /// Drives one decoder into fixed output while enforcing a total operation output limit.
    private static void decode(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits,
            boolean singleFrame
    ) throws IOException {
        long maximumOutputSize = limits.maximumOutputSize();
        int outputStart = target.position();
        ByteBuffer overflowProbe = ByteBuffer.allocate(1);
        DecompressionLimits engineLimits =
                limits.withMaximumOutputSize(DecompressionLimits.UNLIMITED_SIZE);

        try (CompressionDecoder decoder = codec.newDecoder(engineLimits)) {
            boolean supportsConcatenation = decoder instanceof CompressionDecoder.Framed;
            boolean continueFrames = supportsConcatenation && !singleFrame;
            if (supportsConcatenation && !source.hasRemaining()) {
                return;
            }
            while (true) {
                long outputBytes = target.position() - (long) outputStart;
                boolean limitReached =
                        maximumOutputSize >= 0L && outputBytes == maximumOutputSize;
                boolean probing = limitReached || !target.hasRemaining();
                ByteBuffer operationTarget = probing ? overflowProbe.clear() : target;
                int originalLimit = operationTarget.limit();
                if (!probing && maximumOutputSize >= 0L) {
                    long remaining = maximumOutputSize - outputBytes;
                    if (operationTarget.remaining() > remaining) {
                        operationTarget.limit(operationTarget.position() + Math.toIntExact(remaining));
                    }
                }

                int sourcePosition = source.position();
                int targetPosition = operationTarget.position();
                CodecOutcome outcome;
                try {
                    outcome = decoder.decode(source, operationTarget, true);
                } finally {
                    operationTarget.limit(originalLimit);
                }
                int produced = operationTarget.position() - targetPosition;
                if (probing && produced != 0) {
                    if (limitReached) {
                        throw new DecompressionLimitException(maximumOutputSize);
                    }
                    throw new BufferOverflowException();
                }

                if (outcome == CodecOutcome.FINISHED) {
                    if (!continueFrames || !source.hasRemaining()) {
                        return;
                    }
                    requireProgress(
                            sourcePosition,
                            source.position(),
                            targetPosition,
                            operationTarget.position(),
                            "decoder"
                    );
                    decoder.reset();
                    continue;
                }
                handleDecodeContinuation(
                        decoder,
                        source,
                        operationTarget,
                        outcome,
                        sourcePosition,
                        targetPosition
                );
            }
        }
    }

    /// Validates one nonterminal decoder outcome before the next operation.
    private static void handleDecodeContinuation(
            CompressionDecoder decoder,
            ByteBuffer source,
            ByteBuffer target,
            CodecOutcome outcome,
            int sourcePosition,
            int targetPosition
    ) throws IOException {
        if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
            long dictionaryId = decoder instanceof CompressionDecoder.DictionaryAware dictionaryDecoder
                    ? dictionaryDecoder.requiredDictionaryId()
                    : CompressionCodec.UNKNOWN_SIZE;
            throw new IOException("Compression decoder requires dictionary " + dictionaryId);
        }
        if (outcome == CodecOutcome.NEEDS_INPUT) {
            if (source.hasRemaining()) {
                throw new IOException("Compression decoder requested input before consuming its source buffer");
            }
            throw new IOException("Compression decoder requested input after end of input");
        }
        if (outcome != CodecOutcome.NEEDS_OUTPUT) {
            throw new IOException("Unexpected compression decode outcome: " + outcome);
        }
        if (target.hasRemaining() && target.position() == targetPosition) {
            throw new IOException("Compression decoder requested output without producing target bytes");
        }
        requireProgress(sourcePosition, source.position(), targetPosition, target.position(), "decoder");
    }

    /// Requires one engine operation to consume or produce at least one byte.
    private static void requireProgress(
            int sourceBefore,
            int sourceAfter,
            int targetBefore,
            int targetAfter,
            String operation
    ) throws IOException {
        if (sourceBefore == sourceAfter && targetBefore == targetAfter) {
            throw new IOException("Compression " + operation + " made no progress");
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

    /// Returns an initial decompression capacity within the strict caller limit.
    private static int decompressionInitialCapacity(int sourceSize, int maximumCapacity) {
        long suggested = Math.max(
                MINIMUM_INITIAL_CAPACITY,
                Math.min(MAXIMUM_INITIAL_CAPACITY, (long) sourceSize * 2L)
        );
        return (int) Math.min(maximumCapacity, suggested);
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

        /// Returns the current output buffer without forcing unused capacity.
        private ByteBuffer currentBuffer() {
            return buffer;
        }

        /// Returns writable storage, growing by at least one byte when needed.
        private ByteBuffer writableBuffer() {
            ensureCapacity((long) buffer.position() + 1L);
            return buffer;
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
}
