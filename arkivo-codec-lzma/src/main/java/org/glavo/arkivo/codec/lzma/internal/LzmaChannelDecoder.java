// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes an LZMA-alone stream directly from a channel.
@NotNullByDefault
public final class LzmaChannelDecoder implements CompressionDecoder {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Whether this context owns the source.
    private final ChannelOwnership ownership;

    /// The buffered compressed-byte source.
    private final LzmaChannelInput input;

    /// The raw LZMA decoder engine.
    private final LzmaDecoderEngine decoder;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates an LZMA-alone decoder and consumes its header.
    public LzmaChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        input = new LzmaChannelInput(source, 8192);
        int propertyByte = readRequiredByte();
        long dictionarySize = readLittleEndian(Integer.BYTES);
        if (dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IOException("Unsupported LZMA dictionary size: " + dictionarySize);
        }
        long expectedSize = readLittleEndian(Long.BYTES);
        LzmaProperties properties;
        try {
            properties = LzmaProperties.decode(propertyByte, (int) dictionarySize);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid LZMA properties", exception);
        }
        decoder = new LzmaDecoderEngine(properties.dictionarySize());
        decoder.configure(properties.propertyByte());
        decoder.resetDictionary();
        decoder.startChunk(input, expectedSize, expectedSize < 0L);
    }

    /// Reads uncompressed bytes into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        int start = target.position();
        while (target.hasRemaining()) {
            int value = decoder.readByte();
            if (value < 0) {
                break;
            }
            target.put((byte) value);
        }
        int count = target.position() - start;
        outputBytes += count;
        return count == 0 ? -1 : count;
    }

    /// Returns the number of logical compressed bytes consumed.
    @Override
    public long inputBytes() {
        return input.byteCount();
    }

    /// Returns the number of uncompressed bytes returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this decoder and an owned source channel.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        if (ownership == ChannelOwnership.CLOSE) {
            source.close();
        }
    }

    /// Reads one required LZMA-alone header byte.
    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated LZMA-alone header");
        }
        return value;
    }

    /// Reads an unsigned little-endian header value.
    private long readLittleEndian(int length) throws IOException {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) readRequiredByte() << (index * 8);
        }
        return value;
    }

    /// Requires this decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
