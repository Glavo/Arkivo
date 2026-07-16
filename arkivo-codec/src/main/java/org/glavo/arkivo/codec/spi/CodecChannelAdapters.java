// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DictionaryRequiredException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts transport-independent compression engines to the blocking Arkivo channel contracts.
@NotNullByDefault
public final class CodecChannelAdapters {
    /// Default compressed-data staging-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// Creates no instances.
    private CodecChannelAdapters() {
    }

    /// Creates an encoding channel whose runtime capabilities match the created engine.
    public static CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            EncoderFactory<? extends CompressionEncoder> factory
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        CompressionEncoder encoder = createEncoder(factory, targetCloser);
        if (encoder instanceof CompressionEncoder.FlushableFramed flushableFramedEncoder) {
            return new FlushableFramedEncodingChannel(target, targetCloser, flushableFramedEncoder);
        }
        if (encoder instanceof CompressionEncoder.Framed framedEncoder) {
            return new FramedEncodingChannel(target, targetCloser, framedEncoder);
        }
        if (encoder instanceof CompressionEncoder.Flushable flushableEncoder) {
            return new FlushableEncodingChannel(target, targetCloser, flushableEncoder);
        }
        return new EncodingChannel(target, targetCloser, encoder);
    }

    /// Creates an encoding channel with nonterminal flush support.
    public static CompressingWritableByteChannel.Flushable openFlushableEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            EncoderFactory<? extends CompressionEncoder.Flushable> factory
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        CompressionEncoder.Flushable encoder = createEncoder(factory, targetCloser);
        if (encoder instanceof CompressionEncoder.FlushableFramed flushableFramedEncoder) {
            return new FlushableFramedEncodingChannel(target, targetCloser, flushableFramedEncoder);
        }
        return new FlushableEncodingChannel(target, targetCloser, encoder);
    }

    /// Creates an encoding channel that can finish independently terminated frames.
    public static CompressingWritableByteChannel.Framed openFramedEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            EncoderFactory<? extends CompressionEncoder.Framed> factory
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        CompressionEncoder.Framed encoder = createEncoder(factory, targetCloser);
        if (encoder instanceof CompressionEncoder.FlushableFramed flushableFramedEncoder) {
            return new FlushableFramedEncodingChannel(target, targetCloser, flushableFramedEncoder);
        }
        return new FramedEncodingChannel(target, targetCloser, encoder);
    }

    /// Creates an encoding channel with both frame-boundary and nonterminal-flush support.
    public static CompressingWritableByteChannel.FlushableFramed openFlushableFramedEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            EncoderFactory<? extends CompressionEncoder.FlushableFramed> factory
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        CompressionEncoder.FlushableFramed encoder = createEncoder(factory, targetCloser);
        return new FlushableFramedEncodingChannel(target, targetCloser, encoder);
    }

    /// Creates a decoding channel whose runtime frame capability matches the created engine.
    public static DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            DecoderFactory<? extends CompressionDecoder> factory
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser sourceCloser = new OwnedChannelCloser(source, ownership);
        CompressionDecoder decoder = createDecoder(factory, sourceCloser);
        if (decoder instanceof CompressionDecoder.Framed framedDecoder) {
            return new FramedDecodingChannel(source, sourceCloser, framedDecoder);
        }
        return new DecodingChannel(source, sourceCloser, decoder, false);
    }

    /// Creates a decoding channel that can stop at independently terminated frame boundaries.
    public static DecompressingReadableByteChannel.Framed openFramedDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            DecoderFactory<? extends CompressionDecoder.Framed> factory
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");
        OwnedChannelCloser sourceCloser = new OwnedChannelCloser(source, ownership);
        CompressionDecoder.Framed decoder = createDecoder(factory, sourceCloser);
        return new FramedDecodingChannel(source, sourceCloser, decoder);
    }

    /// Creates one transport-independent encoder.
    ///
    /// @param <E> the encoder capability type returned by this factory
    @FunctionalInterface
    public interface EncoderFactory<E extends CompressionEncoder> {
        /// Creates a fresh encoder engine.
        E create() throws IOException;
    }

    /// Creates one transport-independent decoder.
    ///
    /// @param <D> the decoder capability type returned by this factory
    @FunctionalInterface
    public interface DecoderFactory<D extends CompressionDecoder> {
        /// Creates a fresh decoder engine.
        D create() throws IOException;
    }

    /// Creates an encoder and applies target ownership when construction fails.
    private static <E extends CompressionEncoder> E createEncoder(
            EncoderFactory<? extends E> factory,
            OwnedChannelCloser targetCloser
    ) throws IOException {
        try {
            return Objects.requireNonNull(factory.create(), "factory.create()");
        } catch (IOException | RuntimeException | Error exception) {
            targetCloser.closeAfter(exception);
            throw new AssertionError("unreachable");
        }
    }

    /// Creates a decoder and applies source ownership when construction fails.
    private static <D extends CompressionDecoder> D createDecoder(
            DecoderFactory<? extends D> factory,
            OwnedChannelCloser sourceCloser
    ) throws IOException {
        try {
            return Objects.requireNonNull(factory.create(), "factory.create()");
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw new AssertionError("unreachable");
        }
    }

    /// Drives an encoder engine into a blocking writable channel.
    @NotNullByDefault
    private static class EncodingChannel implements CompressingWritableByteChannel {
        /// Compressed-data target.
        private final WritableByteChannel target;

        /// Target ownership tracker.
        private final OwnedChannelCloser targetCloser;

        /// Transport-independent encoder engine.
        private final CompressionEncoder encoder;

        /// Encoded output staging buffer.
        private final ByteBuffer output = ByteBuffer.allocateDirect(BUFFER_SIZE);

        /// Number of uncompressed bytes accepted from callers.
        private long inputBytes;

        /// Number of compressed bytes written to the target.
        private long outputBytes;

        /// Whether engine finalization has been attempted.
        private boolean finished;

        /// Whether the current frame contains or may emit encoded state.
        private boolean frameActive = true;

        /// Whether the writable channel remains open for input.
        private boolean open = true;

        /// Creates an encoding channel around a fresh engine.
        protected EncodingChannel(
                WritableByteChannel target,
                OwnedChannelCloser targetCloser,
                CompressionEncoder encoder
        ) {
            this.target = target;
            this.targetCloser = targetCloser;
            this.encoder = encoder;
        }

        /// Drives source bytes through the encoder and writes all immediately produced output.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            if (!source.hasRemaining()) {
                return 0;
            }
            frameActive = true;

            int sourceStart = source.position();
            try {
                while (source.hasRemaining()) {
                    output.clear();
                    int inputPosition = source.position();
                    CodecOutcome outcome = encoder.encode(source, output);
                    writeOutput();
                    if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                        requireProgress(inputPosition, source.position());
                        continue;
                    }
                    if (outcome == CodecOutcome.NEEDS_INPUT) {
                        if (source.hasRemaining()) {
                            throw new IOException("Compression encoder requested input before consuming its source buffer");
                        }
                        break;
                    }
                    throw new IOException("Unexpected compression encode outcome: " + outcome);
                }
                return source.position() - sourceStart;
            } finally {
                inputBytes += source.position() - sourceStart;
            }
        }

        /// Finishes encoded output, releases the engine, and applies target ownership exactly once.
        @Override
        public void finish() throws IOException {
            if (finished) {
                targetCloser.close();
                return;
            }
            finished = true;
            open = false;

            @Nullable Throwable failure = null;
            try {
                if (frameActive) {
                    finishEngine();
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                encoder.close();
            } catch (RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            targetCloser.closeAfter(failure);
        }

        /// Returns the number of uncompressed bytes accepted from callers.
        @Override
        public long inputBytes() {
            return inputBytes;
        }

        /// Returns the number of compressed bytes written to the target.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether this channel still accepts source bytes.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Finishes the active encoding and applies target ownership.
        @Override
        public void close() throws IOException {
            finish();
        }

        /// Returns whether the current frame has encoded state that still requires finalization.
        protected final boolean frameActive() {
            return frameActive;
        }

        /// Records that the current frame has been completely emitted.
        protected final void markFrameFinished() {
            frameActive = false;
        }

        /// Drains nonterminal flush output from a flushable encoder engine.
        protected final void flushEngine(CompressionEncoder.Flushable flushableEncoder) throws IOException {
            while (true) {
                output.clear();
                CodecOutcome outcome = flushableEncoder.flush(output);
                writeOutput();
                if (outcome == CodecOutcome.FLUSHED) {
                    return;
                }
                if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                    throw new IOException("Unexpected compression flush outcome: " + outcome);
                }
                if (output.position() == 0) {
                    throw new IOException("Compression encoder requested output without producing bytes");
                }
            }
        }

        /// Drains terminal output from the underlying encoder engine.
        private void finishEngine() throws IOException {
            while (true) {
                output.clear();
                CodecOutcome outcome = encoder.finish(output);
                writeOutput();
                if (outcome == CodecOutcome.FINISHED) {
                    return;
                }
                if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                    throw new IOException("Unexpected compression finish outcome: " + outcome);
                }
                if (output.position() == 0) {
                    throw new IOException("Compression encoder requested output without producing bytes");
                }
            }
        }

        /// Drains one nonterminal frame boundary from a framed encoder engine.
        protected final void finishEngineFrame(CompressionEncoder.Framed framedEncoder) throws IOException {
            while (true) {
                output.clear();
                CodecOutcome outcome = framedEncoder.finishFrame(output);
                writeOutput();
                if (outcome == CodecOutcome.BOUNDARY_REACHED) {
                    return;
                }
                if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                    throw new IOException("Unexpected compression frame outcome: " + outcome);
                }
                if (output.position() == 0) {
                    throw new IOException("Compression encoder requested output without producing bytes");
                }
            }
        }

        /// Flips and fully writes the encoded output staging buffer.
        private void writeOutput() throws IOException {
            output.flip();
            while (output.hasRemaining()) {
                int written = target.write(output);
                if (written == 0) {
                    throw new IOException("Compression target channel made no progress");
                }
                outputBytes += written;
            }
        }

        /// Rejects a full-output outcome that neither consumed input nor produced staged output.
        private void requireProgress(int inputBefore, int inputAfter) throws IOException {
            if (inputBefore == inputAfter && output.position() == 0) {
                throw new IOException("Compression encoder made no progress");
            }
        }

        /// Requires this channel to remain open for source bytes.
        protected final void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Drives an encoder that supports nonterminal flushing.
    @NotNullByDefault
    private static final class FlushableEncodingChannel
            extends EncodingChannel
            implements CompressingWritableByteChannel.Flushable {
        /// Flush-capable encoder engine.
        private final CompressionEncoder.Flushable flushableEncoder;

        /// Creates a flush-capable encoding channel.
        private FlushableEncodingChannel(
                WritableByteChannel target,
                OwnedChannelCloser targetCloser,
                CompressionEncoder.Flushable encoder
        ) {
            super(target, targetCloser, encoder);
            this.flushableEncoder = encoder;
        }

        /// Flushes pending output without ending the active encoding.
        @Override
        public void flush() throws IOException {
            ensureOpen();
            flushEngine(flushableEncoder);
        }
    }

    /// Drives an encoder that emits independently terminated frames.
    @NotNullByDefault
    private static class FramedEncodingChannel
            extends EncodingChannel
            implements CompressingWritableByteChannel.Framed {
        /// Frame-capable encoder engine.
        private final CompressionEncoder.Framed framedEncoder;

        /// Creates a frame-capable encoding channel.
        protected FramedEncodingChannel(
                WritableByteChannel target,
                OwnedChannelCloser targetCloser,
                CompressionEncoder.Framed encoder
        ) {
            super(target, targetCloser, encoder);
            this.framedEncoder = encoder;
        }

        /// Finishes the current frame and retains the channel for following source bytes.
        @Override
        public void finishFrame() throws IOException {
            ensureOpen();
            if (!frameActive()) {
                return;
            }
            finishEngineFrame(framedEncoder);
            markFrameFinished();
        }
    }

    /// Drives a framed encoder that also supports nonterminal flushing.
    @NotNullByDefault
    private static final class FlushableFramedEncodingChannel
            extends FramedEncodingChannel
            implements CompressingWritableByteChannel.FlushableFramed {
        /// Flush-capable encoder engine.
        private final CompressionEncoder.Flushable flushableEncoder;

        /// Creates a frame- and flush-capable encoding channel.
        private FlushableFramedEncodingChannel(
                WritableByteChannel target,
                OwnedChannelCloser targetCloser,
                CompressionEncoder.FlushableFramed encoder
        ) {
            super(target, targetCloser, encoder);
            this.flushableEncoder = encoder;
        }

        /// Flushes pending output when a frame is active.
        @Override
        public void flush() throws IOException {
            ensureOpen();
            if (!frameActive()) {
                return;
            }
            flushEngine(flushableEncoder);
        }
    }
    /// Drives a decoder engine from a blocking readable channel.
    @NotNullByDefault
    private static class DecodingChannel implements DecompressingReadableByteChannel {
        /// Compressed-data source.
        private final ReadableByteChannel source;

        /// Source ownership tracker.
        private final OwnedChannelCloser sourceCloser;

        /// Transport-independent decoder engine.
        private final CompressionDecoder decoder;

        /// Whether the channel decodes concatenated frames until physical input ends.
        private final boolean concatenatedFrames;

        /// Buffered compressed input visible to the engine.
        private final ByteBuffer input = ByteBuffer.allocateDirect(BUFFER_SIZE);

        /// Number of compressed bytes logically consumed by the engine.
        private long inputBytes;

        /// Number of compressed bytes obtained from the source.
        private long sourceBytes;

        /// Number of decoded bytes returned to callers.
        private long outputBytes;

        /// Whether physical source EOF was observed.
        private boolean endOfInput;

        /// Whether the single decoded frame completed.
        private boolean frameFinished;

        /// Whether a completed frame must be reset before decoding more input.
        private boolean betweenFrames;

        /// Whether physical input ended after the final verified frame.
        private boolean streamFinished;

        /// Whether this decoding channel remains open.
        private boolean open = true;

        /// Creates a decoding channel around a fresh engine.
        protected DecodingChannel(
                ReadableByteChannel source,
                OwnedChannelCloser sourceCloser,
                CompressionDecoder decoder,
                boolean concatenatedFrames
        ) {
            this.source = source;
            this.sourceCloser = sourceCloser;
            this.decoder = decoder;
            this.concatenatedFrames = concatenatedFrames;
            input.limit(0);
        }

        /// Reads decoded bytes and continues across frame boundaries when supported.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!target.hasRemaining()) {
                ensureOpen();
                return 0;
            }
            int start = target.position();
            CodecResult result = decode(target);
            int produced = target.position() - start;
            if (produced != 0) {
                return produced;
            }
            return result.status() == CodecResult.Status.END_OF_INPUT ? -1 : 0;
        }

        /// Decodes bytes and continues across frame boundaries when supported.
        @Override
        public CodecResult decode(ByteBuffer target) throws IOException {
            return decodeInternal(target, false);
        }

        /// Decodes bytes with optional frame-boundary reporting.
        protected final CodecResult decodeInternal(
                ByteBuffer target,
                boolean stopAtFrame
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            long inputStart = inputBytes;
            long outputStart = outputBytes;
            if (streamFinished) {
                return new CodecResult(0L, 0L, CodecResult.Status.END_OF_INPUT);
            }
            if (frameFinished) {
                CodecResult.Status status = stopAtFrame
                        ? CodecResult.Status.FRAME_FINISHED
                        : CodecResult.Status.END_OF_INPUT;
                return new CodecResult(0L, 0L, status);
            }
            if (!target.hasRemaining()) {
                return new CodecResult(0L, 0L, CodecResult.Status.ACTIVE);
            }
            if (betweenFrames && !beginNextFrame()) {
                return new CodecResult(0L, 0L, CodecResult.Status.END_OF_INPUT);
            }

            while (true) {
                if (!input.hasRemaining() && !endOfInput) {
                    readInput();
                }
                if (concatenatedFrames && endOfInput && !input.hasRemaining() && inputBytes == 0L) {
                    streamFinished = true;
                    return new CodecResult(0L, 0L, CodecResult.Status.END_OF_INPUT);
                }
                int inputPosition = input.position();
                int outputPosition = target.position();
                CodecOutcome outcome = decoder.decode(input, target, endOfInput);
                inputBytes += input.position() - inputPosition;
                outputBytes += target.position() - outputPosition;

                if (outcome == CodecOutcome.FINISHED) {
                    if (!concatenatedFrames) {
                        frameFinished = true;
                        CodecResult.Status status = stopAtFrame
                                ? CodecResult.Status.FRAME_FINISHED
                                : CodecResult.Status.END_OF_INPUT;
                        return new CodecResult(inputBytes - inputStart, outputBytes - outputStart, status);
                    }
                    betweenFrames = true;
                    if (stopAtFrame) {
                        return new CodecResult(
                                inputBytes - inputStart,
                                outputBytes - outputStart,
                                CodecResult.Status.FRAME_FINISHED
                        );
                    }
                    if (!target.hasRemaining()) {
                        return new CodecResult(
                                inputBytes - inputStart,
                                outputBytes - outputStart,
                                CodecResult.Status.ACTIVE
                        );
                    }
                    if (!beginNextFrame()) {
                        return new CodecResult(
                                inputBytes - inputStart,
                                outputBytes - outputStart,
                                CodecResult.Status.END_OF_INPUT
                        );
                    }
                    continue;
                }
                if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
                    if (decoder instanceof CompressionDecoder.DictionaryAware<?, ?> dictionaryDecoder) {
                        throw new DictionaryRequiredException(dictionaryDecoder.dictionaryRequest());
                    }
                    throw new IOException("Compression decoder requested a dictionary without exposing its request");
                }
                if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    if (target.position() == outputPosition && target.hasRemaining()) {
                        throw new IOException("Compression decoder requested output without filling its target buffer");
                    }
                    return new CodecResult(
                            inputBytes - inputStart,
                            outputBytes - outputStart,
                            CodecResult.Status.ACTIVE
                    );
                }
                if (outcome != CodecOutcome.NEEDS_INPUT) {
                    throw new IOException("Unexpected compression decode outcome: " + outcome);
                }
                if (input.hasRemaining()) {
                    throw new IOException("Compression decoder requested input before consuming its source buffer");
                }
                if (target.position() != outputPosition) {
                    return new CodecResult(
                            inputBytes - inputStart,
                            outputBytes - outputStart,
                            CodecResult.Status.ACTIVE
                    );
                }
            }
        }

        /// Returns compressed bytes logically consumed by the engine.
        @Override
        public long inputBytes() {
            return inputBytes;
        }

        /// Returns compressed bytes obtained from the source.
        @Override
        public long sourceBytes() {
            return sourceBytes;
        }

        /// Returns a read-only view of source bytes fetched but not consumed.
        @Override
        public @UnmodifiableView ByteBuffer unconsumedInput() {
            return input.asReadOnlyBuffer();
        }

        /// Returns decoded bytes delivered to callers.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether this decoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Releases the engine and applies source ownership exactly once.
        @Override
        public void close() throws IOException {
            if (!open) {
                sourceCloser.close();
                return;
            }
            open = false;
            @Nullable Throwable failure = null;
            try {
                decoder.close();
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
            sourceCloser.closeAfter(failure);
        }

        /// Resets the engine at a verified boundary and obtains input for the next frame.
        private boolean beginNextFrame() throws IOException {
            decoder.reset();
            betweenFrames = false;
            if (!input.hasRemaining() && !endOfInput) {
                readInput();
            }
            boolean available = input.hasRemaining();
            if (!available && endOfInput) {
                streamFinished = true;
            }
            return available;
        }

        /// Refills the compressed input staging buffer or records physical EOF.
        private void readInput() throws IOException {
            input.clear();
            int read = source.read(input);
            if (read < 0) {
                endOfInput = true;
                input.limit(0);
                return;
            }
            if (read == 0) {
                throw new IOException("Compression source channel made no progress");
            }
            sourceBytes += read;
            input.flip();
        }

        /// Requires this decoding channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Drives a decoder that can stop at independently terminated frame boundaries.
    @NotNullByDefault
    private static final class FramedDecodingChannel
            extends DecodingChannel
            implements DecompressingReadableByteChannel.Framed {
        /// Creates a frame-capable decoding channel.
        private FramedDecodingChannel(
                ReadableByteChannel source,
                OwnedChannelCloser sourceCloser,
                CompressionDecoder.Framed decoder
        ) {
            super(source, sourceCloser, decoder, true);
        }

        /// Decodes through the end of the current frame without starting a following frame.
        @Override
        public CodecResult decodeFrame(ByteBuffer target) throws IOException {
            return decodeInternal(target, true);
        }
    }
    /// Combines lifecycle failures while preserving the first failure as primary.
    private static Throwable mergeFailure(@Nullable Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }
}
