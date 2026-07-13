// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes an EOS-terminated LZMA-alone stream directly to a channel.
@NotNullByDefault
public final class LZMAChannelEncoder implements CompressionEncoder {
    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Tracks closure of the owned compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// The buffered compressed-byte target.
    private final LZMAChannelOutput output;

    /// The raw LZMA encoder engine.
    private final LZMAEncoderEngine encoder;

    /// The exact expected input size, or `CompressionCodec.UNKNOWN_SIZE` when not pledged.
    private final long expectedSize;

    /// The reusable input transfer buffer.
    private final byte[] transferBuffer = new byte[8192];

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an EOS-terminated LZMA-alone encoder with the requested dictionary size.
    public LZMAChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int dictionarySize
    ) throws IOException {
        this(
                target,
                ownership,
                LZMAProperties.defaults(dictionarySize),
                CompressionCodec.UNKNOWN_SIZE
        );
    }

    /// Creates an LZMA-alone encoder with complete model properties and an optional exact input size.
    public LZMAChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            LZMAProperties properties,
            long expectedSize
    ) throws IOException {
        if (expectedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        this.target = Objects.requireNonNull(target, "target");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
        this.expectedSize = expectedSize;
        output = new LZMAChannelOutput(target);
        try {
            writeHeader(Objects.requireNonNull(properties, "properties"), expectedSize);
            encoder = new LZMAEncoderEngine(output, properties);
        } catch (IOException | RuntimeException | Error exception) {
            targetCloser.closeAfter(exception);
            throw exception;
        }
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
    @Override
    public void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    /// Writes the LZMA end marker and closes this encoder context.
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
            encoder.finish(expectedSize < 0L);
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

    /// Writes the 13-byte LZMA-alone header with the configured uncompressed size.
    private void writeHeader(LZMAProperties properties, long uncompressedSize) throws IOException {
        output.write(properties.propertyByte());
        writeLittleEndian(properties.dictionarySize(), Integer.BYTES);
        writeLittleEndian(uncompressedSize, Long.BYTES);
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

}
