// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
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
    private final InMemorySink output = new InMemorySink();

    /// Configured maximum Variant H context order.
    private final int maximumOrder;

    /// Configured model arena size in bytes.
    private final long memorySize;

    /// Active pure Java PPMd channel core, or null after closure.
    private @Nullable PPMd7ChannelEncoder encoder;

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
        encoder = createEncoder();
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
            int consumed = requireEncoder().write(slice);
            if (consumed <= 0 || consumed > length) {
                throw new IOException("PPMd encoder made invalid source progress: " + consumed);
            }
            source.position(source.position() + consumed);
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
            requireEncoder().flush();
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
            requireEncoder().finish();
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
            requireEncoder().resetForBufferEngine();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to restore the validated PPMd model configuration", exception);
        }
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without implicitly finalizing pending input.
    @Override
    public void close() {
        encoder = null;
        output.clear();
        state = State.CLOSED;
    }

    /// Creates a fresh pure Java channel core over private memory storage.
    private PPMd7ChannelEncoder createEncoder() throws IOException {
        return new PPMd7ChannelEncoder(
                output,
                ResourceOwnership.BORROWED,
                maximumOrder,
                memorySize
        );
    }

    /// Returns the active pure Java PPMd channel core.
    private PPMd7ChannelEncoder requireEncoder() {
        @Nullable PPMd7ChannelEncoder current = encoder;
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
