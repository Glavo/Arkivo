// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests defensive validation of engine outcomes used by one-shot byte-buffer operations.
@NotNullByDefault
public final class ByteBufferEngineContractTest {
    /// A dictionary request shared by dictionary-aware scripted decoders.
    private static final DictionaryRequest<RawCompressionDictionary> DICTIONARY_REQUEST = dictionary -> true;

    /// Verifies inconsistent encode outcomes fail identically for allocating and fixed targets.
    @Test
    public void rejectsInvalidEncodeOutcomes() {
        assertEncoderFailure(
                encoderCodec(
                        (source, target) -> {
                            source.get();
                            return CodecOutcome.NEEDS_INPUT;
                        },
                        target -> CodecOutcome.FINISHED
                ),
                "Compression encoder requested input before consuming its source buffer"
        );
        assertEncoderFailure(
                encoderCodec(
                        (source, target) -> {
                            source.get();
                            return CodecOutcome.FINISHED;
                        },
                        target -> CodecOutcome.FINISHED
                ),
                "Unexpected compression encode outcome: FINISHED"
        );
        assertEncoderFailure(
                encoderCodec(
                        (source, target) -> {
                            target.put((byte) 1);
                            return CodecOutcome.NEEDS_OUTPUT;
                        },
                        target -> CodecOutcome.FINISHED
                ),
                "Compression encoder requested output without filling its target buffer"
        );
        assertEncoderFailure(
                encoderCodec(
                        (source, target) -> CodecOutcome.NEEDS_OUTPUT,
                        target -> CodecOutcome.FINISHED
                ),
                "Compression encoder made no progress"
        );
    }

    /// Verifies inconsistent finish outcomes fail identically for allocating and fixed targets.
    @Test
    public void rejectsInvalidFinishOutcomes() {
        EncoderStep consumeSource = (source, target) -> {
            source.position(source.limit());
            return CodecOutcome.NEEDS_INPUT;
        };
        assertEncoderFailure(
                encoderCodec(consumeSource, target -> CodecOutcome.FLUSHED),
                "Unexpected compression finish outcome: FLUSHED"
        );
        assertEncoderFailure(
                encoderCodec(consumeSource, target -> CodecOutcome.NEEDS_OUTPUT),
                "Compression encoder made no finishing progress"
        );
        assertEncoderFailure(
                encoderCodec(
                        consumeSource,
                        target -> {
                            target.put((byte) 1);
                            return CodecOutcome.NEEDS_OUTPUT;
                        }
                ),
                "Compression encoder requested output without filling its target buffer"
        );
    }

    /// Verifies invalid decoder continuation outcomes produce stable checked failures.
    @Test
    public void rejectsInvalidDecodeOutcomes() {
        assertDecoderFailure(
                decoderCodec(() -> new ScriptedDecoder(
                        (source, target) -> CodecOutcome.NEEDS_INPUT
                )),
                "Compression decoder requested input before consuming its source buffer"
        );
        assertDecoderFailure(
                decoderCodec(() -> new ScriptedDecoder(
                        (source, target) -> {
                            source.position(source.limit());
                            return CodecOutcome.NEEDS_INPUT;
                        }
                )),
                "Compression decoder requested input after end of input"
        );
        assertDecoderFailure(
                decoderCodec(() -> new ScriptedDecoder(
                        (source, target) -> CodecOutcome.FLUSHED
                )),
                "Unexpected compression decode outcome: FLUSHED"
        );
        assertDecoderFailure(
                decoderCodec(() -> new ScriptedDecoder(
                        (source, target) -> CodecOutcome.NEEDS_OUTPUT
                )),
                "Compression decoder requested output without producing target bytes"
        );
        assertDecoderFailure(
                decoderCodec(() -> new ScriptedDecoder(
                        (source, target) -> CodecOutcome.NEEDS_DICTIONARY
                )),
                "Compression decoder requested a dictionary without exposing its request"
        );
    }

