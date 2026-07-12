// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Decodes either an LZMA-alone stream or a raw LZMA range-coded stream with supplied properties.
@NotNullByDefault
public final class LzmaInputStream extends InputStream {
    /// The compressed source owned by this stream.
    private final InputStream input;

    /// The raw LZMA decoder engine.
    private final LzmaDecoderEngine decoder;

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates a decoder and reads the 13-byte LZMA-alone header.
    public LzmaInputStream(InputStream input) throws IOException {
        this(Objects.requireNonNull(input, "input"), readHeader(input));
    }

    /// Creates a raw LZMA decoder with supplied size and properties.
    public LzmaInputStream(
            InputStream input,
            long expectedSize,
            int propertyByte,
            int dictionarySize
    ) throws IOException {
        this.input = Objects.requireNonNull(input, "input");
        LzmaProperties properties;
        try {
            properties = LzmaProperties.decode(propertyByte, dictionarySize);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid LZMA properties", exception);
        }
        decoder = new LzmaDecoderEngine(properties.dictionarySize());
        decoder.configure(properties.propertyByte());
        decoder.resetDictionary();
        decoder.startChunk(input, expectedSize, expectedSize < 0L);
    }

    /// Creates an LZMA-alone decoder from its parsed header.
    private LzmaInputStream(InputStream input, Header header) throws IOException {
        this(input, header.expectedSize(), header.propertyByte(), header.dictionarySize());
    }

    /// Reads one decoded byte.
    @Override
    public int read() throws IOException {
        ensureOpen();
        return decoder.readByte();
    }

    /// Reads decoded bytes into the destination array.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        int count = 0;
        while (count < length) {
            int value = decoder.readByte();
            if (value < 0) {
                return count == 0 ? -1 : count;
            }
            bytes[offset + count] = (byte) value;
            count++;
        }
        return count;
    }

    /// Skips decoded bytes while preserving range and dictionary state.
    @Override
    public long skip(long count) throws IOException {
        ensureOpen();
        if (count <= 0L) {
            return 0L;
        }
        long skipped = 0L;
        while (skipped < count) {
            int read = read(singleByte, 0, 1);
            if (read < 0) {
                break;
            }
            skipped++;
        }
        return skipped;
    }

    /// Returns pending dictionary-copy bytes immediately available without parsing another symbol.
    @Override
    public int available() throws IOException {
        ensureOpen();
        return decoder.available();
    }

    /// Closes the compressed source.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        input.close();
    }

    /// Reads the packed properties, dictionary size, and expected output size from an LZMA-alone header.
    private static Header readHeader(InputStream input) throws IOException {
        int propertyByte = readRequiredByte(input);
        long dictionarySize = readLittleEndian(input, 4);
        if (dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IOException("Unsupported LZMA dictionary size: " + dictionarySize);
        }
        long expectedSize = readLittleEndian(input, 8);
        return new Header(propertyByte, (int) dictionarySize, expectedSize);
    }

    /// Reads an unsigned little-endian value with up to eight bytes.
    private static long readLittleEndian(InputStream input, int length) throws IOException {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) readRequiredByte(input) << (index * 8);
        }
        return value;
    }

    /// Reads one required header byte.
    private static int readRequiredByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated LZMA-alone header");
        }
        return value;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Stores one parsed LZMA-alone header.
    ///
    /// @param propertyByte   the packed LZMA property byte
    /// @param dictionarySize the declared dictionary size
    /// @param expectedSize   the expected output size, or `-1` for EOS termination
    private record Header(int propertyByte, int dictionarySize, long expectedSize) {
    }
}
