// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes one exactly sized raw PPMd7 stream without retaining caller-owned buffers.
///
/// Raw PPMd7 has no embedded end marker, model properties, or decoded size. The container-supplied decoded size is the
/// exact boundary; any unused arithmetic finalization bytes remain in the caller source when that many bytes have been
/// produced.
@NotNullByDefault
public final class PPMd7Decoder implements CompressionDecoder {
    /// Configured maximum context order restored by reset.
    private final int maximumOrder;

    /// Configured model arena size restored by reset.
    private final long memorySize;

    /// Configured exact decoded byte count restored by reset.
    private final long decodedSize;

    /// Incremental arithmetic range decoder.
    private final PPMd7BufferRangeDecoder rangeDecoder = new PPMd7BufferRangeDecoder();

    /// Variant H context model with resumable symbol decoding.
    private final PPMd7Model model = new PPMd7Model(rangeDecoder);

    /// Number of decoded bytes still required by the externally declared size.
    private long remainingOutput;

    /// Current decoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an initialized exactly sized raw PPMd7 decoder.
    ///
    /// @param maximumOrder maximum Variant H context order from two through sixty-four
    /// @param memorySize model arena size in bytes
    /// @param decodedSize exact number of bytes the raw stream represents
    /// @throws IOException if the model configuration is invalid or its arena cannot be allocated
    public PPMd7Decoder(int maximumOrder, long memorySize, long decodedSize) throws IOException {
        this.maximumOrder = maximumOrder;
        this.memorySize = memorySize;
        this.decodedSize = decodedSize;
        model.initialize(true, maximumOrder, memorySize);
        remainingOutput = decodedSize;
    }

    /// Decodes source bytes until input, output space, or the externally declared stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Finishes decoding after all source bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Implements decoding with the selected source-completion state.
    private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        rangeDecoder.attach(source, endOfInput);
        try {
            try {
                rangeDecoder.initialize();
            } catch (PPMdInputUnavailableException exception) {
                return CodecOutcome.NEEDS_INPUT;
            }
            if (remainingOutput == 0L) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }

            while (target.hasRemaining()) {
                int symbol;
                try {
                    symbol = model.readByte();
                } catch (PPMdInputUnavailableException exception) {
                    return CodecOutcome.NEEDS_INPUT;
                }
                target.put((byte) symbol);
                remainingOutput--;
                if (remainingOutput == 0L) {
                    state = State.FINISHED;
                    return CodecOutcome.FINISHED;
                }
            }
            return CodecOutcome.NEEDS_OUTPUT;
        } finally {
            rangeDecoder.detach();
        }
    }

    /// Abandons the current stream and restores its configured model and exact-size boundary.
    @Override
    public void reset() {
        requireOpen();
        rangeDecoder.reset();
        model.reset();
        try {
            model.initialize(true, maximumOrder, memorySize);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to restore the validated PPMd model configuration", exception);
        }
        remainingOutput = decodedSize;
        state = State.ACTIVE;
    }

    /// Releases decoder-owned model state without consuming additional input.
    @Override
    public void close() {
        rangeDecoder.reset();
        model.reset();
        remainingOutput = 0L;
        state = State.CLOSED;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("PPMd decoder is closed");
        }
    }

    /// Enumerates the exactly sized PPMd decoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Prefix and symbols may be consumed.
        ACTIVE,

        /// The configured decoded byte count has been produced.
        FINISHED,

        /// Decoder-owned state has been released.
        CLOSED
    }
}