    /// Verifies dictionary-aware decoder requests are preserved in the public checked exception.
    @Test
    public void exposesDictionaryRequests() {
        TestCodec codec = decoderCodec(RequestingDecoder::new).withMaximumOutputSize(8L);
        DictionaryRequiredException allocating = assertThrows(
                DictionaryRequiredException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1}))
        );
        assertSame(DICTIONARY_REQUEST, allocating.request());

        DictionaryRequiredException fixed = assertThrows(
                DictionaryRequiredException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.allocate(8))
        );
        assertSame(DICTIONARY_REQUEST, fixed.request());
    }

    /// Verifies concatenated-frame decoding requires progress before resetting a completed frame.
    @Test
    public void framedDecoderMustConsumeOrProduceBeforeReset() {
        TestCodec codec = decoderCodec(() -> new ScriptedFramedDecoder(
                (source, target) -> CodecOutcome.FINISHED
        )).withMaximumOutputSize(8L);

        IOException allocating = assertThrows(
                IOException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1, 2}))
        );
        assertEquals("Compression decoder made no progress", allocating.getMessage());

        IOException fixed = assertThrows(
                IOException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.allocate(8))
        );
        assertEquals("Compression decoder made no progress", fixed.getMessage());
    }

    /// Verifies a temporary operation limit is restored while partial target progress remains observable.
    @Test
    public void fixedDecodeRestoresTargetLimitAfterFailure() {
        IOException failure = new IOException("injected decode failure");
        TestCodec codec = decoderCodec(() -> new ScriptedDecoder((source, target) -> {
            assertEquals(2, target.remaining());
            source.get();
            target.put((byte) 77);
            throw failure;
        })).withMaximumOutputSize(2L);
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
        ByteBuffer target = ByteBuffer.allocate(8);
        target.position(1);
        target.limit(6);

        assertSame(failure, assertThrows(IOException.class, () -> codec.decompress(source, target)));
        assertEquals(1, source.position());
        assertEquals(2, target.position());
        assertEquals(6, target.limit());
        assertEquals(77, Byte.toUnsignedInt(target.get(1)));
    }

    /// Verifies allocating compression grows for terminal output while fixed compression reports exhaustion.
    @Test
    public void handlesTerminalOutputLargerThanTheInitialTarget() throws IOException {
        TestCodec codec = new TestCodec(
                FinishPayloadEncoder::new,
                () -> new ScriptedDecoder((source, target) -> CodecOutcome.FINISHED),
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE
        );
        ByteBuffer source = ByteBuffer.wrap(new byte[]{7});

        ByteBuffer compressed = codec.compress(source);
        assertEquals(1, source.position());
        assertEquals(FinishPayloadEncoder.PAYLOAD_SIZE, compressed.remaining());
        while (compressed.hasRemaining()) {
            assertEquals(FinishPayloadEncoder.PAYLOAD_BYTE, compressed.get());
        }

        ByteBuffer fixedSource = ByteBuffer.wrap(new byte[]{8});
        ByteBuffer fixedTarget = ByteBuffer.allocate(16);
        assertThrows(BufferOverflowException.class, () -> codec.compress(fixedSource, fixedTarget));
        assertEquals(1, fixedSource.position());
        assertEquals(fixedTarget.limit(), fixedTarget.position());
    }

    /// Verifies empty input completes without entering either framed decoder loop.
    @Test
    public void handlesEmptyInputWithoutInvokingFramedDecoding() throws IOException {
        TestCodec codec = decoderCodec(() -> new ScriptedFramedDecoder((source, target) -> {
            throw new AssertionError("Decoder operation must not run for empty input");
        })).withMaximumOutputSize(0L);

        ByteBuffer compressed = codec.compress(ByteBuffer.allocate(0));
        assertEquals(0, compressed.remaining());

        ByteBuffer fixedCompressed = ByteBuffer.allocate(0);
        codec.compress(ByteBuffer.allocate(0), fixedCompressed);
        assertEquals(0, fixedCompressed.position());

        assertEquals(0, codec.decompress(ByteBuffer.allocate(0)).remaining());
        ByteBuffer target = ByteBuffer.allocate(4);
        target.position(2);
        codec.decompress(ByteBuffer.allocate(0), target);
        assertEquals(2, target.position());
    }

    /// Verifies fixed-target decompression resets a framed decoder across every concatenated frame.
    @Test
    public void decodesConcatenatedFramesIntoFixedTargets() throws IOException {
        TestCodec codec = decoderCodec(() -> new ScriptedFramedDecoder((source, target) -> {
            target.put(source.get());
            return CodecOutcome.FINISHED;
        }));
        ByteBuffer source = ByteBuffer.wrap(new byte[]{11, 22, 33});
        ByteBuffer target = ByteBuffer.allocate(5);
        target.position(1);

        codec.decompress(source, target);

        assertEquals(source.limit(), source.position());
        assertEquals(4, target.position());
        assertEquals(11, Byte.toUnsignedInt(target.get(1)));
        assertEquals(22, Byte.toUnsignedInt(target.get(2)));
        assertEquals(33, Byte.toUnsignedInt(target.get(3)));
    }

    /// Verifies fixed decompression reports the configured output limit before spare target capacity.
    @Test
    public void distinguishesOutputLimitsFromTargetCapacity() {
        TestCodec codec = decoderCodec(() -> new ScriptedDecoder((source, target) -> {
            target.put(source.get());
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED;
        })).withMaximumOutputSize(1L);
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1, 2});
        ByteBuffer target = ByteBuffer.allocate(8);
        target.position(2);
        target.limit(7);

        DecompressionLimitException failure = assertThrows(
                DecompressionLimitException.class,
                () -> codec.decompress(source, target)
        );
        assertEquals(1L, failure.maximum());
        assertEquals(2, source.position());
        assertEquals(3, target.position());
        assertEquals(7, target.limit());
        assertEquals(1, Byte.toUnsignedInt(target.get(2)));
    }

    /// Verifies allocating compression honors usable bounds and safely ignores bounds too large for a ByteBuffer.
    @Test
    public void usesRepresentableCompressionSizeBounds() throws IOException {
        TestCodec bounded = new TestCodec(
                () -> new ScriptedEncoder(
                        (source, target) -> {
                            assertEquals(8, target.remaining());
                            source.position(source.limit());
                            return CodecOutcome.NEEDS_INPUT;
                        },
                        target -> CodecOutcome.FINISHED
                ),
                () -> new ScriptedDecoder((source, target) -> CodecOutcome.FINISHED),
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                8L
        );
        assertEquals(0, bounded.compress(ByteBuffer.wrap(new byte[]{1})).remaining());

        TestCodec oversized = new TestCodec(
                TerminalEncoder::new,
                () -> new ScriptedDecoder((source, target) -> CodecOutcome.FINISHED),
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                (long) Integer.MAX_VALUE + 1L
        );
        assertEquals(0, oversized.compress(ByteBuffer.allocate(0)).remaining());
    }

    /// Verifies fixed compression and decompression validate source-target relationships symmetrically.
    @Test
    public void validatesFixedBufferArgumentsBeforeOpeningEngines() {
        TestCodec codec = new TestCodec(
                () -> {
                    throw new AssertionError("Encoder must not be opened");
                },
                () -> {
                    throw new AssertionError("Decoder must not be opened");
                },
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE
        );
        ByteBuffer same = ByteBuffer.allocate(1);
        ByteBuffer writable = ByteBuffer.allocate(1);
        ByteBuffer readOnly = ByteBuffer.allocate(1).asReadOnlyBuffer();

        assertThrows(NullPointerException.class, () -> codec.compress(null, writable));
        assertThrows(NullPointerException.class, () -> codec.compress(writable, null));
        assertThrows(IllegalArgumentException.class, () -> codec.compress(same, same));
        assertThrows(ReadOnlyBufferException.class, () -> codec.compress(writable, readOnly));
        assertThrows(NullPointerException.class, () -> codec.decompress(null, writable));
        assertThrows(NullPointerException.class, () -> codec.decompress(writable, null));
        assertThrows(IllegalArgumentException.class, () -> codec.decompress(same, same));
        assertThrows(ReadOnlyBufferException.class, () -> codec.decompress(writable, readOnly));
    }

    /// Asserts one malformed encoder fails through both one-shot target modes with the expected reason.
    private static void assertEncoderFailure(TestCodec codec, String expectedMessage) {
        assertFailureMessage(
                expectedMessage,
                () -> codec.compress(ByteBuffer.wrap(new byte[]{1, 2}))
        );
        assertFailureMessage(
                expectedMessage,
                () -> codec.compress(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.allocate(128))
        );
    }

    /// Asserts one malformed decoder fails through both one-shot target modes with the expected reason.
    private static void assertDecoderFailure(TestCodec codec, String expectedMessage) {
        TestCodec bounded = codec.withMaximumOutputSize(16L);
        assertFailureMessage(
                expectedMessage,
                () -> bounded.decompress(ByteBuffer.wrap(new byte[]{1, 2}))
        );
        assertFailureMessage(
                expectedMessage,
                () -> bounded.decompress(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.allocate(16))
        );
    }

    /// Asserts a checked operation fails with one exact diagnostic message.
    private static void assertFailureMessage(String expectedMessage, Executable operation) {
        IOException failure = assertThrows(IOException.class, operation);
        assertEquals(expectedMessage, failure.getMessage());
    }

    /// Returns a codec that creates a new scripted encoder for each operation.
    private static TestCodec encoderCodec(EncoderStep encodeStep, FinishStep finishStep) {
        return new TestCodec(
                () -> new ScriptedEncoder(encodeStep, finishStep),
                () -> new ScriptedDecoder((source, target) -> CodecOutcome.FINISHED),
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE
        );
    }

    /// Returns a codec that creates a new decoder from the supplied factory for each operation.
    private static TestCodec decoderCodec(Supplier<? extends CompressionDecoder> decoderFactory) {
        return new TestCodec(
                TerminalEncoder::new,
                decoderFactory,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE
        );
    }

    /// Performs one scripted encoder input operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface EncoderStep {
        /// Processes the supplied source and target and returns a scripted outcome.
        CodecOutcome apply(ByteBuffer source, ByteBuffer target) throws IOException;
    }

    /// Performs one scripted encoder finish operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface FinishStep {
        /// Processes the supplied target and returns a scripted outcome.
        CodecOutcome apply(ByteBuffer target) throws IOException;
    }

    /// Performs one scripted decoder operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface DecoderStep {
        /// Processes the supplied source and target and returns a scripted outcome.
        CodecOutcome apply(ByteBuffer source, ByteBuffer target) throws IOException;
    }

    /// Delegates encoder operations to fixed test scripts.
    @NotNullByDefault
    private static final class ScriptedEncoder implements CompressionEncoder {
        /// Script used for source encoding.
        private final EncoderStep encodeStep;

        /// Script used for terminal finalization.
        private final FinishStep finishStep;

        /// Creates an encoder from two operation scripts.
        private ScriptedEncoder(EncoderStep encodeStep, FinishStep finishStep) {
            this.encodeStep = encodeStep;
            this.finishStep = finishStep;
        }

        /// Executes the source-encoding script.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
            return encodeStep.apply(source, target);
        }

        /// Executes the terminal-finalization script.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            return finishStep.apply(target);
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Consumes every source byte and finishes without producing output.
    @NotNullByDefault
    private static final class TerminalEncoder implements CompressionEncoder {
        /// Consumes the complete source.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            source.position(source.limit());
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Completes terminal finalization.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            return CodecOutcome.FINISHED;
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Emits a payload entirely during terminal finalization.
    @NotNullByDefault
    private static final class FinishPayloadEncoder implements CompressionEncoder {
        /// The number of terminal payload bytes emitted by each encoder.
        private static final int PAYLOAD_SIZE = 80;

        /// The repeated terminal payload byte.
        private static final byte PAYLOAD_BYTE = 91;

        /// The number of terminal bytes still pending.
        private int remaining = PAYLOAD_SIZE;

        /// Consumes every source byte without producing immediate output.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            source.position(source.limit());
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Emits pending payload bytes and requests another target only after filling the current one.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            int count = Math.min(remaining, target.remaining());
            for (int index = 0; index < count; index++) {
                target.put(PAYLOAD_BYTE);
            }
            remaining -= count;
            return remaining == 0 ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_OUTPUT;
        }

        /// Restores the complete terminal payload.
        @Override
        public void reset() {
            remaining = PAYLOAD_SIZE;
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Delegates decoder operations to one fixed test script.
    @NotNullByDefault
    private static class ScriptedDecoder implements CompressionDecoder {
        /// Script used for every decode operation.
        private final DecoderStep step;

        /// Creates a decoder from one operation script.
        private ScriptedDecoder(DecoderStep step) {
            this.step = step;
        }

        /// Executes the decode script.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
            return step.apply(source, target);
        }

        /// Executes the decode script with asserted end of input.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
            return step.apply(source, target);
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Marks a scripted decoder as supporting concatenated frames.
    @NotNullByDefault
    private static final class ScriptedFramedDecoder extends ScriptedDecoder implements CompressionDecoder.Framed {
        /// Creates a framed decoder from one operation script.
        private ScriptedFramedDecoder(DecoderStep step) {
            super(step);
        }
    }

    /// Always requests one known raw dictionary.
    @NotNullByDefault
    private static final class RequestingDecoder
            implements CompressionDecoder.DictionaryAware<
                    RawCompressionDictionary,
                    DictionaryRequest<RawCompressionDictionary>
                    > {
        /// Reports that a dictionary is required.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            return CodecOutcome.NEEDS_DICTIONARY;
        }

        /// Reports that a dictionary is required with asserted end of input.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            return CodecOutcome.NEEDS_DICTIONARY;
        }

        /// Returns the shared dictionary request.
        @Override
        public DictionaryRequest<RawCompressionDictionary> dictionaryRequest() {
            return DICTIONARY_REQUEST;
        }

        /// Accepts the supplied dictionary without retaining it.
        @Override
        public void provideDictionary(RawCompressionDictionary dictionary) {
            Objects.requireNonNull(dictionary, "dictionary");
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Supplies fresh scripted engines and immutable decompression limits.
    @NotNullByDefault
    private static final class TestCodec implements CompressionCodec<TestCodec>, CompressionFormat {
        /// Factory for a fresh encoder.
        private final Supplier<? extends CompressionEncoder> encoderFactory;

        /// Factory for a fresh decoder.
        private final Supplier<? extends CompressionDecoder> decoderFactory;

        /// Maximum decoded output size.
        private final long maximumOutputSize;

        /// Maximum decoder history-window size.
        private final long maximumWindowSize;

        /// Maximum decoder working-memory size.
        private final long maximumMemorySize;

        /// Maximum encoded size reported for every nonnegative source size.
        private final long maximumCompressedSize;

        /// Creates one immutable scripted codec configuration.
        private TestCodec(
                Supplier<? extends CompressionEncoder> encoderFactory,
                Supplier<? extends CompressionDecoder> decoderFactory,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            this(
                    encoderFactory,
                    decoderFactory,
                    maximumOutputSize,
                    maximumWindowSize,
                    maximumMemorySize,
                    CompressionCodec.UNKNOWN_SIZE
            );
        }

        /// Creates one immutable scripted codec configuration with an explicit compressed-size bound.
        private TestCodec(
                Supplier<? extends CompressionEncoder> encoderFactory,
                Supplier<? extends CompressionDecoder> decoderFactory,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize,
                long maximumCompressedSize
        ) {
            this.encoderFactory = encoderFactory;
            this.decoderFactory = decoderFactory;
            this.maximumOutputSize = maximumOutputSize;
            this.maximumWindowSize = maximumWindowSize;
            this.maximumMemorySize = maximumMemorySize;
            this.maximumCompressedSize = maximumCompressedSize;
        }

        /// Returns the configured decoded-output limit.
        @Override
        public long maximumOutputSize() {
            return maximumOutputSize;
        }

        /// Returns the configured history-window limit.
        @Override
        public long maximumWindowSize() {
            return maximumWindowSize;
        }

        /// Returns the configured decoder-memory limit.
        @Override
        public long maximumMemorySize() {
            return maximumMemorySize;
        }

        /// Returns the configured encoded-size bound for every valid source size.
        @Override
        public long maxCompressedSize(long sourceSize) {
            if (sourceSize < 0L) {
                throw new IllegalArgumentException("sourceSize must not be negative");
            }
            return maximumCompressedSize;
        }

        /// Returns a codec with the requested decoded-output limit.
        @Override
        public TestCodec withMaximumOutputSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumOutputSize");
            return value == maximumOutputSize
                    ? this
                    : new TestCodec(
                            encoderFactory,
                            decoderFactory,
                            value,
                            maximumWindowSize,
                            maximumMemorySize,
                            maximumCompressedSize
                    );
        }

        /// Returns a codec with the requested history-window limit.
        @Override
        public TestCodec withMaximumWindowSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumWindowSize");
            return value == maximumWindowSize
                    ? this
                    : new TestCodec(
                            encoderFactory,
                            decoderFactory,
                            maximumOutputSize,
                            value,
                            maximumMemorySize,
                            maximumCompressedSize
                    );
        }

        /// Returns a codec with the requested decoder-memory limit.
        @Override
        public TestCodec withMaximumMemorySize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumMemorySize");
            return value == maximumMemorySize
                    ? this
                    : new TestCodec(
                            encoderFactory,
                            decoderFactory,
                            maximumOutputSize,
                            maximumWindowSize,
                            value,
                            maximumCompressedSize
                    );
        }

        /// Returns the scripted test format name.
        @Override
        public String name() {
            return "scripted";
        }

        /// Returns this codec as its format identity.
        @Override
        public CompressionFormat format() {
            return this;
        }

        /// Returns this codec as the format's default configuration.
        @Override
        public CompressionCodec<?> defaultCodec() {
            return this;
        }

        /// Creates a fresh scripted encoder.
        @Override
        public CompressionEncoder newEncoder(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
            return encoderFactory.get();
        }

        /// Creates a fresh scripted decoder.
        @Override
        public CompressionDecoder newDecoder() {
            return decoderFactory.get();
        }
    }
}
