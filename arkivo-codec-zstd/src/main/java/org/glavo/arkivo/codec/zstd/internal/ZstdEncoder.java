// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Objects;

/// Incrementally encodes a sequence of Zstandard frames without binding caller-visible state to a channel.
@NotNullByDefault
public final class ZstdEncoder implements CompressionEncoder.FlushableFramed {
    /// Validated parameters restored by reset for the initial frame.
    private final ZstdEncoderParameters initialParameters;

    /// Parameters used by implicitly started frames after the initial frame.
    private final ZstdEncoderParameters defaultFrameParameters;

    /// Parameters used by the active or lazily initialized frame.
    private ZstdEncoderParameters parameters;

    /// Whether standard frame magic is omitted.
    private final boolean magicless;

    /// Internal encoded-output queue used by the pure Java frame implementation.
    private final QueueingChannel output = new QueueingChannel();

    /// Pure Java frame encoder targeting only the internal output queue.
    private ZstdChannelEncoder encoder;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates a pure Java Zstandard buffer encoder.
    ///
    /// @param parameters validated encoder parameters
    /// @param magicless  whether standard frame magic is omitted
    public ZstdEncoder(ZstdEncoderParameters parameters, boolean magicless) {
        this.initialParameters = Objects.requireNonNull(parameters, "parameters");
        this.defaultFrameParameters = parameters.withPledgedSourceSize(CompressionCodec.UNKNOWN_SIZE);
        this.parameters = parameters;
        this.magicless = magicless;
        this.encoder = createEncoder();
    }

    /// Explicitly starts another frame with independent source-size metadata.
    @Override
    public void startFrame(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        requireOpen();
        if (state != State.BETWEEN_FRAMES) {
            throw new IllegalStateException("Cannot start a Zstandard frame while encoder state is " + state);
        }
        encoder.abort();
        parameters = defaultFrameParameters.withPledgedSourceSize(options.sourceSize());
        encoder = createEncoder();
        state = State.ACTIVE;
    }

    /// Encodes source bytes while applying output backpressure between bounded input chunks.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            state = State.ACTIVE;
        }
        requireState(State.ACTIVE, "encode");
        drainOutput(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        while (source.hasRemaining()) {
            int originalLimit = source.limit();
            source.limit(source.position() + Math.min(source.remaining(), parameters.blockSize()));
            try {
                encoder.write(source);
            } finally {
                source.limit(originalLimit);
            }
            drainOutput(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Flushes every accepted source byte to a decodable block boundary.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING_FRAME || state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished Zstandard encoding");
        }
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.FLUSHED;
        }
        if (state == State.ACTIVE) {
            encoder.flush();
            state = State.FLUSHING;
        }
        drainOutput(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the complete Zstandard encoding and drains its final frame output.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the Zstandard encoding");
        }
        if (state == State.FINISHING_FRAME) {
            throw new IllegalStateException("Complete the active frame boundary before finishing the Zstandard encoding");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.BETWEEN_FRAMES) {
            encoder.abort();
            state = State.FINISHED;
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            encoder.finishFrame();
            state = State.FINISHING;
        }
        drainOutput(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Finishes the current Zstandard frame and prepares the encoder for another frame.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the Zstandard frame");
        }
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot finish a frame after terminal Zstandard finalization has started");
        }
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
        if (state == State.ACTIVE) {
            encoder.finishFrame();
            state = State.FINISHING_FRAME;
        }
        drainOutput(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        parameters = defaultFrameParameters;
        encoder = createEncoder();
        state = State.BETWEEN_FRAMES;
        return CodecOutcome.BOUNDARY_REACHED;
    }

    /// Abandons current frame state and creates a fresh encoder with the same immutable configuration.
    @Override
    public void reset() {
        requireOpen();
        encoder.abort();
        output.clearPending();
        parameters = initialParameters;
        encoder = createEncoder();
        state = State.ACTIVE;
    }

    /// Releases worker and frame state without implicitly finishing pending source bytes.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            encoder.abort();
            output.clearPending();
            state = State.CLOSED;
        }
    }

    /// Creates a fresh frame encoder targeting the internal queue.
    private ZstdChannelEncoder createEncoder() {
        return new ZstdChannelEncoder(output, ResourceOwnership.BORROWED, parameters, magicless);
    }

    /// Copies queued encoded bytes into the caller's target buffer.
    private void drainOutput(ByteBuffer target) {
        output.drain(target);
    }

    /// Requires the exact state for operations that accept source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while Zstandard encoder state is " + state);
        }
    }

    /// Requires worker and frame state to remain available.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Zstandard encoder is closed");
        }
    }

    /// Buffers output from the existing pure Java block and worker implementation.
    @NotNullByDefault
    private static final class QueueingChannel implements WritableByteChannel {
        /// Encoded byte ranges awaiting caller output space.
        private final ArrayDeque<ByteBuffer> pending = new ArrayDeque<>();

        /// Accepts one immutable encoded range from the internal frame encoder.
        @Override
        public int write(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            int length = source.remaining();
            if (length != 0) {
                pending.addLast(source.slice().asReadOnlyBuffer());
                source.position(source.limit());
            }
            return length;
        }

        /// Returns true because lifecycle is controlled by the owning buffer encoder.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Performs no action because the channel owns no external resource.
        @Override
        public void close() {
        }

        /// Returns whether encoded bytes still await caller output space.
        private boolean hasRemaining() {
            return !pending.isEmpty();
        }

        /// Copies queued encoded bytes into one caller target.
        private void drain(ByteBuffer target) {
            while (target.hasRemaining() && !pending.isEmpty()) {
                ByteBuffer current = pending.getFirst();
                int length = Math.min(current.remaining(), target.remaining());
                int originalLimit = current.limit();
                current.limit(current.position() + length);
                try {
                    target.put(current);
                } finally {
                    current.limit(originalLimit);
                }
                if (!current.hasRemaining()) {
                    pending.removeFirst();
                }
            }
        }

        /// Discards all output belonging to an abandoned frame.
        private void clearPending() {
            pending.clear();
        }
    }

    /// Tracks the explicit Zstandard frame lifecycle.
    private enum State {
        /// The encoder accepts source bytes.
        ACTIVE,

        /// A flush must be drained before more source bytes are accepted.
        FLUSHING,

        /// Non-terminal frame finalization has started and must be drained.
        FINISHING_FRAME,

        /// The previous frame completed and the next frame has not accepted input.
        BETWEEN_FRAMES,

        /// Terminal encoding finalization has started and must be drained.
        FINISHING,

        /// The complete encoding finished and may be reset or closed.
        FINISHED,

        /// Worker and frame resources were released.
        CLOSED
    }
}
