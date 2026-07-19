// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.util.Objects;

/// Validates decoding limits and applies safety behavior around decoder engines and channel contexts.
@NotNullByDefault
public final class CompressionDecoderSupport {
    /// Creates no instances.
    private CompressionDecoderSupport() {
    }

    /// Validates a decoding size limit.
    ///
    /// @param value the nonnegative size limit, or [org.glavo.arkivo.codec.CompressionCodec#UNLIMITED_SIZE]
    /// @param name  the parameter name used in a validation failure
    /// @throws IllegalArgumentException if `value` is less than
    /// [org.glavo.arkivo.codec.CompressionCodec#UNLIMITED_SIZE]
    public static void validateLimit(long value, String name) {
        Objects.requireNonNull(name, "name");
        if (value < org.glavo.arkivo.codec.CompressionCodec.UNLIMITED_SIZE) {
            throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED_SIZE");
        }
    }

    /// Returns the effective history-window limit after applying the decoder-memory ceiling.
    ///
    /// @param maximumWindowSize the configured history-window limit, or a negative value when unrestricted
    /// @param maximumMemorySize the configured decoder-memory limit, or a negative value when unrestricted
    /// @return the smaller finite limit, or a negative value when both limits are unrestricted
    public static long effectiveMaximumWindowSize(long maximumWindowSize, long maximumMemorySize) {
        if (maximumWindowSize < 0L) {
            return maximumMemorySize;
        }
        if (maximumMemorySize < 0L) {
            return maximumWindowSize;
        }
        return Math.min(maximumWindowSize, maximumMemorySize);
    }

    /// Rejects a required history window that exceeds a configured maximum.
    ///
    /// A negative maximum leaves the window size unrestricted.
    ///
    /// @param maximumWindowSize  the maximum permitted window size, or a negative value for no limit
    /// @param requiredWindowSize the nonnegative window size required by the compressed stream
    /// @throws IllegalArgumentException                                 if `requiredWindowSize` is negative
    /// @throws org.glavo.arkivo.codec.DecompressionWindowLimitException if the required window exceeds a nonnegative
    /// maximum
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

    /// Rejects a decoder working-memory requirement larger than a configured maximum.
    ///
    /// A negative maximum leaves decoder memory unrestricted.
    ///
    /// @param maximumMemorySize the maximum permitted working-memory size, or a negative value for no limit
    /// @param requiredMemorySize the nonnegative working-memory size required by the decoder
    /// @throws IllegalArgumentException       if `requiredMemorySize` is negative
    /// @throws DecompressionMemoryLimitException if the requirement exceeds a nonnegative maximum
    public static void requireMemorySize(
            long maximumMemorySize,
            long requiredMemorySize
    ) throws DecompressionMemoryLimitException {
        if (requiredMemorySize < 0L) {
            throw new IllegalArgumentException("requiredMemorySize must not be negative");
        }
        if (maximumMemorySize >= 0L && requiredMemorySize > maximumMemorySize) {
            throw new DecompressionMemoryLimitException(maximumMemorySize, requiredMemorySize);
        }
    }

    /// Applies a maximum decoded-output size to a transport-independent decoder.
    ///
    /// A negative value leaves the decoder unchanged.
    ///
    /// @param decoder           the decoder to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, an output-limiting decoder
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

    /// Applies a maximum decoded-output size while preserving frame support.
    ///
    /// A negative value leaves the decoder unchanged.
    ///
    /// @param decoder           the frame-capable decoder to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, a frame-capable output-limiting decoder
    public static CompressionDecoder.Framed limitEngineOutput(
            CompressionDecoder.Framed decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return OutputLimitingCompressionDecoder.createFramed(decoder, maximumOutputSize);
    }

    /// Applies a maximum decoded-output size while preserving typed late dictionary binding.
    ///
    /// A negative value leaves the decoder unchanged.
    ///
    /// @param <D>               the format-specific dictionary type
    /// @param <R>               the format-specific dictionary request type
    /// @param decoder           the dictionary-aware decoder to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, a dictionary-aware output-limiting decoder
    public static <D extends CompressionDictionary, R extends DictionaryRequest<D>>
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

    /// Applies a maximum decoded-output size while preserving frame and late-dictionary support.
    ///
    /// A negative value leaves the decoder unchanged.
    ///
    /// @param <D>               the format-specific dictionary type
    /// @param <R>               the format-specific dictionary request type
    /// @param decoder           the frame- and dictionary-aware decoder to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, a frame- and dictionary-aware output-limiting
    /// decoder
    public static <D extends CompressionDictionary, R extends DictionaryRequest<D>>
    CompressionDecoder.FramedDictionaryAware<D, R> limitEngineOutput(
            CompressionDecoder.FramedDictionaryAware<D, R> decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        return OutputLimitingCompressionDecoder.createFramedDictionaryAware(decoder, maximumOutputSize);
    }

    /// Applies a maximum decoded-output size across a complete channel decoding session.
    ///
    /// A negative value leaves the channel unchanged.
    ///
    /// @param decoder           the decoding channel to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, an output-limiting channel that preserves frame and
    /// interruption support when present
    public static DecompressingReadableByteChannel limitChannelOutput(
            DecompressingReadableByteChannel decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        if (decoder instanceof DecompressingReadableByteChannel.Framed framedDecoder) {
            if (decoder instanceof InterruptibleChannel) {
                return new InterruptibleFramedOutputLimitingChannel(framedDecoder, maximumOutputSize);
            }
            return new FramedOutputLimitingChannel(framedDecoder, maximumOutputSize);
        }
        if (decoder instanceof InterruptibleChannel) {
            return new InterruptibleOutputLimitingChannel(decoder, maximumOutputSize);
        }
        return new OutputLimitingChannel(decoder, maximumOutputSize);
    }

