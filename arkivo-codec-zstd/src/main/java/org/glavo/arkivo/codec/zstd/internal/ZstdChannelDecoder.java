// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDirectBufferDecompressingStreamNoFinalizer;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

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

    /// Whether this context owns the source channel.
    private final ChannelOwnership ownership;

    /// The configured native Zstandard decompression context.
    private final ZstdDecompressCtx context;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The direct decoded-output staging buffer.
    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(
            ZstdDirectBufferDecompressingStreamNoFinalizer.recommendedTargetBufferSize()
    );

    /// The number of compressed bytes read from the source.
    private long inputBytes;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether the current frame has completed.
    private boolean frameFinished;

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
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.context = Objects.requireNonNull(context, "context");
        inputBuffer.limit(0);
        outputBuffer.limit(0);
    }

    /// Reads decoded bytes into the caller's target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (outputBuffer.hasRemaining()) {
            return copyOutput(target);
        }
        if (frameFinished) {
            return -1;
        }

        while (true) {
            if (!inputBuffer.hasRemaining() && !readCompressedInput()) {
                throw new EOFException("Unexpected end of Zstandard frame");
            }

            outputBuffer.clear();
            int inputPosition = inputBuffer.position();
            frameFinished = context.decompressDirectByteBufferStream(outputBuffer, inputBuffer);
            outputBuffer.flip();
            if (outputBuffer.hasRemaining()) {
                return copyOutput(target);
            }
            if (frameFinished) {
                return -1;
            }
            if (inputBuffer.position() == inputPosition) {
                throw new IOException("Zstandard decoder made no progress");
            }
        }
    }

    /// Returns the compressed byte count read from the source.
    @Override
    public long inputBytes() {
        return inputBytes;
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
        if (!open) {
            return;
        }
        open = false;
        context.close();
        if (ownership == ChannelOwnership.CLOSE) {
            source.close();
        }
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
            return false;
        }
        if (read == 0) {
            throw new IOException("Zstandard source channel made no progress");
        }
        inputBytes += read;
        inputBuffer.flip();
        return true;
    }

    /// Requires the decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
