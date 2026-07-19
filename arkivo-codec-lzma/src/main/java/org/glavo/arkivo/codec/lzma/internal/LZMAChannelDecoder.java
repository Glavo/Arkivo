// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes an LZMA-alone stream directly from a channel.
@NotNullByDefault
public final class LZMAChannelDecoder implements DecompressingReadableByteChannel {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The buffered compressed-byte source.
    private final LZMAChannelInput input;

    /// The raw LZMA decoder engine.
    private final LZMADecoderEngine decoder;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates an LZMA-alone decoder and consumes its header.
    ///
    /// @param source the channel supplying the LZMA-alone stream
    /// @param ownership whether closing this decoder also closes {@code source}
    /// @throws IOException if the header cannot be read or contains unsupported properties or size metadata
    /// @throws NullPointerException if {@code source} or {@code ownership} is {@code null}
    public LZMAChannelDecoder(ReadableByteChannel source, ResourceOwnership ownership) throws IOException {
        this(source, ownership, CompressionCodec.UNKNOWN_SIZE);
    }

    /// Creates an LZMA-alone decoder with an optional maximum dictionary size.
    ///
    /// @param source the channel supplying the LZMA-alone stream
    /// @param ownership whether closing this decoder also closes {@code source}
    /// @param maximumWindowSize the maximum permitted dictionary size, or a negative value for no limit
    /// @throws IOException if the header cannot be read, is invalid, or declares a dictionary above the limit
    /// @throws NullPointerException if {@code source} or {@code ownership} is {@code null}
    public LZMAChannelDecoder(
            ReadableByteChannel source,
            ResourceOwnership ownership,
            long maximumWindowSize
    ) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        input = new LZMAChannelInput(source, 8192);
        try {
            int propertyByte = readRequiredByte();
            long dictionarySize = readLittleEndian(Integer.BYTES);
            if (dictionarySize > LZMAProperties.MAXIMUM_DICTIONARY_SIZE) {
                throw new IOException("Unsupported LZMA dictionary size: " + dictionarySize);
            }
            long expectedSize = readLittleEndian(Long.BYTES);
            if (expectedSize < -1L) {
                throw new IOException(
                        "Unsupported LZMA uncompressed size: " + Long.toUnsignedString(expectedSize)
                );
            }
            LZMAProperties properties;
            try {
                properties = LZMAProperties.decode(propertyByte, (int) dictionarySize);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid LZMA properties", exception);
            }
            CompressionDecoderSupport.requireWindowSize(maximumWindowSize, properties.dictionarySize());
            decoder = new LZMADecoderEngine(properties.dictionarySize());
            decoder.configure(properties.propertyByte());
            decoder.resetDictionary();
            decoder.startChunk(input, expectedSize, true);
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw exception;
        }
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

    /// Returns the number of compressed bytes obtained from the source.
    @Override
    public long sourceBytes() {
        return input.sourceByteCount();
    }

    /// Returns a read-only view of compressed bytes not yet consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return input.unconsumedInput();
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
        open = false;
        sourceCloser.close();
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
