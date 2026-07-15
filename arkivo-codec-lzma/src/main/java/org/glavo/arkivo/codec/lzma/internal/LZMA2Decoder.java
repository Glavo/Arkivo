// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Objects;

/// Incrementally decodes a raw LZMA2 stream without retaining caller-owned buffers.
///
/// Compressed chunk bodies are staged in a bounded internal array before range decoding so the existing symbol decoder
/// never has to retain or block on a caller-owned input buffer. Dictionary and probability state remain continuous
/// across chunks exactly as selected by their control bytes.
@NotNullByDefault
public final class LZMA2Decoder implements CompressionDecoder {
    /// The maximum number of bytes following a chunk control byte before its body.
    private static final int MAXIMUM_HEADER_SIZE = 5;

    /// The shared LZMA dictionary and probability state.
    private final LZMADecoderEngine decoder;

    /// Incrementally collected bytes following the active control byte.
    private final byte[] header = new byte[MAXIMUM_HEADER_SIZE];

    /// Current decoding phase.
    private Phase phase = Phase.CONTROL;

    /// The active LZMA2 control byte.
    private int control;

    /// The number of collected header bytes.
    private int headerSize;

    /// The number of required header bytes.
    private int headerRequired;

    /// The staged compressed body, or null outside compressed-input collection.
    private byte @Nullable [] compressedData;

    /// The number of staged compressed bytes.
    private int compressedOffset;

    /// The active in-memory compressed chunk source, or null.
    private @Nullable LZMAChannelInput compressedChunk;

    /// Remaining output bytes in the active chunk.
    private int chunkRemaining;

    /// Whether the next data chunk must reset the dictionary.
    private boolean dictionaryResetRequired = true;

    /// Whether the next compressed chunk must supply LZMA properties.
    private boolean propertiesRequired = true;

    /// Creates a decoder with the externally declared LZMA2 dictionary size.
    ///
    /// @param dictionarySize dictionary allocation in bytes
    public LZMA2Decoder(int dictionarySize) {
        decoder = new LZMADecoderEngine(dictionarySize);
    }

