// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies argument validation, reset, completed decoding, and closed decoder states across installed buffer codecs.
@NotNullByDefault
final class CodecEngineResetContractTest {
    /// Number of decoded bytes in every independently encoded test stream.
    private static final int CONTENT_SIZE = 8_193;

    /// First complete stream processed before a reset.
    private static final byte @Unmodifiable [] FIRST_CONTENT = createContent(17);

    /// Second complete stream processed after a reset.
    private static final byte @Unmodifiable [] SECOND_CONTENT = createContent(43);

    /// Content used only to leave an engine in an active state before resetting it.
    private static final byte @Unmodifiable [] ABANDONED_CONTENT = createContent(91);

    /// Verifies completed and active encodings and decodings can be discarded before reusing each engine.
    @Test
    void resetsCompletedAndActiveStreamsAcrossEveryCodec() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            String context = format.name();

            byte[] firstEncoded;
            byte[] secondEncoded;
            byte[] recoveredEncoded;
            try (CompressionEncoder encoder = codec.newEncoder()) {
                firstEncoded = encode(encoder, FIRST_CONTENT, context);

                encoder.reset();
                secondEncoded = encode(encoder, SECOND_CONTENT, context);

                encoder.reset();
                beginEncoding(encoder, ABANDONED_CONTENT, context);
                encoder.reset();
                recoveredEncoded = encode(encoder, FIRST_CONTENT, context);
            }

