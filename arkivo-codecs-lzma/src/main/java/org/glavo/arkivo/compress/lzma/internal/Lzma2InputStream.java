// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Decodes a raw LZMA2 stream with strict chunk reset and size validation.
@NotNullByDefault
public final class Lzma2InputStream extends InputStream {
    /// The largest compressed LZMA2 chunk size.
    private static final int MAXIMUM_COMPRESSED_SIZE = 1 << 16;

    /// The compressed source owned by this stream.
    private final InputStream input;

    /// The shared LZMA state and dictionary across chunks.
    private final LzmaDecoderEngine decoder;

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// The active compressed chunk buffer, or `null` for uncompressed chunks.
    private @Nullable ByteArrayInputStream compressedChunk;

    /// Remaining output bytes in the active chunk.
    private int chunkRemaining;

    /// Whether the active chunk contains range-coded LZMA data.
    private boolean compressed;

    /// Whether the next data chunk must reset the dictionary.
    private boolean dictionaryResetRequired = true;

    /// Whether the next compressed chunk must supply LZMA properties.
    private boolean propertiesRequired = true;

    /// Whether the stream end control byte has been read.
    private boolean endReached;

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates a raw LZMA2 decoder with the externally declared dictionary size.
    public Lzma2InputStream(InputStream input, int dictionarySize) {
        this.input = Objects.requireNonNull(input, "input");
        decoder = new LzmaDecoderEngine(dictionarySize);
    }

    /// Reads one uncompressed byte.
    @Override
    public int read() throws IOException {
        int count = read(singleByte, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    /// Reads uncompressed bytes across any number of LZMA2 chunks.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        int total = 0;
        while (total < length) {
            if (chunkRemaining == 0) {
                finishCompressedChunk();
                if (endReached) {
                    return total == 0 ? -1 : total;
                }
                readChunkHeader();
                if (endReached) {
                    return total == 0 ? -1 : total;
                }
            }

            if (compressed) {
                int value = decoder.readByte();
                if (value < 0) {
                    throw new IOException("LZMA2 chunk ended before its declared output size");
                }
                bytes[offset + total] = (byte) value;
                total++;
                chunkRemaining--;
            } else {
                int copied = Math.min(length - total, chunkRemaining);
                for (int index = 0; index < copied; index++) {
                    int value = readRequiredByte();
                    bytes[offset + total + index] = (byte) decoder.putUncompressedByte(value);
                }
                total += copied;
                chunkRemaining -= copied;
            }
        }
        return total;
    }

    /// Returns bytes remaining in the active chunk without parsing another chunk header.
    @Override
    public int available() throws IOException {
        ensureOpen();
        return compressed ? chunkRemaining : Math.min(chunkRemaining, input.available());
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

    /// Parses and applies the next LZMA2 chunk header.
    private void readChunkHeader() throws IOException {
        int control = readRequiredByte();
        if (control == 0) {
            endReached = true;
            return;
        }

        if (control >= 0xe0 || control == 0x01) {
            decoder.resetDictionary();
            propertiesRequired = true;
            dictionaryResetRequired = false;
        } else if (dictionaryResetRequired) {
            throw new IOException("LZMA2 stream does not begin with a dictionary reset");
        }

        if (control >= 0x80) {
            compressed = true;
            chunkRemaining = ((control & 0x1f) << 16) + readUnsignedShort() + 1;
            int compressedSize = readUnsignedShort() + 1;
            if (compressedSize > MAXIMUM_COMPRESSED_SIZE) {
                throw new IOException("Invalid LZMA2 compressed chunk size");
            }

            if (control >= 0xc0) {
                int property = readRequiredByte();
                try {
                    decoder.configure(property);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid LZMA2 property byte", exception);
                }
                propertiesRequired = false;
            } else if (propertiesRequired) {
                throw new IOException("LZMA2 compressed chunk omits required properties");
            } else if (control >= 0xa0) {
                decoder.resetState();
            }

            byte[] encoded = readRequiredBytes(compressedSize);
            compressedChunk = new ByteArrayInputStream(encoded);
            decoder.startChunk(compressedChunk, chunkRemaining, false);
            return;
        }

        if (control > 0x02) {
            throw new IOException("Invalid LZMA2 control byte: " + control);
        }
        compressed = false;
        compressedChunk = null;
        chunkRemaining = readUnsignedShort() + 1;
    }

    /// Verifies the canonical range-coder termination of a completed compressed chunk.
    private void finishCompressedChunk() throws IOException {
        if (!compressed || compressedChunk == null) {
            return;
        }
        if (decoder.readByte() >= 0 || !decoder.chunkEnded()) {
            throw new IOException("LZMA2 chunk exceeds its declared output size");
        }
        if (!decoder.rangeFinished() || compressedChunk.available() != 0) {
            throw new IOException("LZMA2 chunk has a non-canonical range-coder ending");
        }
        compressed = false;
        compressedChunk = null;
    }

    /// Reads an unsigned big-endian 16-bit value.
    private int readUnsignedShort() throws IOException {
        return readRequiredByte() << 8 | readRequiredByte();
    }

    /// Reads an exact compressed chunk body.
    private byte[] readRequiredBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Truncated LZMA2 compressed chunk");
            }
            if (count == 0) {
                int value = readRequiredByte();
                bytes[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
        return bytes;
    }

    /// Reads one required stream byte.
    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated LZMA2 stream");
        }
        return value;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
