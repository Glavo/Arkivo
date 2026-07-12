// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes Zstandard frames through a native direct-ByteBuffer context.
@NotNullByDefault
public final class ZstdChannelEncoder implements CompressionEncoder {
    /// The owned uncompressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 128 * 1024;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Whether this context owns the target channel.
    private final ChannelOwnership ownership;

    /// The configured native Zstandard compression context.
    private final ZstdCompressCtx context;

    /// The direct owned input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The direct native output staging buffer.
    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(
            ZstdDirectBufferCompressingStreamNoFinalizer.recommendedOutputBufferSize()
    );

    /// The number of uncompressed bytes consumed.
    private long inputBytes;

    /// The number of compressed bytes written.
    private long outputBytes;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an encoder that owns the configured native context.
    ///
    /// @param target compressed-data target
    /// @param ownership whether this context closes the target
    /// @param context configured native compression context
    public ZstdChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            ZstdCompressCtx context
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.context = Objects.requireNonNull(context, "context");
    }

    /// Consumes uncompressed bytes through the native streaming context.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }

        int start = source.position();
        try {
            while (source.hasRemaining()) {
                stageInput(source);
                while (inputBuffer.hasRemaining()) {
                    int inputPosition = inputBuffer.position();
                    long outputBefore = outputBytes;
                    process(EndDirective.CONTINUE);
                    if (inputBuffer.position() == inputPosition && outputBytes == outputBefore) {
                        throw new IOException("Zstandard encoder made no progress");
                    }
                }
            }
            return source.position() - start;
        } finally {
            inputBytes += source.position() - start;
        }
    }

    /// Flushes pending compressed output without ending the frame.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        inputBuffer.clear();
        inputBuffer.limit(0);
        while (!process(EndDirective.FLUSH)) {
            // Continue until the native context reports a completed flush.
        }
    }

    /// Finishes the frame and releases the native context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            return;
        }

        @Nullable Throwable failure = null;
        try {
            inputBuffer.clear();
            inputBuffer.limit(0);
            while (!process(EndDirective.END)) {
                // Continue until the complete frame epilogue is written.
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            open = false;
            context.close();
            closeOwnedTarget(failure);
        }
    }

    /// Returns the consumed uncompressed byte count.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the emitted compressed byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes the encoder.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Processes one native streaming operation and drains produced output.
    private boolean process(EndDirective directive) throws IOException {
        outputBuffer.clear();
        boolean complete = context.compressDirectByteBufferStream(outputBuffer, inputBuffer, directive);
        outputBuffer.flip();
        while (outputBuffer.hasRemaining()) {
            int written = target.write(outputBuffer);
            if (written == 0) {
                throw new IOException("Zstandard target channel made no progress");
            }
            outputBytes += written;
        }
        return complete;
    }

    /// Copies one bounded source range into the owned direct input buffer.
    private void stageInput(ByteBuffer source) {
        inputBuffer.clear();
        int count = Math.min(source.remaining(), inputBuffer.capacity());
        ByteBuffer chunk = source.slice();
        chunk.limit(count);
        inputBuffer.put(chunk);
        source.position(source.position() + count);
        inputBuffer.flip();
    }

    /// Closes the target when this context owns it without hiding an earlier failure.
    private void closeOwnedTarget(@Nullable Throwable failure) throws IOException {
        if (ownership != ChannelOwnership.CLOSE) {
            return;
        }
        try {
            target.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }

    /// Requires the encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
