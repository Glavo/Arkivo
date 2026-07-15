// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes Zstandard frames with the pure Java block and entropy engine.
@NotNullByDefault
public final class ZstdChannelDecoder implements DecompressingReadableByteChannel {
    /// The empty pending-output buffer.
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Tracks closure of the compressed source.
    private final OwnedChannelCloser sourceCloser;

    /// Buffered source input.
    private final ZstdChannelInput input;

    /// Incremental frame decoder.
    private final ZstdFrameDecoder frameDecoder;

    /// Pending decoded block bytes.
    private ByteBuffer outputBuffer = EMPTY;

    /// Number of bytes returned to callers.
    private long outputBytes;

    /// Whether the pending block completes a frame.
    private boolean pendingFrameFinished;

    /// Whether the most recent operation observed a frame boundary.
    private boolean lastFrameFinished;

    /// Whether physical end of input was reached.
    private boolean endOfInput;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a pure Java decoder.
    ///
    /// @param source compressed-data source
    /// @param ownership whether closing this decoder closes the source
    /// @param dictionary configured dictionary bytes, or null
    /// @param maximumWindowSize maximum permitted frame window, or the unknown-size sentinel
    /// @param magicless whether standard frame magic is omitted
    /// @param verifyChecksums whether present frame checksums are verified
    public ZstdChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            @Nullable CompressionDictionary dictionary,
            long maximumWindowSize,
            boolean magicless,
            boolean verifyChecksums
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.input = new ZstdChannelInput(source);
        this.frameDecoder = new ZstdFrameDecoder(
                input,
                ZstdDictionary.parse(dictionary),
                maximumWindowSize,
                magicless,
                verifyChecksums
        );
    }

    /// Reads decoded bytes into the caller's target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        return readDecoded(target, false);
    }

    /// Decodes one increment while optionally stopping at the next verified frame boundary.
    @Override
    public CodecResult decode(ByteBuffer target, DecodeDirective directive) throws IOException {
        Objects.requireNonNull(directive, "directive");
        long inputBefore = input.byteCount();
        long outputBefore = outputBytes;
        boolean stopAtFrame = directive == DecodeDirective.STOP_AT_FRAME;
        int read = readDecoded(target, stopAtFrame);
        CodecStatus status = stopAtFrame && lastFrameFinished
                ? CodecStatus.FRAME_FINISHED
                : read < 0 ? CodecStatus.END_OF_INPUT : CodecStatus.ACTIVE;
        return new CodecResult(
                input.byteCount() - inputBefore,
                outputBytes - outputBefore,
                status
        );
    }

    /// Returns the number of compressed bytes logically consumed.
    @Override
    public long inputBytes() {
        return input.byteCount();
    }

    /// Returns the number of compressed bytes fetched from the source.
    @Override
    public long sourceBytes() {
        return input.sourceByteCount();
    }

    /// Returns compressed bytes fetched but not consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return input.unconsumedInput();
    }

    /// Returns the number of decoded bytes delivered to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes an owned source.
    @Override
    public void close() throws IOException {
        open = false;
        sourceCloser.closeAfter(null);
    }

    /// Performs one decoded read with explicit frame-boundary behavior.
    private int readDecoded(ByteBuffer target, boolean stopAtFrame) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        lastFrameFinished = false;
        if (!target.hasRemaining()) {
            return 0;
        }
        if (outputBuffer.hasRemaining()) {
            return copyOutput(target, stopAtFrame);
        }
        if (pendingFrameFinished) {
            pendingFrameFinished = false;
            if (stopAtFrame) {
                lastFrameFinished = true;
                return 0;
            }
        }
        if (endOfInput) {
            return -1;
        }

        while (true) {
            ZstdFrameDecoder.Step step = frameDecoder.readStep();
            if (step.endOfInput()) {
                endOfInput = true;
                return -1;
            }
            pendingFrameFinished = step.frameFinished();
            byte[] output = step.output();
            if (output.length != 0) {
                outputBuffer = ByteBuffer.wrap(output).asReadOnlyBuffer();
                return copyOutput(target, stopAtFrame);
            }
            if (pendingFrameFinished) {
                pendingFrameFinished = false;
                if (stopAtFrame) {
                    lastFrameFinished = true;
                    return 0;
                }
            }
        }
    }

    /// Copies pending output and reports a drained frame boundary when requested.
    private int copyOutput(ByteBuffer target, boolean stopAtFrame) {
        int copied = Math.min(target.remaining(), outputBuffer.remaining());
        ByteBuffer chunk = outputBuffer.slice();
        chunk.limit(copied);
        target.put(chunk);
        outputBuffer.position(outputBuffer.position() + copied);
        outputBytes += copied;
        if (!outputBuffer.hasRemaining() && pendingFrameFinished && stopAtFrame) {
            pendingFrameFinished = false;
            lastFrameFinished = true;
        }
        return copied;
    }

    /// Requires this decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
