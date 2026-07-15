// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes one headerless LZMA range-coded stream without retaining caller buffers.
///
/// The range decoder speculates over one complete symbol at a time. An incomplete symbol rolls back its adaptive
/// probabilities and retains only a fixed-size owned prefix, while successful symbols commit exact source positions.
@NotNullByDefault
public final class LZMARawDecoder implements CompressionDecoder {
    /// Number of bytes in the LZMA range-coder prefix.
    private static final int RANGE_PREFIX_SIZE = 5;

    /// Immutable model and dictionary properties.
    private final LZMAProperties properties;

    /// Exact decoded size, or `CompressionCodec.UNKNOWN_SIZE` for EOS termination.
    private final long expectedSize;

    /// Collected range-coder prefix bytes.
    private final byte[] rangePrefix = new byte[RANGE_PREFIX_SIZE];

    /// Mutable raw symbol decoder, or null after closure.
    private @Nullable LZMADecoderEngine decoder;

    /// Transactional compressed input after prefix collection, or null before initialization.
    private @Nullable LZMABufferInput input;

    /// Number of collected range-coder prefix bytes.
    private int rangePrefixSize;

    /// Current decoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates a raw decoder with explicit model, size, and window limits.
    ///
    /// @param properties        LZMA model and dictionary properties
    /// @param decodedSize       exact decoded size, or `CompressionCodec.UNKNOWN_SIZE`
    /// @param maximumWindowSize maximum permitted dictionary allocation
    public LZMARawDecoder(
            LZMAProperties properties,
            long decodedSize,
            long maximumWindowSize
    ) throws IOException {
        if (decodedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA decoded size must be nonnegative or -1");
        }
        this.properties = Objects.requireNonNull(properties, "properties");
        this.expectedSize = decodedSize;
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, properties.dictionarySize());
        decoder = createDecoder();
    }

    /// Decodes source bytes until input, output space, or the raw stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (!target.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        collectRangePrefix(source);
        if (rangePrefixSize < rangePrefix.length) {
            if (endOfInput) {
                throw new EOFException("Truncated LZMA range-coded stream");
            }
            return CodecOutcome.NEEDS_INPUT;
        }

        LZMABufferInput activeInput = initializeInput();
        activeInput.attach(source, endOfInput);
        try {
            while (target.hasRemaining()) {
                int value;
                try {
                    value = requireDecoder().readByte();
                } catch (LZMANeedInputException exception) {
                    activeInput.retainAttachedFragment();
                    return CodecOutcome.NEEDS_INPUT;
                }
                if (value < 0) {
                    state = State.FINISHED;
                    return CodecOutcome.FINISHED;
                }
                target.put((byte) value);
            }
            return CodecOutcome.NEEDS_OUTPUT;
        } finally {
            activeInput.detach();
        }
    }

    /// Abandons the current raw stream and restores its configured initial state.
    @Override
    public void reset() {
        requireOpen();
        rangePrefixSize = 0;
        input = null;
        decoder = createDecoder();
        state = State.ACTIVE;
    }

    /// Releases dictionary and lookahead state without consuming more input.
    @Override
    public void close() {
        state = State.CLOSED;
        decoder = null;
        input = null;
        rangePrefixSize = 0;
    }

    /// Copies only the fixed range-coder prefix from caller input.
    private void collectRangePrefix(ByteBuffer source) {
        int length = Math.min(source.remaining(), rangePrefix.length - rangePrefixSize);
        source.get(rangePrefix, rangePrefixSize, length);
        rangePrefixSize += length;
    }

    /// Initializes the transactional input and active range coder after prefix collection.
    private LZMABufferInput initializeInput() throws IOException {
        @Nullable LZMABufferInput current = input;
        if (current != null) {
            return current;
        }
        current = new LZMABufferInput(rangePrefix);
        requireDecoder().startChunk(current, expectedSize, true);
        current.compact();
        input = current;
        return current;
    }

    /// Creates an empty raw symbol decoder with the immutable model configuration.
    private LZMADecoderEngine createDecoder() {
        LZMADecoderEngine created = new LZMADecoderEngine(properties.dictionarySize());
        created.configure(properties.propertyByte());
        created.resetDictionary();
        return created;
    }

    /// Returns the active raw symbol decoder.
    private LZMADecoderEngine requireDecoder() {
        @Nullable LZMADecoderEngine current = decoder;
        if (current == null) {
            throw new IllegalStateException("LZMA decoder is closed");
        }
        return current;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("LZMA decoder is closed");
        }
    }

    /// Enumerates the raw decoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// More compressed input may be accepted.
        ACTIVE,

        /// The raw stream boundary has been consumed.
        FINISHED,

        /// The decoder has been closed.
        CLOSED
    }
}
