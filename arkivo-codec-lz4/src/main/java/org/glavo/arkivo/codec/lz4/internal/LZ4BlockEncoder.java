// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Encodes one bounded raw LZ4 block without retaining caller-owned buffers.
@NotNullByDefault
public final class LZ4BlockEncoder implements CompressionEncoder {
    /// Empty pending output.
    private static final ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Owned source bytes awaiting terminal block compression.
    private final OwnedByteAccumulator source;

    /// Encoded bytes awaiting caller-owned target space.
    private ByteBuffer output = EMPTY_OUTPUT;

    /// Current raw block encoder lifecycle state.
    private State state = State.COLLECTING;

    /// Creates an encoder for one raw block bounded by decoded byte count.
    public LZ4BlockEncoder(int maximumBlockSize) {
        source = new OwnedByteAccumulator(maximumBlockSize);
    }

    /// Collects source bytes until the caller declares the raw block complete.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        requireState(State.COLLECTING, "encode");
        this.source.append(source, "Raw LZ4 block exceeds its configured decoded-size bound");
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Compresses the collected block and drains its complete raw representation.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.COLLECTING) {
            output = ByteBuffer.wrap(LZ4BlockCompression.compress(source.takeBytes())).asReadOnlyBuffer();
            state = State.DRAINING;
        } else if (state != State.DRAINING) {
            throw new IllegalStateException("Cannot finish while raw LZ4 encoder state is " + state);
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
        source.clear();
        output = EMPTY_OUTPUT;
        state = State.COLLECTING;
    }

    /// Releases owned input and output without implicitly finishing the block.
    @Override
    public void close() {
        source.clear();
        output = EMPTY_OUTPUT;
        state = State.CLOSED;
    }

    /// Copies pending encoded bytes into a caller-owned target.
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

    /// Requires the exact state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while raw LZ4 encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Raw LZ4 encoder is closed");
        }
    }

    /// Tracks raw LZ4 block collection, output, and lifecycle state.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be collected.
        COLLECTING,

        /// Complete encoded bytes await target space.
        DRAINING,

        /// The raw block has been completely emitted.
        FINISHED,

        /// All owned state has been released.
        CLOSED
    }
}