    /// Decodes source bytes until input, output space, or the LZMA2 end marker stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (phase == Phase.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            switch (phase) {
                case CONTROL -> {
                    if (!source.hasRemaining()) {
                        return inputOutcome(endOfInput);
                    }
                    readControl(Byte.toUnsignedInt(source.get()));
                    if (phase == Phase.FINISHED) {
                        return CodecOutcome.FINISHED;
                    }
                }
                case HEADER -> {
                    copyHeader(source);
                    if (headerSize < headerRequired) {
                        return inputOutcome(endOfInput);
                    }
                    beginChunk();
                }
                case COMPRESSED_INPUT -> {
                    copyCompressedInput(source);
                    byte @Nullable [] selectedData = compressedData;
                    if (selectedData == null || compressedOffset < selectedData.length) {
                        return inputOutcome(endOfInput);
                    }
                    startCompressedChunk(selectedData);
                }
                case COMPRESSED_OUTPUT -> {
                    while (chunkRemaining > 0 && target.hasRemaining()) {
                        int value = decoder.readByte();
                        if (value < 0) {
                            throw new IOException("LZMA2 chunk ended before its declared output size");
                        }
                        target.put((byte) value);
                        chunkRemaining--;
                    }
                    if (chunkRemaining > 0) {
                        return CodecOutcome.NEEDS_OUTPUT;
                    }
                    finishCompressedChunk();
                }
                case UNCOMPRESSED_OUTPUT -> {
                    while (chunkRemaining > 0 && source.hasRemaining() && target.hasRemaining()) {
                        target.put((byte) decoder.putUncompressedByte(Byte.toUnsignedInt(source.get())));
                        chunkRemaining--;
                    }
                    if (chunkRemaining == 0) {
                        phase = Phase.CONTROL;
                    } else if (!target.hasRemaining()) {
                        return CodecOutcome.NEEDS_OUTPUT;
                    } else {
                        return inputOutcome(endOfInput);
                    }
                }
                case FINISHED -> {
                    return CodecOutcome.FINISHED;
                }
                case CLOSED -> throw new IllegalStateException("LZMA2 decoder is closed");
            }
        }
    }

    /// Abandons the current stream and restores its initial empty state.
    @Override
    public void reset() {
        requireOpen();
        decoder.resetDictionary();
        phase = Phase.CONTROL;
        control = 0;
        headerSize = 0;
        headerRequired = 0;
        compressedData = null;
        compressedOffset = 0;
        compressedChunk = null;
        chunkRemaining = 0;
        dictionaryResetRequired = true;
        propertiesRequired = true;
    }

    /// Releases decoder-owned stream state without consuming additional input.
    @Override
    public void close() {
        phase = Phase.CLOSED;
        compressedData = null;
        compressedChunk = null;
        chunkRemaining = 0;
    }

    /// Validates one control byte and selects its header shape.
    private void readControl(int value) throws IOException {
        control = value;
        headerSize = 0;
        if (control == 0) {
            phase = Phase.FINISHED;
            return;
        }
        if (control >= 0x80) {
            if (dictionaryResetRequired && control < 0xe0) {
                throw new IOException("LZMA2 stream does not begin with a dictionary reset");
            }
            headerRequired = control >= 0xc0 ? 5 : 4;
            phase = Phase.HEADER;
            return;
        }
        if (control == 0x01 || control == 0x02) {
            if (dictionaryResetRequired && control != 0x01) {
                throw new IOException("LZMA2 stream does not begin with a dictionary reset");
            }
            headerRequired = 2;
            phase = Phase.HEADER;
            return;
        }
        throw new IOException("Invalid LZMA2 control byte: " + control);
    }

    /// Copies available source bytes into the active fixed-size header.
    private void copyHeader(ByteBuffer source) {
        int copied = Math.min(source.remaining(), headerRequired - headerSize);
        source.get(header, headerSize, copied);
        headerSize += copied;
    }

    /// Applies the completed header and starts body collection or direct stored-byte decoding.
    private void beginChunk() throws IOException {
        if (control >= 0xe0 || control == 0x01) {
            decoder.resetDictionary();
            propertiesRequired = true;
            dictionaryResetRequired = false;
        }

        if (control >= 0x80) {
            chunkRemaining = ((control & 0x1f) << 16) + unsignedShort(header, 0) + 1;
            int compressedSize = unsignedShort(header, 2) + 1;
            if (control >= 0xc0) {
                try {
                    decoder.configure(Byte.toUnsignedInt(header[4]));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid LZMA2 property byte", exception);
                }
                propertiesRequired = false;
            } else if (propertiesRequired) {
                throw new IOException("LZMA2 compressed chunk omits required properties");
            } else if (control >= 0xa0) {
                decoder.resetState();
            }

            compressedData = new byte[compressedSize];
            compressedOffset = 0;
            phase = Phase.COMPRESSED_INPUT;
            return;
        }

        chunkRemaining = unsignedShort(header, 0) + 1;
        phase = Phase.UNCOMPRESSED_OUTPUT;
    }

    /// Copies compressed bytes into the bounded active-chunk staging array.
    private void copyCompressedInput(ByteBuffer source) {
        byte @Nullable [] selectedData = compressedData;
        if (selectedData == null) {
            throw new IllegalStateException("LZMA2 compressed input is not initialized");
        }
        int copied = Math.min(source.remaining(), selectedData.length - compressedOffset);
        source.get(selectedData, compressedOffset, copied);
        compressedOffset += copied;
    }

    /// Starts range decoding after the complete compressed chunk body has been staged.
    private void startCompressedChunk(byte[] encoded) throws IOException {
        LZMAChannelInput input = new LZMAChannelInput(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                Math.max(1, encoded.length)
        );
        compressedChunk = input;
        compressedData = null;
        compressedOffset = 0;
        decoder.startChunk(input, chunkRemaining, false);
        phase = Phase.COMPRESSED_OUTPUT;
    }

    /// Verifies the canonical range-coder termination of a completed compressed chunk.
    private void finishCompressedChunk() throws IOException {
        LZMAChannelInput input = Objects.requireNonNull(compressedChunk, "compressedChunk");
        if (decoder.readByte() >= 0 || !decoder.chunkEnded()) {
            throw new IOException("LZMA2 chunk exceeds its declared output size");
        }
        if (!decoder.rangeFinished() || input.available() != 0) {
            throw new IOException("LZMA2 chunk has a non-canonical range-coder ending");
        }
        compressedChunk = null;
        phase = Phase.CONTROL;
    }

    /// Reads one unsigned big-endian 16-bit value from an array.
    private static int unsignedShort(byte[] values, int offset) {
        return Byte.toUnsignedInt(values[offset]) << 8 | Byte.toUnsignedInt(values[offset + 1]);
    }

    /// Returns an input request or reports a truncated final source.
    private CodecOutcome inputOutcome(boolean endOfInput) throws EOFException {
        if (endOfInput) {
            throw new EOFException("Truncated LZMA2 stream");
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("LZMA2 decoder is closed");
        }
    }

    /// Enumerates the incremental LZMA2 decoding phases.
    @NotNullByDefault
    private enum Phase {
        /// The next byte is a chunk control or stream end marker.
        CONTROL,

        /// Remaining chunk-header bytes are being collected.
        HEADER,

        /// A bounded compressed chunk body is being collected.
        COMPRESSED_INPUT,

        /// A complete compressed chunk is producing output.
        COMPRESSED_OUTPUT,

        /// Stored bytes are being copied through the shared dictionary.
        UNCOMPRESSED_OUTPUT,

        /// The stream end marker has been consumed.
        FINISHED,

        /// The decoder has been closed.
        CLOSED
    }
}
