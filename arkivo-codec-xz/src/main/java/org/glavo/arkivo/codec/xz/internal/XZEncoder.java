// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.xz.XZFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally encodes XZ streams without retaining caller-owned buffers.
///
/// The pure Java XZ writer receives bounded source slices and writes complete encoded bytes into a private memory sink.
/// No additional source is accepted while encoded bytes await caller-owned target space.
@NotNullByDefault
public final class XZEncoder implements CompressionEncoder.FlushableFramed {
    /// The largest source slice passed to the XZ writer in one operation.
    private static final int SOURCE_SLICE_SIZE = 16 * 1024;

    /// Complete encoded bytes emitted by the XZ writer.
    private final InMemorySink output = new InMemorySink();

    /// Immutable LZMA2 properties restored by reset.
    private final LZMAProperties properties;

    /// Immutable XZ integrity-check identifier.
    private final int checkType;

    /// Immutable preprocessing filter chain.
    private final XZFilterChain filterChain;

    /// Immutable maximum uncompressed Block size, or zero for an unbounded Block.
    private final long maximumBlockSize;

    /// Active pure Java XZ writer, or null after closure.
    private @Nullable XZChannelEncoder encoder;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an encoder with complete immutable XZ settings.
    ///
    /// @param properties LZMA2 model and dictionary properties
    /// @param checkType XZ integrity-check identifier
    /// @param filterChain ordered preprocessing filters
    /// @param maximumBlockSize maximum uncompressed Block size, or zero for an unbounded Block
    /// @throws IOException if the check type is unsupported or the initial Stream Header cannot be produced
    /// @throws NullPointerException if {@code properties} or {@code filterChain} is {@code null}
    /// @throws IllegalArgumentException if the dictionary is unsupported or {@code maximumBlockSize} is negative
    public XZEncoder(
            LZMAProperties properties,
            int checkType,
            XZFilterChain filterChain,
            long maximumBlockSize
    ) throws IOException {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.checkType = checkType;
        this.filterChain = Objects.requireNonNull(filterChain, "filterChain");
        if (maximumBlockSize < 0L) {
            throw new IllegalArgumentException("XZ maximum Block size must not be negative");
        }
        this.maximumBlockSize = maximumBlockSize;
        encoder = createWriter();
    }

    /// Explicitly starts another XZ Stream after a completed Stream boundary.
    @Override
    public void startFrame(EncodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        requireOpen();
        if (state != State.BETWEEN_FRAMES) {
            throw new IllegalStateException("Cannot start an XZ Stream while encoder state is " + state);
        }
        requireWriter().startFrame(options);
        state = State.ACTIVE;
    }

    /// Encodes source bytes until the source is exhausted or compressed output requires target space.
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
            int consumed = requireWriter().write(slice);
            if (consumed <= 0 || consumed > length) {
                throw new IOException("XZ writer made invalid source progress: " + consumed);
            }
            source.position(source.position() + consumed);
        }
    }

    /// Flushes the active XZ Block to an incremental LZMA2 decoding boundary.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.FLUSHED;
        }
        if (state == State.ACTIVE) {
            requireWriter().flush();
            state = State.FLUSHING;
        } else if (state != State.FLUSHING) {
            throw new IllegalStateException("Cannot flush while XZ encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the current XZ Stream while retaining the session for a following Stream.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
        if (state == State.ACTIVE) {
            requireWriter().finishFrame();
            state = State.FRAME_FINISHING;
        } else if (state != State.FRAME_FINISHING) {
            throw new IllegalStateException("Cannot finish a frame while XZ encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.BETWEEN_FRAMES;
        return CodecOutcome.BOUNDARY_REACHED;
    }

    /// Finishes the complete XZ encoding session.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.BETWEEN_FRAMES) {
            requireWriter().finish();
            state = State.FINISHING;
        }
        if (state == State.ACTIVE) {
            requireWriter().finish();
            state = State.FINISHING;
        } else if (state != State.FINISHING) {
            throw new IllegalStateException("Cannot finish while XZ encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current Stream sequence and restores the configured initial state.
    @Override
    public void reset() {
        requireOpen();
        output.clear();
        try {
            encoder = createWriter();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to reset the in-memory XZ writer", exception);
        }
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without implicitly finishing pending input.
    @Override
    public void close() {
        state = State.CLOSED;
        encoder = null;
        output.clear();
    }

    /// Creates the pure Java XZ writer over the private memory sink.
    private XZChannelEncoder createWriter() throws IOException {
        return new XZChannelEncoder(
                output,
                ResourceOwnership.BORROWED,
                properties,
                checkType,
                filterChain,
                maximumBlockSize
        );
    }

    /// Returns the active XZ writer.
    private XZChannelEncoder requireWriter() {
        @Nullable XZChannelEncoder current = encoder;
        if (current == null) {
            throw new IllegalStateException("XZ encoder is closed");
        }
        return current;
    }

    /// Requires the exact state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while XZ encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("XZ encoder is closed");
        }
    }

    /// Enumerates the XZ encoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted.
        ACTIVE,

        /// A Stream boundary completed and no following Stream is active.
        BETWEEN_FRAMES,

        /// A flush boundary must be drained.
        FLUSHING,

        /// A nonterminal Stream boundary must be drained.
        FRAME_FINISHING,

        /// Terminal Stream bytes must be drained.
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
