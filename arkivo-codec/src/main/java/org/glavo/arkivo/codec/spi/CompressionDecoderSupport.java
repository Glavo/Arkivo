// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel.Directive;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Applies operation-scoped safety behavior around decoder engines and channel contexts.
@NotNullByDefault
public final class CompressionDecoderSupport {
    /// Creates no instances.
    private CompressionDecoderSupport() {
    }

    /// Rejects a required history window that exceeds a configured maximum.
    ///
    /// A negative maximum leaves the window size unrestricted.
    public static void requireWindowSize(
            long maximumWindowSize,
            long requiredWindowSize
    ) throws org.glavo.arkivo.codec.DecompressionWindowLimitException {
        if (requiredWindowSize < 0L) {
            throw new IllegalArgumentException("requiredWindowSize must not be negative");
        }
        if (maximumWindowSize >= 0L && requiredWindowSize > maximumWindowSize) {
            throw new org.glavo.arkivo.codec.DecompressionWindowLimitException(
                    maximumWindowSize,
                    requiredWindowSize
            );
        }
    }

    /// Applies a maximum decoded-output size to a transport-independent decoder.
    ///
    /// A negative value leaves the decoder unchanged.
    public static CompressionDecoder limitEngineOutput(
            CompressionDecoder decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return OutputLimitingCompressionDecoder.create(decoder, maximumOutputSize);
    }

    /// Applies a maximum decoded-output size while preserving typed late dictionary binding.
    ///
    /// A negative value leaves the decoder unchanged.
    ///
    /// @param <D> the format-specific dictionary type
    /// @param <R> the format-specific dictionary request type
    public static <D extends CompressionDictionary, R extends DictionaryRequest>
            CompressionDecoder.DictionaryAware<D, R> limitEngineOutput(
            CompressionDecoder.DictionaryAware<D, R> decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return OutputLimitingCompressionDecoder.createDictionaryAware(decoder, maximumOutputSize);
    }

    /// Applies a maximum decoded-output size across a complete channel decoding session.
    ///
    /// A negative value leaves the channel unchanged.
    public static DecompressingReadableByteChannel limitChannelOutput(
            DecompressingReadableByteChannel decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return new OutputLimitingChannel(decoder, maximumOutputSize);
    }

    /// Enforces a total maximum output size over a channel decoding session.
    @NotNullByDefault
    private static final class OutputLimitingChannel
            implements DecompressingReadableByteChannel {
        /// The algorithm-specific decoder channel.
        private final DecompressingReadableByteChannel decoder;

        /// The maximum number of bytes that may be returned.
        private final long maximumOutputSize;

        /// The single-byte buffer used to verify completion after the limit is reached.
        private final ByteBuffer probe = ByteBuffer.allocate(1);

        /// The number of decoded bytes returned to callers.
        private long outputBytes;

        /// Whether excess decoded output has already been observed.
        private boolean exceeded;

        /// Creates an output-limiting channel.
        private OutputLimitingChannel(
                DecompressingReadableByteChannel decoder,
                long maximumOutputSize
        ) {
            this.decoder = decoder;
            this.maximumOutputSize = maximumOutputSize;
        }

        /// Reads decoded bytes without returning more than the configured maximum.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (exceeded) {
                throw limitException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }

            long remaining = maximumOutputSize - outputBytes;
            if (remaining == 0L) {
                return probeForExcess();
            }

            int originalLimit = target.limit();
            if (target.remaining() > remaining) {
                target.limit(target.position() + Math.toIntExact(remaining));
            }
            int read;
            try {
                read = decoder.read(target);
            } finally {
                target.limit(originalLimit);
            }
            if (read > 0) {
                outputBytes += read;
            }
            return read;
        }

        /// Decodes with frame control while enforcing the total output limit.
        @Override
        public CodecResult decode(
                ByteBuffer target,
                Directive directive
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(directive, "directive");
            if (exceeded) {
                throw limitException();
            }
            if (!target.hasRemaining()) {
                return decoder.decode(target, directive);
            }

            long remaining = maximumOutputSize - outputBytes;
            if (remaining == 0L) {
                return probeForExcess(directive);
            }

            int start = target.position();
            int originalLimit = target.limit();
            if (target.remaining() > remaining) {
                target.limit(target.position() + Math.toIntExact(remaining));
            }
            CodecResult result;
            try {
                result = decoder.decode(target, directive);
            } finally {
                target.limit(originalLimit);
            }
            int produced = target.position() - start;
            outputBytes += produced;
            return new CodecResult(result.inputBytes(), produced, result.status());
        }

        /// Returns compressed bytes consumed by the underlying decoder.
        @Override
        public long inputBytes() {
            return decoder.inputBytes();
        }

        /// Returns compressed bytes obtained from the underlying source.
        @Override
        public long sourceBytes() {
            return decoder.sourceBytes();
        }

        /// Returns the underlying decoder's read-only unconsumed-input view.
        @Override
        public @UnmodifiableView ByteBuffer unconsumedInput() {
            return decoder.unconsumedInput();
        }

        /// Returns decoded bytes delivered before the configured limit.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether the underlying decoder remains open.
        @Override
        public boolean isOpen() {
            return decoder.isOpen();
        }

        /// Closes the underlying decoder and applies its ownership policy.
        @Override
        public void close() throws IOException {
            decoder.close();
        }

        /// Probes for one excess byte after the output limit is reached.
        private int probeForExcess() throws IOException {
            probe.clear();
            int read = decoder.read(probe);
            if (read <= 0) {
                return read;
            }
            exceeded = true;
            throw limitException();
        }

        /// Probes one frame-aware operation after the output limit is reached.
        private CodecResult probeForExcess(Directive directive) throws IOException {
            probe.clear();
            CodecResult result = decoder.decode(probe, directive);
            if (result.outputBytes() == 0L) {
                return result;
            }
            exceeded = true;
            throw limitException();
        }

        /// Creates the stable configured decompression-limit failure.
        private DecompressionLimitException limitException() {
            return new DecompressionLimitException(maximumOutputSize);
        }
    }
}
