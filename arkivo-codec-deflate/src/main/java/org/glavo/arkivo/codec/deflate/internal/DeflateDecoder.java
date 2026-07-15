// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Incrementally decodes one raw Deflate stream without binding the codec state to an input channel.
@NotNullByDefault
public final class DeflateDecoder implements CompressionDecoder {
    /// Empty input used to detach caller-owned buffers from the JDK context after every operation.
    private static final ByteBuffer EMPTY_INPUT = ByteBuffer.allocate(0);

    /// Configured preset dictionary bytes, or null.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// JDK raw Inflate context.
    private final Inflater inflater = new Inflater(true);

    /// Current decoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates a raw Deflate decoder with an optional preset dictionary.
    ///
    /// @param dictionary preset dictionary, or null
    public DeflateDecoder(@Nullable CompressionDictionary dictionary) {
        this.dictionary = dictionary != null ? dictionary.bytes() : null;
        try {
            configureDictionary();
        } catch (RuntimeException | Error exception) {
            inflater.end();
            state = State.CLOSED;
            throw exception;
        }
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
            if (inflater.needsDictionary()) {
                state = State.NEEDS_DICTIONARY;
                return CodecOutcome.NEEDS_DICTIONARY;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }

            int sourcePosition = source.position();
            int targetPosition = target.position();
            inflater.setInput(source.hasRemaining() ? source : EMPTY_INPUT);
            try {
                inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid raw deflate stream", exception);
            } finally {
                inflater.setInput(EMPTY_INPUT);
            }

            if (inflater.finished()) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }
            if (inflater.needsDictionary()) {
                state = State.NEEDS_DICTIONARY;
                return CodecOutcome.NEEDS_DICTIONARY;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (source.position() != sourcePosition || target.position() != targetPosition) {
                continue;
            }
            if (!source.hasRemaining()) {
                if (endOfInput) {
                    throw new EOFException("Unexpected end of raw deflate stream");
                }
                return CodecOutcome.NEEDS_INPUT;
            }
            throw new IOException("Raw deflate decoder made no progress");
        }
    }

    /// Supplies a late-bound preset dictionary requested by the JDK inflater.
    @Override
    public void provideDictionary(CompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        requireOpen();
        if (state != State.NEEDS_DICTIONARY) {
            throw new IllegalStateException("Raw Deflate decoder is not waiting for a dictionary");
        }
        inflater.setDictionary(dictionary.bytes());
        state = State.ACTIVE;
    }

    /// Abandons the current stream and restores the configured Inflate state.
    @Override
    public void reset() {
        requireOpen();
        inflater.reset();
        configureDictionary();
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

    /// Applies the immutable preset dictionary to a fresh JDK context session.
    private void configureDictionary() {
        byte @Nullable [] selectedDictionary = dictionary;
        if (selectedDictionary != null) {
            inflater.setDictionary(selectedDictionary);
        }
    }

    /// Requires the native context to remain available.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Raw Deflate decoder is closed");
        }
    }

    /// Tracks the explicit raw Deflate stream lifecycle.
    private enum State {
        /// The decoder accepts compressed source bytes.
        ACTIVE,

        /// Decoding is paused until a preset dictionary is supplied.
        NEEDS_DICTIONARY,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Native resources were released.
        CLOSED
    }
}
