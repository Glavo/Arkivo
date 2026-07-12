// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.zip.Deflater;

/// Encodes raw deflate data directly between ByteBuffers and a target channel.
@NotNullByDefault
public final class DeflateChannelEncoder implements CompressionEncoder {
    /// The native output staging-buffer size.
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    /// The owned input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Whether this context owns the target channel.
    private final ChannelOwnership ownership;

    /// The JDK raw deflate context.
    private final Deflater deflater;

    /// The direct owned input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The direct output staging buffer.
    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(OUTPUT_BUFFER_SIZE);

    /// The number of uncompressed bytes consumed.
    private long inputBytes;

    /// The number of compressed bytes written.
    private long outputBytes;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates a raw deflate encoder with the requested JDK compression level.
    ///
    /// @param target compressed-data target
    /// @param ownership whether this context closes the target
    /// @param compressionLevel JDK deflate compression level
    public DeflateChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int compressionLevel
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.deflater = new Deflater(compressionLevel, true);
    }

    /// Consumes uncompressed bytes and writes any immediately available compressed output.
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
                deflater.setInput(inputBuffer);
                do {
                    int inputPosition = inputBuffer.position();
                    int produced = deflate(Deflater.NO_FLUSH);
                    if (produced == 0 && inputBuffer.position() == inputPosition) {
                        throw new IOException("Raw deflate encoder made no progress");
                    }
                } while (inputBuffer.hasRemaining());
            }
            return source.position() - start;
        } finally {
            inputBytes += source.position() - start;
        }
    }

    /// Flushes pending compressed data without ending the raw deflate stream.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        while (deflate(Deflater.SYNC_FLUSH) == outputBuffer.capacity()) {
            // Continue until the deflater no longer fills the complete staging buffer.
        }
    }

    /// Finishes the raw deflate stream and releases the native context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            return;
        }

        @Nullable Throwable failure = null;
        try {
            deflater.finish();
            while (!deflater.finished()) {
                if (deflate(Deflater.NO_FLUSH) == 0 && !deflater.finished()) {
                    throw new IOException("Raw deflate encoder could not finish the stream");
                }
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            open = false;
            deflater.end();
            closeOwnedTarget(failure);
        }
    }

    /// Returns the consumed uncompressed byte count.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the written compressed byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether the encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes the encoder context.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Deflates into the staging buffer and writes all produced bytes.
    private int deflate(int flushMode) throws IOException {
        outputBuffer.clear();
        int produced = deflater.deflate(outputBuffer, flushMode);
        outputBuffer.flip();
        while (outputBuffer.hasRemaining()) {
            int written = target.write(outputBuffer);
            if (written == 0) {
                throw new IOException("Raw deflate target channel made no progress");
            }
            outputBytes += written;
        }
        return produced;
    }

    /// Copies one bounded source range into the owned input staging buffer.
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