    /// Applies a maximum decoded-output size while preserving channel frame support.
    ///
    /// A negative value leaves the channel unchanged.
    ///
    /// @param decoder           the frame-capable decoding channel to constrain
    /// @param maximumOutputSize the maximum decoded byte count, or a negative value for no limit
    /// @return `decoder` when the maximum is negative; otherwise, a frame-capable output-limiting channel that
    /// preserves interruption support when present
    public static DecompressingReadableByteChannel.Framed limitChannelOutput(
            DecompressingReadableByteChannel.Framed decoder,
            long maximumOutputSize
    ) {
        Objects.requireNonNull(decoder, "decoder");
        if (maximumOutputSize < 0L) {
            return decoder;
        }
        if (decoder instanceof InterruptibleChannel) {
            return new InterruptibleFramedOutputLimitingChannel(decoder, maximumOutputSize);
        }
        return new FramedOutputLimitingChannel(decoder, maximumOutputSize);
    }

    /// Enforces a total maximum output size over a channel decoding session.
    @NotNullByDefault
    private static class OutputLimitingChannel
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
        protected OutputLimitingChannel(
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
                return decoder.read(target);
            }

            long remaining = maximumOutputSize - outputBytes;
            if (remaining == 0L) {
                return probeForExcessRead();
            }

            int originalLimit = target.limit();
            if (target.remaining() > remaining) {
                target.limit(target.position() + Math.toIntExact(remaining));
            }
            int start = target.position();
            try {
                return decoder.read(target);
            } finally {
                target.limit(originalLimit);
                outputBytes += target.position() - start;
            }
        }

        /// Decodes without returning more than the configured maximum.
        @Override
        public CodecResult decode(ByteBuffer target) throws IOException {
            return decodeLimited(target, false);
        }

        /// Decodes with optional frame-boundary reporting while enforcing the output limit.
        protected final CodecResult decodeLimited(
                ByteBuffer target,
                boolean stopAtFrame
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            if (exceeded) {
                throw limitException();
            }
            if (!target.hasRemaining()) {
                return decodeDelegate(target, stopAtFrame);
            }

            long remaining = maximumOutputSize - outputBytes;
            if (remaining == 0L) {
                return probeForExcessDecode(stopAtFrame);
            }

            int start = target.position();
            int originalLimit = target.limit();
            if (target.remaining() > remaining) {
                target.limit(target.position() + Math.toIntExact(remaining));
            }
            CodecResult result;
            try {
                result = decodeDelegate(target, stopAtFrame);
            } finally {
                target.limit(originalLimit);
                outputBytes += target.position() - start;
            }
            int produced = target.position() - start;
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

        /// Invokes the ordinary or frame-stopping decode operation.
        private CodecResult decodeDelegate(ByteBuffer target, boolean stopAtFrame) throws IOException {
            if (stopAtFrame) {
                if (decoder instanceof DecompressingReadableByteChannel.Framed framedDecoder) {
                    return framedDecoder.decodeFrame(target);
                }
                throw new AssertionError("Frame decoding requires a framed channel");
            }
            return decoder.decode(target);
        }

        /// Probes for one excess byte through the ordinary channel read contract.
        private int probeForExcessRead() throws IOException {
            probe.clear();
            int read = decoder.read(probe);
            if (read <= 0) {
                return read;
            }
            exceeded = true;
            throw limitException();
        }

        /// Probes one decode operation after the output limit is reached.
        private CodecResult probeForExcessDecode(boolean stopAtFrame) throws IOException {
            probe.clear();
            CodecResult result = decodeDelegate(probe, stopAtFrame);
            if (result.outputBytes() == 0L) {
                return result;
            }
            exceeded = true;
            throw limitException();
        }

        /// Creates the stable configured decompression-limit failure.
        private DecompressionOutputLimitException limitException() {
            return new DecompressionOutputLimitException(maximumOutputSize);
        }
    }

    /// Preserves interruption support through a channel output limiter.
    @NotNullByDefault
    private static final class InterruptibleOutputLimitingChannel
            extends OutputLimitingChannel
            implements InterruptibleChannel {
        /// Creates an interruptible output-limiting channel.
        private InterruptibleOutputLimitingChannel(
                DecompressingReadableByteChannel decoder,
                long maximumOutputSize
        ) {
            super(decoder, maximumOutputSize);
        }
    }

    /// Preserves frame-boundary control through a channel output limiter.
    @NotNullByDefault
    private static class FramedOutputLimitingChannel
            extends OutputLimitingChannel
            implements DecompressingReadableByteChannel.Framed {
        /// Creates a frame-capable output-limiting channel.
        protected FramedOutputLimitingChannel(
                DecompressingReadableByteChannel.Framed decoder,
                long maximumOutputSize
        ) {
            super(decoder, maximumOutputSize);
        }

        /// Decodes through the current frame while enforcing the session output limit.
        @Override
        public CodecResult decodeFrame(ByteBuffer target) throws IOException {
            return decodeLimited(target, true);
        }
    }

    /// Preserves frame-boundary control and interruption support through a channel output limiter.
    @NotNullByDefault
    private static final class InterruptibleFramedOutputLimitingChannel
            extends FramedOutputLimitingChannel
            implements InterruptibleChannel {
        /// Creates an interruptible frame-capable output-limiting channel.
        private InterruptibleFramedOutputLimitingChannel(
                DecompressingReadableByteChannel.Framed decoder,
                long maximumOutputSize
        ) {
            super(decoder, maximumOutputSize);
        }
    }
}
