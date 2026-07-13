// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes a raw LZMA2 stream directly to a channel.
@NotNullByDefault
public final class Lzma2ChannelEncoder implements CompressionEncoder {
    /// The largest uncompressed chunk considered by this encoder.
    private static final int BLOCK_SIZE = 1 << 16;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Tracks closure of the owned compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// The buffered compressed-byte target.
    private final LzmaChannelOutput output;

    /// The LZMA properties used for compressed chunks.
    private final LzmaProperties properties;

    /// The pending uncompressed chunk.
    private final byte[] block = new byte[BLOCK_SIZE];

    /// The number of pending bytes in `block`.
    private int blockSize;

    /// The number of uncompressed bytes accepted.
    private long inputBytes;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates a raw LZMA2 encoder with the requested dictionary size.
    public Lzma2ChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int dictionarySize
    ) {
        this(target, ownership, LzmaProperties.defaults(dictionarySize));
    }

    /// Creates a raw LZMA2 encoder with complete model properties.
    public Lzma2ChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            LzmaProperties properties
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
        output = new LzmaChannelOutput(target);
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Buffers uncompressed bytes and emits complete LZMA2 chunks.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        int start = source.position();
        while (source.hasRemaining()) {
            int copied = Math.min(source.remaining(), block.length - blockSize);
            source.get(block, blockSize, copied);
            blockSize += copied;
            if (blockSize == block.length) {
                writeBlock();
            }
        }
        int count = source.position() - start;
        inputBytes += count;
        return count;
    }

    /// Writes complete compressed chunks already emitted by the encoder.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    /// Writes the final chunk and LZMA2 end control byte.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }
        @Nullable Throwable failure = null;
        try {
            writeBlock();
            output.write(0);
            output.flush();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        targetCloser.closeAfter(failure);

    }

    /// Returns the number of uncompressed bytes accepted.
    @Override
    public long inputBytes() {
        return inputBytes;
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

    /// Compresses and emits the pending block, or stores it when compression has no benefit.
    private void writeBlock() throws IOException {
        if (blockSize == 0) {
            return;
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(blockSize);
        int effectiveDictionarySize = Math.max(
                LzmaProperties.MINIMUM_DICTIONARY_SIZE,
                Math.min(properties.dictionarySize(), blockSize)
        );
        LzmaProperties blockProperties = new LzmaProperties(
                properties.literalContextBits(),
                properties.literalPositionBits(),
                properties.positionBits(),
                effectiveDictionarySize
        );
        try (LzmaOutputStream encoder = new LzmaOutputStream(compressed, blockProperties, false)) {
            encoder.write(block, 0, blockSize);
        }

        byte[] encoded = compressed.toByteArray();
        if (encoded.length <= 1 << 16 && encoded.length + 3 < blockSize) {
            writeCompressedBlock(encoded);
        } else {
            writeUncompressedBlock();
        }
        blockSize = 0;
    }

    /// Writes one property-reset and dictionary-reset compressed chunk.
    private void writeCompressedBlock(byte[] encoded) throws IOException {
        int uncompressedMinusOne = blockSize - 1;
        output.write(0xe0 | uncompressedMinusOne >>> 16);
        writeUnsignedShort(uncompressedMinusOne);
        writeUnsignedShort(encoded.length - 1);
        output.write(properties.propertyByte());
        output.write(encoded, 0, encoded.length);
    }

    /// Writes one dictionary-reset uncompressed chunk.
    private void writeUncompressedBlock() throws IOException {
        output.write(0x01);
        writeUnsignedShort(blockSize - 1);
        output.write(block, 0, blockSize);
    }

    /// Writes an unsigned big-endian 16-bit value.
    private void writeUnsignedShort(int value) throws IOException {
        output.write(value >>> 8 & 0xff);
        output.write(value & 0xff);
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

}
