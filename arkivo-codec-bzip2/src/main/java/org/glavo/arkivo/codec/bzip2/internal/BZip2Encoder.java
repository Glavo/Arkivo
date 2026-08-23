// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.internal.PendingOutputChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally encodes BZip2 frames without retaining caller-owned buffers.
///
/// The pure Java stream writer emits into a bounded in-memory sink. Source bytes are supplied in bounded slices and no
/// additional source is accepted while complete compressed bytes await caller-owned target space. Flushing completes
/// the current BZip2 frame, which is the format's independently decodable continuing-stream boundary.
@NotNullByDefault
public final class BZip2Encoder implements CompressionEncoder.Framed {
    /// The largest source slice supplied to the stream writer at once.
    private static final int SOURCE_SLICE_SIZE = 16 * 1024;

    /// Complete compressed bytes emitted by the stream writer.
    private final PendingOutputChannel output = new PendingOutputChannel();

    /// Configured BZip2 block-size level.
    private final int blockSize;

    /// Active pure Java stream writer, or null after closure.
    private @Nullable BZip2StreamEncoder writer;

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
        writer = createStreamEncoder();
    }

    /// Explicitly starts another BZip2 frame after a completed boundary.
    @Override
    public void startFrame(EncodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        requireOpen();
        if (state != State.BETWEEN_FRAMES) {
            throw new IllegalStateException("Cannot start a BZip2 frame while encoder state is " + state);
        }
        requireWriter().startFrame();
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
                throw new IOException("BZip2 stream writer made invalid source progress: " + consumed);
            }
            source.position(source.position() + consumed);
        }
    }

    /// Completes the current BZip2 frame as a decodable flush boundary.
    ///
    /// The target position advances by the encoded bytes copied during this call. Repeated calls with additional target
    /// space are required after `NEEDS_OUTPUT`.
    ///
    /// @param target destination for pending encoded bytes
    /// @return `FLUSHED` once the frame boundary is complete, or `NEEDS_OUTPUT` while bytes remain
    /// @throws IOException if frame finalization fails
    /// @throws IllegalStateException if the encoder is closed or another finalization operation is active
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.FLUSHED;
        }
        if (state == State.ACTIVE) {
            requireWriter().finishFrame();
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
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
        if (state == State.ACTIVE) {
            requireWriter().finishFrame();
            state = State.FRAME_FINISHING;
        } else if (state != State.FRAME_FINISHING) {
            throw new IllegalStateException("Cannot finish a frame while BZip2 encoder state is " + state);
        }

        output.drainTo(target);
        if (output.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.BETWEEN_FRAMES;
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
        if (state == State.BETWEEN_FRAMES) {
            requireWriter().finishFrame();
            state = State.FINISHING;
        }
        if (state == State.ACTIVE) {
            requireWriter().finishFrame();
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
        writer = createStreamEncoder();
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without implicitly finishing pending input.
    @Override
    public void close() {
        state = State.CLOSED;
        writer = null;
        output.clear();
    }

    /// Creates the pure Java stream writer over the private memory sink.
    private BZip2StreamEncoder createStreamEncoder() {
        try {
            return new BZip2StreamEncoder(output, blockSize);
        } catch (IOException exception) {
            throw new AssertionError("In-memory BZip2 stream-writer creation unexpectedly failed", exception);
        }
    }

    /// Returns the active stream writer.
    private BZip2StreamEncoder requireWriter() {
        @Nullable BZip2StreamEncoder current = writer;
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

        /// A frame boundary completed and no following frame is active.
        BETWEEN_FRAMES,

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
}
