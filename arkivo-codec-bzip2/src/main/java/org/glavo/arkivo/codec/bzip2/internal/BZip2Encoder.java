// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.FramedCompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally encodes BZip2 frames without retaining caller-owned buffers.
///
/// The pure Java block encoder writes into a bounded in-memory sink. Source bytes are supplied in bounded slices and no
/// additional source is accepted while complete compressed bytes await caller-owned target space. Flushing completes
/// the current BZip2 frame, which is the format's independently decodable continuing-stream boundary.
@NotNullByDefault
public final class BZip2Encoder implements FramedCompressionEncoder {
    /// The largest source slice supplied to the block encoder at once.
    private static final int SOURCE_SLICE_SIZE = 16 * 1024;

    /// Complete compressed bytes emitted by the block encoder.
    private final InMemorySink output = new InMemorySink();

    /// Configured BZip2 block-size level.
    private final int blockSize;

    /// Active pure Java block encoder, or null after closure.
    private @Nullable BZip2ChannelEncoder encoder;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an encoder with a block-size level from one through nine.
    ///
    /// @param blockSize BZip2 block-size level
    public BZip2Encoder(int blockSize) {
        if (blockSize < 1 || blockSize > 9) {
            throw new IllegalArgumentException("BZip2 block size must be between 1 and 9");
        }
        this.blockSize = blockSize;
        encoder = createBlockEncoder();
    }

    /// Encodes source bytes until the source is exhausted or compressed output requires target space.
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
            int consumed = requireEncoder().write(slice);
            if (consumed <= 0 || consumed > length) {
                throw new IOException("BZip2 block encoder made invalid source progress: " + consumed);
            }
            source.position(source.position() + consumed);
        }
    }

    /// Completes the current BZip2 frame as a decodable flush boundary.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.ACTIVE) {
            requireEncoder().finishFrame();
            state = State.FLUSHING;
        } else if (state != State.FLUSHING) {
            throw new IllegalStateException("Cannot flush while BZip2 encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the current BZip2 frame while retaining the encoding session.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.ACTIVE) {
            requireEncoder().finishFrame();
            state = State.FRAME_FINISHING;
        } else if (state != State.FRAME_FINISHING) {
            throw new IllegalStateException("Cannot finish a frame while BZip2 encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.BOUNDARY_REACHED;
    }

    /// Finishes the complete BZip2 encoding session.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            requireEncoder().finish();
            state = State.FINISHING;
        } else if (state != State.FINISHING) {
            throw new IllegalStateException("Cannot finish while BZip2 encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current frame sequence and restores the configured initial state.
    @Override
    public void reset() {
        requireOpen();
        output.clear();
        encoder = createBlockEncoder();
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without implicitly finishing pending input.
    @Override
    public void close() {
        state = State.CLOSED;
        encoder = null;
        output.clear();
    }

    /// Creates the pure Java block encoder over the private memory sink.
    private BZip2ChannelEncoder createBlockEncoder() {
        try {
            return new BZip2ChannelEncoder(output, ChannelOwnership.RETAIN, blockSize);
        } catch (IOException exception) {
            throw new AssertionError("In-memory BZip2 encoder creation unexpectedly failed", exception);
        }
    }

    /// Returns the active block encoder.
    private BZip2ChannelEncoder requireEncoder() {
        @Nullable BZip2ChannelEncoder current = encoder;
        if (current == null) {
            throw new IllegalStateException("BZip2 encoder is closed");
        }
        return current;
    }

    /// Requires the exact state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while BZip2 encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("BZip2 encoder is closed");
        }
    }

    /// Enumerates the BZip2 encoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted.
        ACTIVE,

        /// A flush boundary must be drained.
        FLUSHING,

        /// A non-terminal frame boundary must be drained.
        FRAME_FINISHING,

        /// Terminal frame bytes must be drained.
        FINISHING,

        /// The complete encoding has finished.
        FINISHED,

        /// The encoder has been closed.
        CLOSED
    }

    /// Stores complete encoded bytes independently of caller-owned targets.
    @NotNullByDefault
    private static final class InMemorySink implements WritableByteChannel {
        /// Initial encoded-output capacity.
        private static final int INITIAL_CAPACITY = 8192;

        /// Encoded bytes between `start` and `end`.
        private byte[] bytes = new byte[INITIAL_CAPACITY];

        /// First encoded byte not yet returned to the caller.
        private int start;

        /// Position following the final encoded byte.
        private int end;

        /// Copies every supplied encoded byte into owned storage.
        @Override
        public int write(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            int length = source.remaining();
            ensureCapacity(length);
            source.get(bytes, end, length);
            end += length;
            return length;
        }

        /// Returns whether this private sink accepts bytes.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Retains this private sink for encoder reset reuse.
        @Override
        public void close() {
        }

        /// Returns whether encoded bytes await caller-owned target space.
        private boolean hasRemaining() {
            return start < end;
        }

        /// Copies as many encoded bytes as fit in a caller-owned target.
        private void drainTo(ByteBuffer target) {
            int length = Math.min(target.remaining(), end - start);
            target.put(bytes, start, length);
            start += length;
            if (start == end) {
                start = 0;
                end = 0;
            }
        }

        /// Abandons all pending encoded bytes.
        private void clear() {
            start = 0;
            end = 0;
        }

        /// Makes room for another encoded write while retaining pending bytes.
        private void ensureCapacity(int additionalLength) {
            int remaining = end - start;
            if (additionalLength <= bytes.length - end) {
                return;
            }
            if (additionalLength <= bytes.length - remaining) {
                System.arraycopy(bytes, start, bytes, 0, remaining);
                start = 0;
                end = remaining;
                return;
            }
            int required = Math.addExact(remaining, additionalLength);
            int capacity = bytes.length;
            while (capacity < required) {
                capacity = Math.max(Math.addExact(capacity, capacity >>> 1), required);
            }
            bytes = Arrays.copyOfRange(bytes, start, start + capacity);
            start = 0;
            end = remaining;
        }
    }
}
