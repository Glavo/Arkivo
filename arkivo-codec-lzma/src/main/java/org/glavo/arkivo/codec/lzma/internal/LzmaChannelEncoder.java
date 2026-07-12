// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes an EOS-terminated LZMA-alone stream directly to a channel.
@NotNullByDefault
public final class LzmaChannelEncoder implements CompressionEncoder {
    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Whether this context owns the target.
    private final ChannelOwnership ownership;

    /// The buffered compressed-byte target.
    private final LzmaChannelOutput output;

    /// The raw LZMA encoder engine.
    private final LzmaEncoderEngine encoder;

    /// The reusable input transfer buffer.
    private final byte[] transferBuffer = new byte[8192];

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an LZMA-alone encoder with the requested dictionary size.
    public LzmaChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int dictionarySize
    ) throws IOException {
        this.target = Objects.requireNonNull(target, "target");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        LzmaProperties properties = LzmaProperties.defaults(dictionarySize);
        output = new LzmaChannelOutput(target);
        writeHeader(properties);
        encoder = new LzmaEncoderEngine(output, properties);
    }

    /// Consumes uncompressed bytes from the source buffer.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        int start = source.position();
        while (source.hasRemaining()) {
            int count = Math.min(source.remaining(), transferBuffer.length);
            source.get(transferBuffer, 0, count);
            encoder.write(transferBuffer, 0, count);
        }
        return source.position() - start;
    }

    /// Writes currently staged complete range-coded bytes to the target.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    /// Writes the LZMA end marker and closes this encoder context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            encoder.finish(true);
            output.flush();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        if (ownership == ChannelOwnership.CLOSE) {
            try {
                target.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        throwFailure(failure);
    }

    /// Returns the number of uncompressed bytes accepted by the encoder.
    @Override
    public long inputBytes() {
        return encoder.inputSize();
    }

    /// Returns the number of compressed bytes written to the target.
    @Override
    public long outputBytes() {
        return output.byteCount();
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes this encoder context.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Writes the 13-byte LZMA-alone header with an unknown uncompressed size.
    private void writeHeader(LzmaProperties properties) throws IOException {
        output.write(properties.propertyByte());
        writeLittleEndian(properties.dictionarySize(), Integer.BYTES);
        writeLittleEndian(-1L, Long.BYTES);
    }

    /// Writes a fixed-width little-endian integer.
    private void writeLittleEndian(long value, int length) throws IOException {
        for (int index = 0; index < length; index++) {
            output.write((int) (value >>> (index * 8)) & 0xff);
        }
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows a close-time failure with its original type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }
}
