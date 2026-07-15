// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zlib.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Incrementally decodes one zlib stream with explicit preset-dictionary negotiation.
@NotNullByDefault
public final class ZlibDecoder implements CompressionDecoder {
    /// Empty input used to detach caller-owned buffers from the JDK context after every operation.
    private static final ByteBuffer EMPTY_INPUT = ByteBuffer.allocate(0);

    /// JDK zlib-wrapped Inflate context.
    private final Inflater inflater = new Inflater(false);

    /// Maximum permitted declared zlib window size, or the unknown sentinel.
    private final long maximumWindowSize;

    /// Configured preset dictionary bytes applied automatically when requested, or null.
    private final byte @Nullable @Unmodifiable [] configuredDictionary;

    /// Configured dictionary identifier, or the unknown sentinel.
    private final long configuredDictionaryId;

    /// Mutable storage for the two-byte zlib header used by window validation.
    private final byte[] header = new byte[2];

    /// Current decoder lifecycle state.
    private State state = State.ACTIVE;

    /// Number of zlib header bytes already copied into owned storage.
    private int headerBytes;

    /// Validated header bytes not yet consumed by the JDK inflater.
    private ByteBuffer pendingHeader = EMPTY_INPUT;

    /// Whether the configured dictionary has been applied in the current stream.
    private boolean configuredDictionaryApplied;

    /// Dictionary identifier requested by the current stream, or the unknown sentinel.
    private long requiredDictionaryId = CompressionDictionary.UNKNOWN_ID;

    /// Creates a zlib decoder with optional window and preset-dictionary configuration.
    ///
    /// @param maximumWindowSize maximum permitted declared window size, or `CompressionCodec.UNKNOWN_SIZE`
    /// @param dictionary preset dictionary applied automatically when requested, or null
    public ZlibDecoder(long maximumWindowSize, @Nullable CompressionDictionary dictionary) {
        this.maximumWindowSize = maximumWindowSize;
        this.configuredDictionary = dictionary != null ? dictionary.bytes() : null;
        this.configuredDictionaryId = dictionary != null
                ? dictionary.id()
                : CompressionDictionary.UNKNOWN_ID;
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
            if (inflater.finished()) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }

            if (headerBytes < header.length) {
                while (source.hasRemaining() && headerBytes < header.length) {
                    header[headerBytes++] = source.get();
                }
                if (headerBytes < header.length) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of zlib header");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                validateWindowSize();
                pendingHeader = ByteBuffer.wrap(header);
            }

            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }

            ByteBuffer input;
            if (pendingHeader.hasRemaining()) {
                input = pendingHeader;
            } else if (source.hasRemaining()) {
                input = source;
            } else {
                if (endOfInput) {
                    throw new EOFException("Unexpected end of zlib stream");
                }
                return CodecOutcome.NEEDS_INPUT;
            }

            int inputPosition = input.position();
            int targetPosition = target.position();
            inflater.setInput(input);
            try {
                inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid zlib stream", exception);
            } finally {
                inflater.setInput(EMPTY_INPUT);
            }

            if (inflater.finished()) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }
            if (inflater.needsDictionary()) {
                requiredDictionaryId = Integer.toUnsignedLong(inflater.getAdler());
                byte @Nullable [] selectedDictionary = configuredDictionary;
                if (selectedDictionary != null && !configuredDictionaryApplied) {
                    applyDictionary(selectedDictionary, configuredDictionaryId);
                    configuredDictionaryApplied = true;
                    continue;
                }
                state = State.NEEDS_DICTIONARY;
                return CodecOutcome.NEEDS_DICTIONARY;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (input.position() != inputPosition || target.position() != targetPosition) {
                continue;
            }
            if (!input.hasRemaining()) {
                if (!pendingHeader.hasRemaining() && !source.hasRemaining()) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of zlib stream");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                continue;
            }
            throw new IOException("Zlib decoder made no progress");
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
        applyDictionary(dictionary.bytes(), dictionary.id());
        state = State.ACTIVE;
    }

    /// Abandons the current stream and restores the configured zlib decoder state.
    @Override
    public void reset() {
        requireOpen();
        inflater.reset();
        headerBytes = 0;
        pendingHeader = EMPTY_INPUT;
        configuredDictionaryApplied = false;
        requiredDictionaryId = CompressionDictionary.UNKNOWN_ID;
        state = State.ACTIVE;
    }

    /// Releases the JDK Inflate context without consuming additional input.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            inflater.end();
        }
    }

    /// Validates the window size declared by the complete owned zlib header.
    private void validateWindowSize() throws IOException {
        int compressionMethodAndInfo = Byte.toUnsignedInt(header[0]);
        int flags = Byte.toUnsignedInt(header[1]);
        int combined = compressionMethodAndInfo << 8 | flags;
        int compressionMethod = compressionMethodAndInfo & 0x0f;
        int windowBits = (compressionMethodAndInfo >>> 4) + 8;
        if (compressionMethod == 8 && windowBits <= 15 && combined % 31 == 0) {
            StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, 1L << windowBits);
        }
    }

    /// Applies dictionary bytes after validating an optional caller-provided identifier.
    private void applyDictionary(byte[] dictionary, long dictionaryId) throws IOException {
        if (dictionaryId != CompressionDictionary.UNKNOWN_ID
                && dictionaryId != requiredDictionaryId) {
            throw new IOException(
                    "Configured zlib dictionary identifier does not match " + requiredDictionaryId
            );
        }
        try {
            inflater.setDictionary(dictionary);
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Configured zlib dictionary does not match " + requiredDictionaryId,
                    exception
            );
        }
    }

    /// Requires the native context to remain available.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Zlib decoder is closed");
        }
    }

    /// Tracks the explicit zlib stream lifecycle.
    private enum State {
        /// The decoder accepts source bytes.
        ACTIVE,

        /// Decoding is paused until a preset dictionary is supplied.
        NEEDS_DICTIONARY,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Native resources were released.
        CLOSED
    }
}
