// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDirectBufferDecompressingStreamNoFinalizer;
import com.github.luben.zstd.ZstdException;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes Zstandard frames through a native direct-ByteBuffer context.
@NotNullByDefault
public final class ZstdChannelDecoder implements CompressionDecoder {
    /// The compressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 128 * 1024;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The configured native Zstandard decompression context.
    private final ZstdDecompressCtx context;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The direct decoded-output staging buffer.
    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(
            ZstdDirectBufferDecompressingStreamNoFinalizer.recommendedTargetBufferSize()
    );

    /// The maximum permitted frame window size, or the unknown sentinel.
    private final long maximumWindowSize;

    /// Whether the current frame window has been validated.
    private boolean windowValidated;

    /// The number of compressed bytes logically consumed by the native decoder.
    private long inputBytes;

    /// The number of compressed bytes obtained from the source.
    private long sourceBytes;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether the current frame has completed.
    private boolean frameFinished;

    /// Whether the last decode operation delivered a complete frame.
    private boolean lastFrameFinished;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a decoder that owns the configured native context.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    /// @param context configured native decompression context
    public ZstdChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            ZstdDecompressCtx context
    ) {
        this(source, ownership, context, CompressionCodec.UNKNOWN_SIZE);
    }

    /// Creates a decoder with an optional maximum frame window size.
    public ZstdChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            ZstdDecompressCtx context,
            long maximumWindowSize
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.context = Objects.requireNonNull(context, "context");
        this.maximumWindowSize = maximumWindowSize;
        windowValidated = maximumWindowSize < 0L;
        inputBuffer.limit(0);
        outputBuffer.limit(0);
    }

    /// Reads decoded bytes into the caller's target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        return readDecoded(target, false);
    }

    /// Decodes one increment while optionally stopping after the current frame.
    @Override
    public CodecResult decode(ByteBuffer target, DecodeDirective directive) throws IOException {
        Objects.requireNonNull(directive, "directive");
        long inputBefore = inputBytes;
        long outputBefore = outputBytes;
        boolean stopAtFrame = directive == DecodeDirective.STOP_AT_FRAME;
        int read = readDecoded(target, stopAtFrame);
        CodecStatus status = stopAtFrame && lastFrameFinished
                ? CodecStatus.FRAME_FINISHED
                : read < 0 ? CodecStatus.END_OF_INPUT : CodecStatus.ACTIVE;
        return new CodecResult(inputBytes - inputBefore, outputBytes - outputBefore, status);
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
            int copied = copyOutput(target);
            if (stopAtFrame && frameFinished && !outputBuffer.hasRemaining()) {
                lastFrameFinished = true;
            }
            return copied;
        }
        while (true) {
            if (frameFinished && !startNextFrame()) {
                return -1;
            }
            if (!inputBuffer.hasRemaining() && !readCompressedInput()) {
                throw new EOFException("Unexpected end of Zstandard frame");
            }
            validateFrameWindow();

            outputBuffer.clear();
            int inputPosition = inputBuffer.position();
            try {
                frameFinished = context.decompressDirectByteBufferStream(outputBuffer, inputBuffer);
            } catch (ZstdException exception) {
                throw new IOException("Invalid Zstandard frame", exception);
            }
            inputBytes += inputBuffer.position() - inputPosition;
            outputBuffer.flip();
            if (outputBuffer.hasRemaining()) {
                int copied = copyOutput(target);
                if (stopAtFrame && frameFinished && !outputBuffer.hasRemaining()) {
                    lastFrameFinished = true;
                }
                return copied;
            }
            if (frameFinished) {
                if (stopAtFrame) {
                    lastFrameFinished = true;
                    return 0;
                }
                continue;
            }
            if (inputBuffer.position() == inputPosition) {
                throw new IOException("Zstandard decoder made no progress");
            }
        }
    }

    /// Returns the compressed byte count logically consumed by the native decoder.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the compressed byte count obtained from the source.
    @Override
    public long sourceBytes() {
        return sourceBytes;
    }

    /// Returns a read-only view of compressed bytes not yet consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return inputBuffer.asReadOnlyBuffer();
    }

    /// Returns the uncompressed byte count returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Releases the native context and closes an owned source channel.
    @Override
    public void close() throws IOException {
        @Nullable Throwable failure = null;
        if (open) {
            open = false;
            try {
                context.close();
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
        }
        sourceCloser.closeAfter(failure);
    }

    /// Copies pending direct output into the caller's target buffer.
    private int copyOutput(ByteBuffer target) {
        int count = Math.min(target.remaining(), outputBuffer.remaining());
        ByteBuffer chunk = outputBuffer.slice();
        chunk.limit(count);
        target.put(chunk);
        outputBuffer.position(outputBuffer.position() + count);
        outputBytes += count;
        return count;
    }

    /// Reads another compressed chunk into the owned direct input buffer.
    private boolean readCompressedInput() throws IOException {
        inputBuffer.clear();
        int read = source.read(inputBuffer);
        if (read < 0) {
            inputBuffer.flip();
            return false;
        }
        if (read == 0) {
            throw new IOException("Zstandard source channel made no progress");
        }
        sourceBytes += read;
        inputBuffer.flip();
        return true;
    }

    /// Resets the native session when another frame begins.
    private boolean startNextFrame() throws IOException {
        if (!inputBuffer.hasRemaining() && !readCompressedInput()) {
            return false;
        }
        context.reset();
        frameFinished = false;
        windowValidated = maximumWindowSize < 0L;
        return true;
    }

    /// Reads and validates the current frame's declared decoding window.
    private void validateFrameWindow() throws IOException {
        if (windowValidated) {
            return;
        }
        while (true) {
            long requiredWindowSize = ZstdFrameHeader.requiredWindowSize(inputBuffer);
            if (requiredWindowSize != ZstdFrameHeader.NEED_MORE_INPUT) {
                StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, requiredWindowSize);
                windowValidated = true;
                return;
            }
            inputBuffer.compact();
            if (!inputBuffer.hasRemaining()) {
                throw new IOException("Zstandard frame header exceeds the input buffer");
            }
            int read = source.read(inputBuffer);
            if (read < 0) {
                inputBuffer.flip();
                throw new EOFException("Unexpected end of Zstandard frame header");
            }
            if (read == 0) {
                throw new IOException("Zstandard source channel made no progress");
            }
            sourceBytes += read;
            inputBuffer.flip();
        }
    }

    /// Requires the decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
