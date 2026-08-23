// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.internal.PendingOutputChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally encodes one raw PPMd7 stream without retaining caller-owned buffers.
///
/// The pure Java model and range coder write into a bounded private sink. Source is supplied in bounded slices and no
/// additional source is accepted while complete compressed bytes await caller-owned target space.
@NotNullByDefault
public final class PPMd7Encoder implements CompressionEncoder {
    /// The largest caller-source slice passed to the model at once.
    private static final int SOURCE_SLICE_SIZE = 16 * 1024;

    /// Complete arithmetic bytes awaiting a caller-owned target.
    private final PendingOutputChannel output = new PendingOutputChannel();

    /// Configured maximum Variant H context order.
    private final int maximumOrder;

    /// Configured model arena size in bytes.
    private final long memorySize;

    /// Active arithmetic range encoder, or null after closure.
    private @Nullable PPMd7RangeEncoder rangeEncoder;

    /// Active Variant H context model, or null after closure.
    private @Nullable PPMd7Model model;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an initialized raw PPMd7 buffer encoder.
    ///
    /// @param maximumOrder maximum Variant H context order from two through sixty-four
    /// @param memorySize model arena size in bytes
    /// @throws IOException if the model configuration is invalid or its arena cannot be allocated
    public PPMd7Encoder(int maximumOrder, long memorySize) throws IOException {
        this.maximumOrder = maximumOrder;
        this.memorySize = memorySize;
        PPMd7RangeEncoder createdRangeEncoder = new PPMd7RangeEncoder(output);
        PPMd7Model createdModel = new PPMd7Model(createdRangeEncoder);
        createdModel.initialize(true, maximumOrder, memorySize);
        rangeEncoder = createdRangeEncoder;
        model = createdModel;
    }

    /// Encodes source bytes until source exhaustion or complete compressed output requires target space.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");

        while (true) {
            output.drainTo(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }

            int length = Math.min(source.remaining(), SOURCE_SLICE_SIZE);
            ByteBuffer slice = source.slice();
            slice.limit(length);
            PPMd7Model currentModel = requireModel();
            while (slice.hasRemaining()) {
                currentModel.writeByte(Byte.toUnsignedInt(slice.get()));
            }
            source.position(source.position() + length);
        }
    }

    /// Drains every complete arithmetic byte currently available without ending the raw stream.
    ///
    /// @param target the buffer receiving staged arithmetic bytes; its position advances by the bytes written
    /// @return {@link CodecOutcome#NEEDS_OUTPUT} while staged bytes remain, or {@link CodecOutcome#FLUSHED} when drained
    /// @throws IOException if arithmetic output cannot be produced
    /// @throws NullPointerException if {@code target} is {@code null}
    /// @throws IllegalStateException if a previous flush is not drained, the stream is finishing, or the encoder is closed
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.ACTIVE) {
            requireRangeEncoder().flushOutput();
            state = State.FLUSHING;
        } else if (state != State.FLUSHING) {
            throw new IllegalStateException("Cannot flush while PPMd encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finalizes the arithmetic representation and drains its exact terminal bytes.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            requireRangeEncoder().finish();
            state = State.FINISHING;
        } else if (state != State.FINISHING) {
            throw new IllegalStateException("Cannot finish while PPMd encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current raw stream and restores the configured model state.
    @Override
    public void reset() {
        requireOpen();
        output.clear();
        try {
            requireRangeEncoder().reset();
            PPMd7Model currentModel = requireModel();
            currentModel.reset();
            currentModel.initialize(true, maximumOrder, memorySize);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to restore the validated PPMd model configuration", exception);
        }
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without implicitly finalizing pending input.
    @Override
    public void close() {
        rangeEncoder = null;
        model = null;
        output.clear();
        state = State.CLOSED;
    }

    /// Returns the active arithmetic range encoder.
    private PPMd7RangeEncoder requireRangeEncoder() {
        @Nullable PPMd7RangeEncoder current = rangeEncoder;
        if (current == null) {
            throw new IllegalStateException("PPMd encoder is closed");
        }
        return current;
    }

    /// Returns the active Variant H context model.
    private PPMd7Model requireModel() {
        @Nullable PPMd7Model current = model;
        if (current == null) {
            throw new IllegalStateException("PPMd encoder is closed");
        }
        return current;
    }

    /// Requires the exact encoder state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while PPMd encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("PPMd encoder is closed");
        }
    }

    /// Enumerates the raw PPMd encoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted.
        ACTIVE,

        /// Complete range-coded bytes from a nonterminal flush await target space.
        FLUSHING,

        /// Terminal arithmetic bytes await target space.
        FINISHING,

        /// The raw stream is complete and may only be reset or closed.
        FINISHED,

        /// Encoder-owned state has been released.
        CLOSED
    }
}
