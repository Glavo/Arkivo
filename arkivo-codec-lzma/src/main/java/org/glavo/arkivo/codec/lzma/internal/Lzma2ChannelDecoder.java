// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes a raw LZMA2 stream directly from a channel.
@NotNullByDefault
public final class Lzma2ChannelDecoder implements CompressionDecoder {
    /// The largest compressed LZMA2 chunk size.
    private static final int MAXIMUM_COMPRESSED_SIZE = 1 << 16;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Whether this context owns the source.
    private final ChannelOwnership ownership;

    /// The buffered compressed-byte source.
    private final LzmaChannelInput input;

    /// The shared LZMA state and dictionary across chunks.
    private final LzmaDecoderEngine decoder;

    /// The active compressed chunk source.
    private @Nullable LzmaChannelInput compressedChunk;

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

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a raw LZMA2 decoder with the externally declared dictionary size.
    public Lzma2ChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            int dictionarySize
    ) {
        this(source, ownership, dictionarySize, 8192);
    }

    /// Creates a raw LZMA2 decoder with an explicit compressed-input buffer size.
    public Lzma2ChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            int dictionarySize,
            int inputBufferSize
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        input = new LzmaChannelInput(source, inputBufferSize);
        decoder = new LzmaDecoderEngine(dictionarySize);
    }

    /// Reads uncompressed bytes across LZMA2 chunk boundaries.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        int start = target.position();
        while (target.hasRemaining()) {
            if (chunkRemaining == 0) {
                finishCompressedChunk();
                if (endReached) {
                    break;
                }
                readChunkHeader();
                if (endReached) {
                    break;
                }
            }

            if (compressed) {
                int value = decoder.readByte();
                if (value < 0) {
                    throw new IOException("LZMA2 chunk ended before its declared output size");
                }
                target.put((byte) value);
                chunkRemaining--;
            } else {
                int copied = Math.min(target.remaining(), chunkRemaining);
                for (int index = 0; index < copied; index++) {
                    target.put((byte) decoder.putUncompressedByte(readRequiredByte()));
                }
                chunkRemaining -= copied;
            }
        }
        int count = target.position() - start;
        outputBytes += count;
        return count == 0 && endReached ? -1 : count;
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
            compressedChunk = new LzmaChannelInput(
                    Channels.newChannel(new ByteArrayInputStream(encoded)),
                    Math.max(1, encoded.length)
            );
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

    /// Verifies the canonical range-coder termination of a compressed chunk.
    private void finishCompressedChunk() throws IOException {
        LzmaChannelInput chunk = compressedChunk;
        if (!compressed || chunk == null) {
            return;
        }
        if (decoder.readByte() >= 0 || !decoder.chunkEnded()) {
            throw new IOException("LZMA2 chunk exceeds its declared output size");
        }
        if (!decoder.rangeFinished() || chunk.available() != 0) {
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
            offset += count;
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

    /// Requires this decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