            CompressionCodec<?> decoderCodec = CodecContractConfigurations
                    .decoderCodec(codec, CONTENT_SIZE)
                    .withMaximumOutputSize(CONTENT_SIZE);
            try (CompressionDecoder decoder = decoderCodec.newDecoder()) {
                assertArrayEquals(FIRST_CONTENT, decode(decoder, firstEncoded, context), context);

                decoder.reset();
                assertArrayEquals(SECOND_CONTENT, decode(decoder, secondEncoded, context), context);

                decoder.reset();
                beginDecoding(decoder, recoveredEncoded, context);
                decoder.reset();
                assertArrayEquals(FIRST_CONTENT, decode(decoder, recoveredEncoded, context), context);
            }
        }
    }

    /// Verifies reset discards failed decoding after an empty target and a separately reported end of input.
    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFormats")
    void resetsAfterTruncatedInput(String formatName) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        byte[] encoded;
        try (CompressionEncoder encoder = codec.newEncoder()) {
            encoded = encode(encoder, FIRST_CONTENT, formatName);
        }
        // Unix compress has no end marker; a shorter sequence of complete codes can be a valid stream.
        int[] prefixSizes = switch (formatName) {
            case "compress" -> new int[]{1};
            case "gzip", "zlib", "lzip", "lz4" -> new int[]{1, encoded.length / 2, encoded.length - 1};
            default -> new int[]{1, encoded.length / 2};
        };
        CompressionCodec<?> configured = CodecContractConfigurations.decoderCodec(codec, CONTENT_SIZE)
                .withMaximumOutputSize(CONTENT_SIZE);
        try (CompressionDecoder decoder = configured.newDecoder()) {
            for (int prefixSize : prefixSizes) {
                for (boolean incremental : new boolean[]{false, true}) {
                    String context = formatName + " prefix=" + prefixSize + " incremental=" + incremental;
                    ByteBuffer source = ByteBuffer.allocateDirect(prefixSize + 4);
                    source.position(2).put(encoded, 0, prefixSize).flip().position(2);
                    ByteBuffer input = source.asReadOnlyBuffer();
                    ByteArrayOutputStream partial = new ByteArrayOutputStream();
                    assertThrows(IOException.class, () -> {
                        for (int calls = 0; calls < CONTENT_SIZE; calls++) {
                            int capacity = calls == 0 ? 0 : 31;
                            ByteBuffer target = ByteBuffer.allocate(capacity);
                            CodecOutcome outcome;
                            try {
                                outcome = incremental && input.hasRemaining()
                                        ? decoder.decode(input, target) : decoder.finish(input, target);
                            } finally {
                                assertEquals(prefixSize + 2, input.limit(), context);
                                assertEquals(capacity, target.limit(), context);
                                drain(target, partial);
                            }
                            if (outcome == CodecOutcome.FINISHED) {
                                fail("Truncated stream completed: " + context);
                            }
                            assertTrue(outcome == CodecOutcome.NEEDS_INPUT
                                    || outcome == CodecOutcome.NEEDS_OUTPUT, context);
                        }
                        fail("Decoder did not report truncation: " + context);
                    }, context);
                    assertTrue(partial.size() <= CONTENT_SIZE, context);
                    assertArrayEquals(Arrays.copyOf(FIRST_CONTENT, partial.size()), partial.toByteArray(), context);
                    assertEquals(2, source.position(), context);

                    decoder.reset();
                    decoder.reset();
                    assertArrayEquals(FIRST_CONTENT, decode(decoder, encoded, context), context);
                    decoder.reset();
                }
            }
        }
    }

    /// Returns each installed format as a separately reported reset test invocation.
    private static Stream<String> installedFormats() {
        return CompressionFormats.installed().stream().map(CompressionFormat::name);
    }

    /// Invalid buffer arguments do not start a boundary operation or consume any input.
    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFormats")
    @SuppressWarnings("DataFlowIssue")
    void encoderNullArgumentsPreserveInitialState(String formatName) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        @UnmodifiableView ByteBuffer source = ByteBuffer.wrap(FIRST_CONTENT).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(7);
        byte[] encoded;
        try (var encoder = codec.newEncoder()) {
            assertThrows(NullPointerException.class, () -> encoder.encode(null, target));
            assertThrows(NullPointerException.class, () -> encoder.encode(source, null));
            assertThrows(NullPointerException.class, () -> encoder.finish(null));
            if (encoder instanceof CompressionEncoder.Flushable flushable) {
                assertThrows(NullPointerException.class, () -> flushable.flush(null));
            }
            if (encoder instanceof CompressionEncoder.Framed framed) {
                assertThrows(NullPointerException.class, () -> framed.finishFrame(null));
                assertThrows(NullPointerException.class, () -> framed.startFrame(null));
            }
            assertEquals(0, source.position());
            assertEquals(0, target.position());
            assertArrayEquals(new byte[7], target.array());
            encoded = encode(encoder, FIRST_CONTENT, formatName);
        }
        try (var decoder = CodecContractConfigurations.decoderCodec(codec, CONTENT_SIZE).newDecoder()) {
            assertArrayEquals(FIRST_CONTENT, decode(decoder, encoded, formatName));
        }
    }

    /// Null inputs or outputs leave a new decoder usable without reset or lost compressed input.
    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFormats")
    @SuppressWarnings("DataFlowIssue")
    void decoderNullArgumentsPreserveInitialState(String formatName) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        byte[] encoded;
        try (var encoder = codec.newEncoder()) {
            encoded = encode(encoder, FIRST_CONTENT, formatName);
        }
        @UnmodifiableView ByteBuffer source = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(7);
        try (var decoder = CodecContractConfigurations.decoderCodec(codec, CONTENT_SIZE).newDecoder()) {
            assertThrows(NullPointerException.class, () -> decoder.decode(null, target));
            assertThrows(NullPointerException.class, () -> decoder.decode(source, null));
            assertThrows(NullPointerException.class, () -> decoder.finish(null, target));
            assertThrows(NullPointerException.class, () -> decoder.finish(source, null));
            assertEquals(0, source.position());
            assertEquals(0, target.position());
            assertArrayEquals(new byte[7], target.array());
            assertArrayEquals(FIRST_CONTENT, decode(decoder, encoded, formatName));
        }
    }

    /// Completed decoders remain finished until reset, and closed decoders reject all stateful operations.
    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFormats")
    void decoderFinishedAndClosedStates(String formatName) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        byte[] encoded;
        try (var encoder = codec.newEncoder()) {
            encoded = encode(encoder, FIRST_CONTENT, formatName);
        }
        try (var decoder = CodecContractConfigurations.decoderCodec(codec, CONTENT_SIZE).newDecoder()) {
            assertArrayEquals(FIRST_CONTENT, decode(decoder, encoded, formatName));
            assertEquals(CodecOutcome.FINISHED, decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
            decoder.close();
            decoder.close();
            assertThrows(IllegalStateException.class, decoder::reset);
            assertClosedDecoderOperation(() -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
            assertClosedDecoderOperation(() -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
        }
    }

    /// Accepts state or channel-closure errors without treating unrelated decoding failures as closure rejection.
    private static void assertClosedDecoderOperation(Executable operation) {
        Exception failure = assertThrows(Exception.class, operation);
        assertTrue(failure instanceof IllegalStateException || failure instanceof ClosedChannelException,
                () -> "Unexpected closed decoder failure: " + failure);
    }

    /// Encodes one complete stream through a reusable engine with bounded direct targets.
    private static byte @Unmodifiable [] encode(
            CompressionEncoder encoder,
            byte @Unmodifiable [] content,
            String context
    ) throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(content.length);
        source.put(content).flip();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        while (source.hasRemaining()) {
            ByteBuffer target = ByteBuffer.allocateDirect(127);
            int sourcePosition = source.position();
            CodecOutcome outcome = encoder.encode(source, target);
            int produced = drain(target, encoded);
            assertTrue(source.position() > sourcePosition || produced > 0, context);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT, context);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(source.limit(), source.position(), context);
            } else {
                assertEquals(target.capacity(), produced, context);
            }
        }

        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(127);
            CodecOutcome outcome = encoder.finish(target);
            int produced = drain(target, encoded);
            if (outcome == CodecOutcome.FINISHED) {
                return encoded.toByteArray();
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome, context);
            assertEquals(target.capacity(), produced, context);
            assertTrue(produced > 0, context);
        }
    }

    /// Starts but deliberately does not finish an encoding before the caller resets the engine.
    private static void beginEncoding(
            CompressionEncoder encoder,
            byte @Unmodifiable [] content,
            String context
    ) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(content);
        ByteBuffer target = ByteBuffer.allocateDirect(1);
        CodecOutcome outcome = encoder.encode(source, target);
        assertTrue(source.position() > 0 || target.position() > 0, context);
        assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT, context);
    }

    /// Decodes one final source buffer through a reusable engine with bounded direct targets.
    private static byte @Unmodifiable [] decode(
            CompressionDecoder decoder,
            byte @Unmodifiable [] encoded,
            String context
    ) throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length);
        source.put(encoded).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(113);
            int sourcePosition = source.position();
            CodecOutcome outcome = decoder.finish(source, target);
            int produced = drain(target, decoded);
            assertTrue(source.position() > sourcePosition || produced > 0 || outcome == CodecOutcome.FINISHED, context);
            if (outcome == CodecOutcome.FINISHED) {
                return decoded.toByteArray();
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome, context);
            assertEquals(target.capacity(), produced, context);
        }
    }

    /// Starts but deliberately does not finish a decoding before the caller resets the engine.
    private static void beginDecoding(
            CompressionDecoder decoder,
            byte @Unmodifiable [] encoded,
            String context
    ) throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(1);
        source.put(encoded[0]).flip();
        ByteBuffer target = ByteBuffer.allocateDirect(1);
        CodecOutcome outcome = decoder.decode(source, target);
        assertTrue(source.position() > 0 || target.position() > 0, context);
        assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT, context);
    }

    /// Copies a target buffer's produced bytes into an owned byte stream.
    private static int drain(ByteBuffer target, ByteArrayOutputStream output) {
        int produced = target.position();
        target.flip();
        while (target.hasRemaining()) {
            output.write(target.get());
        }
        return produced;
    }

    /// Creates deterministic mixed repetitive and varying content of the shared stream size.
    private static byte @Unmodifiable [] createContent(int seed) {
        byte[] content = new byte[CONTENT_SIZE];
        for (int index = 0; index < content.length; index++) {
            content[index] = index % 29 < 23
                    ? (byte) ('a' + (index + seed) % 11)
                    : (byte) (seed * 31 + index * 17);
        }
        return content;
    }
}
