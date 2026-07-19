// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
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
        if (decoder instanceof CompressionDecoder.DictionaryAware<?, ?> dictionaryDecoder) {
            return createDictionaryAware(dictionaryDecoder, maximumOutputSize);
        }
        if (decoder instanceof CompressionDecoder.Framed framedDecoder) {
            return createFramed(framedDecoder, maximumOutputSize);
        }
        return new OutputLimitingCompressionDecoder(decoder, maximumOutputSize);
    }

    /// Creates a type-preserving limiting wrapper around a framed decoder.
    static CompressionDecoder.Framed createFramed(
            CompressionDecoder.Framed decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (decoder instanceof CompressionDecoder.DictionaryAware<?, ?> dictionaryDecoder) {
            return new FramedDictionaryDecoder<>(dictionaryDecoder, maximumOutputSize);
        }
        return new FramedDecoder(decoder, maximumOutputSize);
    }

    /// Creates a type-preserving limiting wrapper around a dictionary-aware decoder.
    ///
    /// @param <D> accepted dictionary type
    /// @param <R> exposed request type
    static <D extends CompressionDictionary, R extends DictionaryRequest<D>>
            CompressionDecoder.DictionaryAware<D, R> createDictionaryAware(
            CompressionDecoder.DictionaryAware<D, R> decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (decoder instanceof CompressionDecoder.Framed) {
            return new FramedDictionaryDecoder<>(decoder, maximumOutputSize);
        }
        return new DictionaryDecoder<>(decoder, maximumOutputSize);
    }

    /// Creates a type-preserving limiting wrapper around a framed dictionary-aware decoder.
    ///
    /// @param <D> accepted dictionary type
    /// @param <R> exposed request type
    static <D extends CompressionDictionary, R extends DictionaryRequest<D>>
            CompressionDecoder.FramedDictionaryAware<D, R> createFramedDictionaryAware(
            CompressionDecoder.FramedDictionaryAware<D, R> decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        return new FramedDictionaryDecoder<>(decoder, maximumOutputSize);
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
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Finishes decoding after all source bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Implements decoding with the selected source-completion state.
    private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
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
            outcome = endOfInput
                    ? decoder.finish(source, target)
                    : decoder.decode(source, target);
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


    /// Decodes one hidden byte to determine whether the configured limit is exceeded.
    private CodecOutcome probeForExcess(ByteBuffer source, boolean endOfInput) throws IOException {
        probe.clear();
        CodecOutcome outcome = endOfInput
                ? decoder.finish(source, probe)
                : decoder.decode(source, probe);
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
    private DecompressionOutputLimitException limitException() {
        return new DecompressionOutputLimitException(maximumOutputSize);
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
    ///
    /// @param <D> accepted dictionary type
    /// @param <R> exposed request type
    @NotNullByDefault
    private static class DictionaryDecoder<
            D extends CompressionDictionary,
            R extends DictionaryRequest<D>
    > extends OutputLimitingCompressionDecoder
            implements CompressionDecoder.DictionaryAware<D, R> {
        /// Wrapped dictionary-aware decoder.
        private final CompressionDecoder.DictionaryAware<D, R> dictionaryDecoder;

        /// Creates a dictionary-aware limiting wrapper.
        private DictionaryDecoder(
                CompressionDecoder.DictionaryAware<D, R> decoder,
                long maximumOutputSize
        ) {
            super(decoder, maximumOutputSize);
            this.dictionaryDecoder = decoder;
        }

        /// Returns the current format-specific dictionary request.
        @Override
        public R dictionaryRequest() {
            return dictionaryDecoder.dictionaryRequest();
        }

        /// Supplies the requested format-specific dictionary.
        @Override
        public void provideDictionary(D dictionary) throws IOException {
            dictionaryDecoder.provideDictionary(dictionary);
        }
    }

    /// Preserves both concatenated-frame and late-dictionary capabilities through the output limiter.
    ///
    /// @param <D> accepted dictionary type
    /// @param <R> exposed request type
    @NotNullByDefault
    private static final class FramedDictionaryDecoder<
            D extends CompressionDictionary,
            R extends DictionaryRequest<D>
    > extends DictionaryDecoder<D, R>
            implements CompressionDecoder.FramedDictionaryAware<D, R> {
        /// Creates a framed dictionary-aware limiting wrapper.
        private FramedDictionaryDecoder(
                CompressionDecoder.DictionaryAware<D, R> decoder,
                long maximumOutputSize
        ) {
            super(decoder, maximumOutputSize);
        }
    }
}
