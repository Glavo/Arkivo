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
final class OutputLimitingCompressionDecoder implements CompressionDecoder {
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

    /// Returns the dictionary identifier requested by the underlying decoder.
    @Override
    public long requiredDictionaryId() {
        return decoder.requiredDictionaryId();
    }

    /// Supplies a requested dictionary to the underlying decoder.
    @Override
    public void provideDictionary(CompressionDictionary dictionary) throws IOException {
        decoder.provideDictionary(dictionary);
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
}
