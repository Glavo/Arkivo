// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Decodes one EOF-delimited, bounded raw LZ4 block without retaining caller-owned buffers.
@NotNullByDefault
public final class LZ4BlockDecoder implements CompressionDecoder {
    /// Empty pending output.
    private static final ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Maximum accepted decoded block size.
    private final int maximumBlockSize;

    /// Maximum permitted match distance, or the unlimited sentinel.
    private final long maximumWindowSize;

    /// Maximum permitted working memory, or the unlimited sentinel.
    private final long maximumMemorySize;

    /// Owned compressed bytes awaiting the caller-declared end of input.
    private final OwnedByteAccumulator compressed;

    /// Decoded bytes awaiting caller-owned target space.
    private ByteBuffer output = EMPTY_OUTPUT;

    /// Current raw block decoder lifecycle state.
    private State state = State.COLLECTING;

    /// Creates a decoder for one bounded raw LZ4 block.
    public LZ4BlockDecoder(
            int maximumBlockSize,
            int maximumCompressedSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        if (maximumBlockSize < 0 || maximumCompressedSize < 0) {
            throw new IllegalArgumentException("Raw LZ4 size bounds must not be negative");
        }
        this.maximumBlockSize = maximumBlockSize;
        this.maximumWindowSize = maximumWindowSize;
        this.maximumMemorySize = maximumMemorySize;
        compressed = new OwnedByteAccumulator(maximumCompressedSize);
    }

    /// Collects compressed bytes until physical end of input establishes the raw block boundary.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        requireState(State.COLLECTING, "decode");
        appendCompressed(source);
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Decodes the complete raw block after all compressed bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.COLLECTING) {
            appendCompressed(source);
            LZ4BlockDecompression.Result result = LZ4BlockDecompression.decompress(
                    compressed.takeBytes(),
                    maximumBlockSize,
                    maximumWindowSize,
                    maximumMemorySize
            );
            output = ByteBuffer.wrap(result.bytes(), 0, result.length()).slice().asReadOnlyBuffer();
            state = State.DRAINING;
        } else if (state != State.DRAINING) {
            throw new IllegalStateException("Cannot finish while raw LZ4 decoder state is " + state);
        } else if (source.hasRemaining()) {
            throw new IllegalStateException("Cannot supply more input after raw LZ4 decoding has started");
        }

        drain(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        output = EMPTY_OUTPUT;
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons collected input and restores the initial raw block state.
    @Override
    public void reset() {
        requireOpen();
        compressed.clear();
        output = EMPTY_OUTPUT;
        state = State.COLLECTING;
    }

    /// Releases owned input and output without consuming further bytes.
    @Override
    public void close() {
        compressed.clear();
        output = EMPTY_OUTPUT;
        state = State.CLOSED;
    }

    /// Copies pending decoded bytes into a caller-owned target.
    private void drain(ByteBuffer target) {
        int length = Math.min(output.remaining(), target.remaining());
        int originalLimit = output.limit();
        output.limit(output.position() + length);
        try {
            target.put(output);
        } finally {
            output.limit(originalLimit);
        }
    }

    /// Copies compressed input after enforcing its owned-storage requirement.
    private void appendCompressed(ByteBuffer source) throws IOException {
        long requiredMemorySize = (long) compressed.size() + source.remaining();
        if (maximumMemorySize >= 0L && requiredMemorySize > maximumMemorySize) {
            throw new DecompressionMemoryLimitException(maximumMemorySize, requiredMemorySize);
        }
        compressed.append(source, "Raw LZ4 block exceeds its configured compressed-size bound");
    }

    /// Requires the exact state for an input-collecting operation.
    private void requireState(State required, String operation) {
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while raw LZ4 decoder state is " + state);
        }
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Raw LZ4 decoder is closed");
        }
    }

    /// Tracks compressed collection, decoded output, and lifecycle state.
    @NotNullByDefault
    private enum State {
        /// Compressed bytes may be collected.
        COLLECTING,

        /// Complete decoded bytes await target space.
        DRAINING,

        /// The complete raw block has been returned.
        FINISHED,

        /// All owned state has been released.
        CLOSED
    }
}
