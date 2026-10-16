// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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

    /// Variant H context model with resumable symbol decoding, or null after closure.
    private @Nullable PPMd7Model model;

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
        PPMd7Model createdModel = new PPMd7Model(rangeDecoder);
        createdModel.initialize(true, maximumOrder, memorySize);
        model = createdModel;
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
        PPMd7Model currentModel = requireModel();
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
                    symbol = currentModel.readByte();
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
        PPMd7Model currentModel = requireModel();
        rangeDecoder.reset();
        currentModel.reset();
        try {
            currentModel.initialize(true, maximumOrder, memorySize);
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
        model = null;
        remainingOutput = 0L;
        state = State.CLOSED;
    }

    /// Returns the active model, rejecting operations after closure.
    private PPMd7Model requireModel() {
        @Nullable PPMd7Model current = model;
        if (current == null) {
            throw new IllegalStateException("PPMd decoder is closed");
        }
        return current;
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
