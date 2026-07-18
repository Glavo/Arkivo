// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes a headerless LZMA range-coded stream directly to a channel.
@NotNullByDefault
public final class LZMARawChannelEncoder implements CompressingWritableByteChannel {
    /// Tracks closure of the compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// The buffered compressed-byte target.
    private final LZMAChannelOutput output;

    /// The raw LZMA encoder engine.
    private final LZMAEncoderEngine encoder;

    /// The exact expected input size, or `CompressionCodec.UNKNOWN_SIZE` when not pledged.
    private final long expectedSize;

    /// Whether finalization writes the reserved LZMA end marker.
    private final boolean endMarker;

    /// The reusable input transfer buffer.
    private final byte[] transferBuffer = new byte[8192];

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates a raw LZMA encoder with explicit model and termination parameters.
    ///
    /// @param target the channel receiving the headerless range-coded stream
    /// @param ownership whether finishing or closing this encoder also closes {@code target}
    /// @param properties the externally supplied model and dictionary properties
    /// @param expectedSize the exact input size, or {@link CompressionCodec#UNKNOWN_SIZE}
    /// @param endMarker whether finalization emits the reserved LZMA end marker
    /// @throws NullPointerException if {@code target}, {@code ownership}, or {@code properties} is {@code null}
    /// @throws IllegalArgumentException if {@code expectedSize} is less than {@link CompressionCodec#UNKNOWN_SIZE}
    public LZMARawChannelEncoder(
            WritableByteChannel target,
            ResourceOwnership ownership,
            LZMAProperties properties,
            long expectedSize,
            boolean endMarker
    ) {
        if (expectedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        Objects.requireNonNull(target, "target");
        targetCloser = new OwnedChannelCloser(target, ownership);
        this.expectedSize = expectedSize;
        this.endMarker = endMarker;
        output = new LZMAChannelOutput(target);
        encoder = new LZMAEncoderEngine(output, Objects.requireNonNull(properties, "properties"));
    }

    /// Consumes uncompressed bytes from the source buffer.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (expectedSize >= 0L && source.remaining() > expectedSize - encoder.inputSize()) {
            throw new IOException("LZMA input exceeds the pledged source size");
        }
        int start = source.position();
        while (source.hasRemaining()) {
            int count = Math.min(source.remaining(), transferBuffer.length);
            source.get(transferBuffer, 0, count);
            encoder.write(transferBuffer, 0, count);
        }
        return source.position() - start;
    }

    /// Writes currently staged complete range-coded bytes to the target.
    ///
    /// @throws IOException if the encoder is closed or the target cannot accept the staged bytes
    public void flush() throws IOException {
        ensureOpen();
        encoder.flush();
        output.flush();
    }

    /// Finalizes the range coder and closes an owned target.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }
        @Nullable Throwable failure = null;
        try {
            if (expectedSize >= 0L && encoder.inputSize() != expectedSize) {
                throw new IOException("LZMA input does not match the pledged source size");
            }
            encoder.finish(endMarker);
            output.flush();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        targetCloser.closeAfter(failure);
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

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
