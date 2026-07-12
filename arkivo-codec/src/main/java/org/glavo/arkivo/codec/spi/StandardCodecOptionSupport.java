// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Applies standard codec options that can be implemented independently of a compression algorithm.
@NotNullByDefault
public final class StandardCodecOptionSupport {
    /// Creates no instances.
    private StandardCodecOptionSupport() {
    }

    /// Resolves and validates the configured maximum decompressed output size.
    ///
    /// Returns `CompressionCodec.UNKNOWN_SIZE` when no limit is configured.
    public static long maximumOutputSize(CodecOptions options) {
        Objects.requireNonNull(options, "options");
        @Nullable Long requested = options.get(StandardCodecOptions.MAX_OUTPUT_SIZE);
        if (requested == null) {
            return CompressionCodec.UNKNOWN_SIZE;
        }
        if (requested < 0L) {
            throw new IllegalArgumentException("Maximum decompressed output size must not be negative");
        }
        return requested;
    }

    /// Applies a validated maximum output size to a decoder.
    ///
    /// A negative value leaves the decoder unchanged. Non-negative values are enforced against bytes returned to
    /// callers, with one additional decoded byte used only to distinguish exact-limit EOF from excess output.
    public static CompressionDecoder limitOutput(CompressionDecoder decoder, long maximumOutputSize) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return new OutputLimitingDecoder(decoder, maximumOutputSize);
    }

    /// Enforces one maximum decompressed output size over a decoder context.
    @NotNullByDefault
    private static final class OutputLimitingDecoder implements CompressionDecoder {
        /// The algorithm-specific decoder.
        private final CompressionDecoder decoder;

        /// The maximum number of bytes that may be returned.
        private final long maximumOutputSize;

        /// The single-byte buffer used to verify EOF after the limit is reached.
        private final ByteBuffer probe = ByteBuffer.allocate(1);

        /// The number of decoded bytes returned to callers.
        private long outputBytes;

        /// Whether excess decoded output has already been observed.
        private boolean exceeded;

        /// Creates an output-limiting decoder.
        private OutputLimitingDecoder(CompressionDecoder decoder, long maximumOutputSize) {
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

        /// Returns compressed bytes consumed by the underlying decoder.
        @Override
        public long inputBytes() {
            return decoder.inputBytes();
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

        /// Closes the underlying decoder and applies its source ownership policy.
        @Override
        public void close() throws IOException {
            decoder.close();
        }

        /// Probes for one excess byte after the caller-visible limit is reached.
        private int probeForExcess() throws IOException {
            probe.clear();
            int read = decoder.read(probe);
            if (read <= 0) {
                return read;
            }
            exceeded = true;
            throw limitException();
        }

        /// Creates the stable exception reported after excess output is observed.
        private DecompressionLimitException limitException() {
            return new DecompressionLimitException(maximumOutputSize);
        }
    }
}
