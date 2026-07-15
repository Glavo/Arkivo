// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes an LZMA-alone stream over the shared raw buffer engine.
@NotNullByDefault
public final class LZMADecoder implements CompressionDecoder {
    /// Number of bytes in an LZMA-alone header.
    private static final int HEADER_SIZE = 13;

    /// Maximum dictionary allocation accepted for this decoder.
    private final long maximumWindowSize;

    /// Incrementally collected LZMA-alone header.
    private final byte[] header = new byte[HEADER_SIZE];

    /// Shared raw stream decoder after header validation, or null beforehand.
    private @Nullable LZMARawDecoder rawDecoder;

    /// Number of collected header bytes.
    private int headerSize;

    /// Whether the complete stream has finished.
    private boolean finished;

    /// Whether this wrapper has closed.
    private boolean closed;

    /// Creates an LZMA-alone decoder with an optional dictionary allocation limit.
    ///
    /// @param maximumWindowSize maximum permitted dictionary size
    public LZMADecoder(long maximumWindowSize) {
        this.maximumWindowSize = maximumWindowSize;
    }

    /// Collects the header and delegates the range-coded payload to the raw engine.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (finished) {
            return CodecOutcome.FINISHED;
        }
        if (!target.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        collectHeader(source);
        if (headerSize < header.length) {
            if (endOfInput) {
                throw new EOFException("Truncated LZMA-alone header");
            }
            return CodecOutcome.NEEDS_INPUT;
        }

        LZMARawDecoder decoder = initializeRawDecoder();
        CodecOutcome outcome = decoder.decode(source, target, endOfInput);
        if (outcome == CodecOutcome.FINISHED) {
            finished = true;
        }
        return outcome;
    }

    /// Abandons the current stream and restores empty header collection.
    @Override
    public void reset() {
        requireOpen();
        @Nullable LZMARawDecoder decoder = rawDecoder;
        if (decoder != null) {
            decoder.close();
        }
        rawDecoder = null;
        headerSize = 0;
        finished = false;
    }

    /// Releases header and raw decoder state without consuming more input.
    @Override
    public void close() {
        closed = true;
        @Nullable LZMARawDecoder decoder = rawDecoder;
        if (decoder != null) {
            decoder.close();
        }
        rawDecoder = null;
        headerSize = 0;
    }

    /// Copies only the fixed LZMA-alone header from caller input.
    private void collectHeader(ByteBuffer source) {
        int length = Math.min(source.remaining(), header.length - headerSize);
        source.get(header, headerSize, length);
        headerSize += length;
    }

    /// Parses the completed header and creates the shared raw decoder.
    private LZMARawDecoder initializeRawDecoder() throws IOException {
        @Nullable LZMARawDecoder current = rawDecoder;
        if (current != null) {
            return current;
        }

        int propertyByte = Byte.toUnsignedInt(header[0]);
        long dictionarySize = readLittleEndian(1, Integer.BYTES);
        if (dictionarySize > LZMAProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IOException("Unsupported LZMA dictionary size: " + dictionarySize);
        }
        long expectedSize = readLittleEndian(5, Long.BYTES);
        if (expectedSize < CompressionCodec.UNKNOWN_SIZE) {
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
        current = new LZMARawDecoder(properties, expectedSize, maximumWindowSize);
        rawDecoder = current;
        return current;
    }

    /// Reads one unsigned little-endian header value.
    private long readLittleEndian(int offset, int length) {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) Byte.toUnsignedInt(header[offset + index]) << (index * 8);
        }
        return value;
    }

    /// Requires this wrapper to remain open.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("LZMA decoder is closed");
        }
    }
}
