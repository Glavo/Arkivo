// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Objects;

/// Incrementally encodes one Zstandard frame without binding caller-visible state to a channel.
@NotNullByDefault
public final class ZstdEncoder implements CompressionEncoder {
    /// Validated immutable encoder parameters.
    private final ZstdEncoderParameters parameters;

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
    /// @param magicless whether standard frame magic is omitted
    public ZstdEncoder(ZstdEncoderParameters parameters, boolean magicless) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.magicless = magicless;
        this.encoder = createEncoder();
    }

    /// Encodes source bytes while applying output backpressure between bounded input chunks.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
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
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished Zstandard frame");
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

    /// Finishes the current frame and drains its final block and optional checksum.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the Zstandard frame");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FRAME_FINISHED;
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
        return CodecOutcome.FRAME_FINISHED;
    }

    /// Abandons current frame state and creates a fresh encoder with the same immutable configuration.
    @Override
    public void reset() {
        requireOpen();
        encoder.abort();
        output.clearPending();
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
        return new ZstdChannelEncoder(output, ChannelOwnership.RETAIN, parameters, magicless);
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

        /// Frame finalization has started and must be drained.
        FINISHING,

        /// The frame completed and may be reset or closed.
        FINISHED,

        /// Worker and frame resources were released.
        CLOSED
    }
}
