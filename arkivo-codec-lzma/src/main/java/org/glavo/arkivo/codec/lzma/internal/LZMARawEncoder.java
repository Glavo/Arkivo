// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally encodes one headerless LZMA stream without retaining caller-owned buffers.
///
/// The match finder owns a bounded 64 KiB source window. Complete range-coded bytes are copied into a private sink and
/// drained before another caller source slice is accepted.
@NotNullByDefault
public final class LZMARawEncoder implements CompressionEncoder {
    /// Largest caller source slice copied into the match finder at once.
    private static final int SOURCE_SLICE_SIZE = 16 * 1024;

    /// Immutable model and dictionary properties.
    private final LZMAProperties properties;

    /// Exact expected input size, or `CompressionCodec.UNKNOWN_SIZE`.
    private final long expectedSize;

    /// Whether finalization emits the reserved LZMA end marker.
    private final boolean endMarker;

    /// Reusable source transfer array.
    private final byte[] transferBuffer = new byte[SOURCE_SLICE_SIZE];

    /// Complete compressed bytes awaiting caller target space.
    private final InMemorySink output = new InMemorySink();

    /// Buffered range-coded output, or null after closure.
    private @Nullable LZMAChannelOutput compressedOutput;

    /// Mutable match finder and range encoder, or null after closure.
    private @Nullable LZMAEncoderEngine encoder;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates a raw encoder with explicit model and termination parameters.
    ///
    /// @param properties   LZMA model and dictionary properties
    /// @param pledgedSize exact input size, or `CompressionCodec.UNKNOWN_SIZE`
    /// @param endMarker   whether finalization writes an EOS marker
    public LZMARawEncoder(LZMAProperties properties, long pledgedSize, boolean endMarker) {
        if (pledgedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        this.properties = Objects.requireNonNull(properties, "properties");
        this.expectedSize = pledgedSize;
        this.endMarker = endMarker;
        initializeEngine();
    }

    /// Encodes source bytes until source exhaustion or pending output requires target space.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");
        if (expectedSize >= 0L && source.remaining() > expectedSize - requireEncoder().inputSize()) {
            throw new IOException("LZMA input exceeds the pledged source size");
        }

        while (true) {
            output.drainTo(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }

            int length = Math.min(source.remaining(), transferBuffer.length);
            source.get(transferBuffer, 0, length);
            requireEncoder().write(transferBuffer, 0, length);
        }
    }

    /// Emits every complete range-coded byte currently derivable without ending the stream.
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.ACTIVE) {
            requireEncoder().flush();
            requireCompressedOutput().flush();
            state = State.FLUSHING;
        } else if (state != State.FLUSHING) {
            throw new IllegalStateException("Cannot flush while LZMA encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finalizes the range coder and drains its exact terminal bytes.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            if (expectedSize >= 0L && requireEncoder().inputSize() != expectedSize) {
                throw new IOException("LZMA input does not match the pledged source size");
            }
            requireEncoder().finish(endMarker);
            requireCompressedOutput().flush();
            state = State.FINISHING;
        } else if (state != State.FINISHING) {
            throw new IllegalStateException("Cannot finish while LZMA encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current stream and restores the configured initial state.
    @Override
    public void reset() {
        requireOpen();
        output.clear();
        initializeEngine();
        state = State.ACTIVE;
    }

    /// Releases dictionary, match-finder, and pending-output state without finalizing.
    @Override
    public void close() {
        state = State.CLOSED;
        encoder = null;
        compressedOutput = null;
        output.clear();
    }

    /// Creates a fresh match finder and range output over private storage.
    private void initializeEngine() {
        compressedOutput = new LZMAChannelOutput(output);
        encoder = new LZMAEncoderEngine(compressedOutput, properties);
    }

    /// Returns the active match finder and range encoder.
    private LZMAEncoderEngine requireEncoder() {
        @Nullable LZMAEncoderEngine current = encoder;
        if (current == null) {
            throw new IllegalStateException("LZMA encoder is closed");
        }
        return current;
    }

    /// Returns the active compressed-byte staging output.
    private LZMAChannelOutput requireCompressedOutput() {
        @Nullable LZMAChannelOutput current = compressedOutput;
        if (current == null) {
            throw new IllegalStateException("LZMA encoder is closed");
        }
        return current;
    }

    /// Requires the exact state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while LZMA encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("LZMA encoder is closed");
        }
    }

    /// Enumerates the raw encoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted.
        ACTIVE,

        /// A flush boundary must be drained.
        FLUSHING,

        /// Terminal range-coded bytes must be drained.
        FINISHING,

        /// The complete raw stream has finished.
        FINISHED,

        /// The encoder has been closed.
        CLOSED
    }

    /// Stores complete encoded bytes independently of caller-owned targets.
    @NotNullByDefault
    private static final class InMemorySink implements WritableByteChannel {
        /// Initial compressed-output capacity.
        private static final int INITIAL_CAPACITY = 8192;

        /// Compressed bytes between `start` and `end`.
        private byte[] bytes = new byte[INITIAL_CAPACITY];

        /// First compressed byte not yet returned to the caller.
        private int start;

        /// Position following the final compressed byte.
        private int end;

        /// Copies every supplied compressed byte into owned storage.
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

        /// Returns whether compressed bytes await caller target space.
        private boolean hasRemaining() {
            return start < end;
        }

        /// Copies as many compressed bytes as fit in a caller target.
        private void drainTo(ByteBuffer target) {
            int length = Math.min(target.remaining(), end - start);
            target.put(bytes, start, length);
            start += length;
            if (start == end) {
                start = 0;
                end = 0;
            }
        }

        /// Abandons every pending compressed byte.
        private void clear() {
            start = 0;
            end = 0;
        }

        /// Makes room for another complete compressed write.
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
