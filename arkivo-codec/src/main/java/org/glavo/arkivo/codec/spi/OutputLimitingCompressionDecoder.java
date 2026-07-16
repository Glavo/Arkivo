// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Enforces a caller-visible output limit around a transport-independent decoder engine.
@NotNullByDefault
class OutputLimitingCompressionDecoder implements CompressionDecoder {
    /// Algorithm-specific decoder engine.
    private final CompressionDecoder decoder;

    /// Maximum decoded byte count.
    private final long maximumOutputSize;

    /// Single-byte output used to distinguish an exact-size frame from excess output.
    private final ByteBuffer probe = ByteBuffer.allocate(1);

    /// Number of decoded bytes returned to callers.
    private long outputBytes;

    /// Whether excess output has already been observed.
    private boolean exceeded;

    /// Creates a capability-preserving limiting wrapper around one decoder engine.
    static CompressionDecoder create(CompressionDecoder decoder, long maximumOutputSize) {
        Objects.requireNonNull(decoder, "decoder");
        boolean framed = decoder instanceof CompressionDecoder.Framed;
        boolean dictionary = decoder instanceof CompressionDecoder.DictionaryAware;
        if (framed && dictionary) {
            return new FramedDictionaryDecoder(decoder, maximumOutputSize);
        }
        if (framed) {
            return new FramedDecoder(decoder, maximumOutputSize);
        }
        if (dictionary) {
            return new DictionaryDecoder(decoder, maximumOutputSize);
        }
        return new OutputLimitingCompressionDecoder(decoder, maximumOutputSize);
    }

    /// Creates a limiting wrapper around one decoder engine.
    OutputLimitingCompressionDecoder(CompressionDecoder decoder, long maximumOutputSize) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            throw new IllegalArgumentException("maximumOutputSize must not be negative");
        }
        this.maximumOutputSize = maximumOutputSize;
    }

    /// Decodes without returning more than the configured output limit.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (exceeded) {
            throw limitException();
        }
        if (!target.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        long remaining = maximumOutputSize - outputBytes;
        if (remaining == 0L) {
            return probeForExcess(source, endOfInput);
        }

        int originalLimit = target.limit();
        if (target.remaining() > remaining) {
            target.limit(target.position() + Math.toIntExact(remaining));
        }
        int targetStart = target.position();
        CodecOutcome outcome;
        try {
            outcome = decoder.decode(source, target, endOfInput);
        } finally {
            target.limit(originalLimit);
        }
        outputBytes += target.position() - targetStart;
        return outcome;
    }

    /// Resets both the output limit and underlying decoder session.
    @Override
    public void reset() {
        decoder.reset();
        outputBytes = 0L;
        exceeded = false;
        probe.clear();
    }

    /// Releases the underlying decoder engine.
    @Override
    public void close() {
        decoder.close();
    }

    /// Returns the wrapped decoder as a dictionary-aware engine.
    final CompressionDecoder.DictionaryAware dictionaryDecoder() {
        return (CompressionDecoder.DictionaryAware) decoder;
    }

    /// Decodes one hidden byte to determine whether the configured limit is exceeded.
    private CodecOutcome probeForExcess(ByteBuffer source, boolean endOfInput) throws IOException {
        probe.clear();
        CodecOutcome outcome = decoder.decode(source, probe, endOfInput);
        if (probe.position() != 0) {
            exceeded = true;
            throw limitException();
        }
        if (outcome == CodecOutcome.NEEDS_OUTPUT) {
            throw new IOException("Compression decoder requested output without producing a probe byte");
        }
        return outcome;
    }

    /// Creates the configured decompression-limit failure.
    private DecompressionLimitException limitException() {
        return new DecompressionLimitException(maximumOutputSize);
    }

    /// Preserves concatenated-frame support through the output limiter.
    @NotNullByDefault
    private static final class FramedDecoder
            extends OutputLimitingCompressionDecoder
            implements CompressionDecoder.Framed {
        /// Creates a framed limiting wrapper.
        private FramedDecoder(CompressionDecoder decoder, long maximumOutputSize) {
            super(decoder, maximumOutputSize);
        }
    }

    /// Preserves late dictionary binding through the output limiter.
    @NotNullByDefault
    private static class DictionaryDecoder
            extends OutputLimitingCompressionDecoder
            implements CompressionDecoder.DictionaryAware {
        /// Creates a dictionary-aware limiting wrapper.
        private DictionaryDecoder(CompressionDecoder decoder, long maximumOutputSize) {
            super(decoder, maximumOutputSize);
        }

        /// Returns the requested dictionary identifier.
        @Override
        public long requiredDictionaryId() {
            return dictionaryDecoder().requiredDictionaryId();
        }

        /// Supplies the requested dictionary.
        @Override
        public void provideDictionary(CompressionDictionary dictionary) throws IOException {
            dictionaryDecoder().provideDictionary(dictionary);
        }
    }

    /// Preserves both concatenated-frame and late-dictionary capabilities through the output limiter.
    @NotNullByDefault
    private static final class FramedDictionaryDecoder
            extends DictionaryDecoder
            implements CompressionDecoder.Framed {
        /// Creates a framed dictionary-aware limiting wrapper.
        private FramedDictionaryDecoder(CompressionDecoder decoder, long maximumOutputSize) {
            super(decoder, maximumOutputSize);
        }
    }
}
