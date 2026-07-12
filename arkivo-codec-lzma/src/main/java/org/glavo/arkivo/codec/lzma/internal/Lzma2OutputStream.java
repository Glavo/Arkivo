// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Writes a raw LZMA2 stream using bounded independently compressed chunks.
@NotNullByDefault
public final class Lzma2OutputStream extends OutputStream {
    /// The largest uncompressed block considered by this encoder.
    private static final int BLOCK_SIZE = 1 << 16;

    /// The compressed destination owned by this stream.
    private final OutputStream output;

    /// The LZMA properties used for every compressed block.
    private final LzmaProperties properties;

    /// The pending uncompressed block.
    private final byte[] block = new byte[BLOCK_SIZE];

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// The number of pending block bytes.
    private int blockSize;

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates an LZMA2 encoder with default LZMA model properties.
    public Lzma2OutputStream(OutputStream output, int dictionarySize) {
        this(output, LzmaProperties.defaults(dictionarySize));
    }

    /// Creates an LZMA2 encoder with supplied LZMA model properties.
    public Lzma2OutputStream(OutputStream output, LzmaProperties properties) {
        this.output = Objects.requireNonNull(output, "output");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Writes one uncompressed byte.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Buffers uncompressed bytes and emits complete LZMA2 blocks.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        while (length > 0) {
            int copied = Math.min(length, block.length - blockSize);
            System.arraycopy(bytes, offset, block, blockSize, copied);
            blockSize += copied;
            offset += copied;
            length -= copied;
            if (blockSize == block.length) {
                writeBlock();
            }
        }
    }

    /// Flushes complete emitted chunks while retaining the current partial block.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    /// Writes the final block and end control byte, then closes the destination.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            writeBlock();
            output.write(0);
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            output.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
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
        output.write(encoded);
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

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
