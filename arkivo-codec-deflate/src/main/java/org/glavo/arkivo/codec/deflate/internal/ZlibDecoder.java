// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.Adler32;

/// Incrementally decodes and validates one zlib stream with explicit preset-dictionary negotiation.
@NotNullByDefault
public final class ZlibDecoder implements CompressionDecoder.DictionaryAware {
    /// Zlib flag indicating a four-byte preset-dictionary identifier.
    private static final int FLAG_PRESET_DICTIONARY = 0x20;

    /// Maximum permitted declared zlib window size, or the unknown sentinel.
    private final long maximumWindowSize;

    /// Configured preset dictionary applied automatically when requested, or null.
    private final @Nullable CompressionDictionary configuredDictionary;

    /// Mutable storage for the two-byte zlib header.
    private final byte[] header = new byte[2];

    /// Mutable storage for the four-byte preset-dictionary identifier.
    private final byte[] dictionaryIdentifier = new byte[4];

    /// Mutable storage for the four-byte stream checksum trailer.
    private final byte[] trailer = new byte[4];

    /// Adler-32 checksum of decoded stream content.
    private final Adler32 checksum = new Adler32();

    /// Current raw Deflate body decoder, or null before header and dictionary processing completes.
    private @Nullable DeflateDecoderEngine body;

    /// Current decoder lifecycle state.
    private State state = State.HEADER;

    /// Number of zlib header bytes already copied into owned storage.
    private int headerBytes;

    /// Number of dictionary-identifier bytes already copied into owned storage.
    private int dictionaryIdentifierBytes;

    /// Number of trailer bytes already copied into owned storage.
    private int trailerBytes;

    /// Window size declared by the current zlib header.
    private int declaredWindowSize;

    /// Dictionary identifier requested by the current stream, or the unknown sentinel.
    private long requiredDictionaryId = CompressionDictionary.UNKNOWN_ID;

    /// Creates a zlib decoder with optional window and preset-dictionary configuration.
    ///
    /// @param maximumWindowSize maximum permitted declared window size, or CompressionCodec.UNKNOWN_SIZE
    /// @param dictionary preset dictionary applied automatically when requested, or null
    public ZlibDecoder(long maximumWindowSize, @Nullable CompressionDictionary dictionary) {
        this.maximumWindowSize = maximumWindowSize;
        this.configuredDictionary = dictionary;
    }

    /// Decodes source bytes until input, output space, a dictionary, or the stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.NEEDS_DICTIONARY) {
            return CodecOutcome.NEEDS_DICTIONARY;
        }

        while (true) {
            if (state == State.HEADER) {
                headerBytes += copyOwned(source, header, headerBytes);
                if (headerBytes < header.length) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of zlib header");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                validateHeader();
                if ((Byte.toUnsignedInt(header[1]) & FLAG_PRESET_DICTIONARY) != 0) {
                    state = State.DICTIONARY_IDENTIFIER;
                } else {
                    beginBody(null);
                }
                continue;
            }

            if (state == State.DICTIONARY_IDENTIFIER) {
                dictionaryIdentifierBytes += copyOwned(
                        source,
                        dictionaryIdentifier,
                        dictionaryIdentifierBytes
                );
                if (dictionaryIdentifierBytes < dictionaryIdentifier.length) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of zlib dictionary identifier");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                requiredDictionaryId = bigEndianUnsignedInt(dictionaryIdentifier);
                @Nullable CompressionDictionary selectedDictionary = configuredDictionary;
                if (selectedDictionary == null) {
                    state = State.NEEDS_DICTIONARY;
                    return CodecOutcome.NEEDS_DICTIONARY;
                }
                applyDictionary(selectedDictionary);
                continue;
            }

            if (state == State.BODY) {
                DeflateDecoderEngine selectedBody = Objects.requireNonNull(body, "body");
                int targetPosition = target.position();
                CodecOutcome outcome;
                try {
                    outcome = selectedBody.decode(source, target, endOfInput);
                } catch (EOFException exception) {
                    EOFException translated = new EOFException("Unexpected end of zlib stream");
                    translated.initCause(exception);
                    throw translated;
                } catch (IOException exception) {
                    throw new IOException("Invalid zlib stream", exception);
                }
                updateContentChecksum(target, targetPosition);
                if (outcome == CodecOutcome.FINISHED) {
                    state = State.TRAILER;
                    continue;
                }
                return outcome;
            }

            if (state == State.TRAILER) {
                trailerBytes += copyOwned(source, trailer, trailerBytes);
                if (trailerBytes < trailer.length) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of zlib stream trailer");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                if (bigEndianUnsignedInt(trailer) != checksum.getValue()) {
                    throw new IOException("Zlib stream checksum mismatch");
                }
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }

            throw new AssertionError("Unexpected zlib decoder state: " + state);
        }
    }

    /// Returns the Adler-32 identifier requested by the current zlib stream.
    @Override
    public long requiredDictionaryId() {
        return state == State.NEEDS_DICTIONARY
                ? requiredDictionaryId
                : CompressionDictionary.UNKNOWN_ID;
    }

    /// Supplies the dictionary requested by the current zlib stream.
    @Override
    public void provideDictionary(CompressionDictionary dictionary) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        requireOpen();
        if (state != State.NEEDS_DICTIONARY) {
            throw new IllegalStateException("Zlib decoder is not waiting for a dictionary");
        }
        applyDictionary(dictionary);
    }

    /// Abandons the current stream and restores the configured zlib decoder state.
    @Override
    public void reset() {
        requireOpen();
        @Nullable DeflateDecoderEngine selectedBody = body;
        if (selectedBody != null) {
            selectedBody.close();
        }
        body = null;
        checksum.reset();
        headerBytes = 0;
        dictionaryIdentifierBytes = 0;
        trailerBytes = 0;
        declaredWindowSize = 0;
        requiredDictionaryId = CompressionDictionary.UNKNOWN_ID;
        state = State.HEADER;
    }

    /// Releases decoder-owned state without consuming additional input.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            @Nullable DeflateDecoderEngine selectedBody = body;
            if (selectedBody != null) {
                selectedBody.close();
                body = null;
            }
            state = State.CLOSED;
        }
    }

    /// Validates the complete zlib header and its declared window size.
    private void validateHeader() throws IOException {
        int compressionMethodAndInfo = Byte.toUnsignedInt(header[0]);
        int flags = Byte.toUnsignedInt(header[1]);
        int combined = compressionMethodAndInfo << 8 | flags;
        int compressionMethod = compressionMethodAndInfo & 0x0f;
        int windowInformation = compressionMethodAndInfo >>> 4;
        if (compressionMethod != 8 || windowInformation > 7 || combined % 31 != 0) {
            throw new IOException("Invalid zlib header");
        }
        declaredWindowSize = 1 << (windowInformation + 8);
        CompressionDecoderSupport.requireWindowSize(maximumWindowSize, declaredWindowSize);
    }

    /// Starts raw Deflate decoding with the selected dictionary and declared window.
    private void beginBody(@Nullable CompressionDictionary dictionary) {
        body = new DeflateDecoderEngine(
                DeflateDecoderEngine.Format.DEFLATE,
                dictionary,
                declaredWindowSize
        );
        checksum.reset();
        state = State.BODY;
    }

    /// Validates and installs one requested preset dictionary.
    private void applyDictionary(CompressionDictionary dictionary) throws IOException {
        if (dictionary.id() != CompressionDictionary.UNKNOWN_ID
                && dictionary.id() != requiredDictionaryId) {
            throw new IOException(
                    "Configured zlib dictionary identifier does not match " + requiredDictionaryId
            );
        }
        byte[] bytes = dictionary.bytes();
        Adler32 dictionaryChecksum = new Adler32();
        dictionaryChecksum.update(bytes);
        if (dictionaryChecksum.getValue() != requiredDictionaryId) {
            throw new IOException(
                    "Configured zlib dictionary does not match " + requiredDictionaryId
            );
        }
        beginBody(dictionary);
    }

    /// Updates the stream checksum for the target range produced by the Deflate engine.
    private void updateContentChecksum(ByteBuffer target, int start) {
        int end = target.position();
        if (start == end) {
            return;
        }
        ByteBuffer produced = target.duplicate();
        produced.position(start);
        produced.limit(end);
        checksum.update(produced);
    }

    /// Reads one unsigned big-endian 32-bit value from a fixed byte array.
    private static long bigEndianUnsignedInt(byte[] source) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(source[0]) << 24
                        | Byte.toUnsignedInt(source[1]) << 16
                        | Byte.toUnsignedInt(source[2]) << 8
                        | Byte.toUnsignedInt(source[3])
        );
    }

    /// Copies available source bytes into one owned fixed-size array.
    private static int copyOwned(ByteBuffer source, byte[] target, int offset) {
        int length = Math.min(source.remaining(), target.length - offset);
        source.get(target, offset, length);
        return length;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Zlib decoder is closed");
        }
    }

    /// Tracks the explicit zlib parser and decoder lifecycle.
    @NotNullByDefault
    private enum State {
        /// The two-byte stream header is being parsed.
        HEADER,

        /// The four-byte preset-dictionary identifier is being parsed.
        DICTIONARY_IDENTIFIER,

        /// Decoding is paused until a preset dictionary is supplied.
        NEEDS_DICTIONARY,

        /// Raw Deflate stream content is being decoded.
        BODY,

        /// The four-byte Adler-32 trailer is being parsed.
        TRAILER,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Decoder-owned state was released.
        CLOSED
    }
}
