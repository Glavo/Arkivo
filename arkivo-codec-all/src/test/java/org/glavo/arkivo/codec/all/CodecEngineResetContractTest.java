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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies every installed buffer codec restores its original configuration when reset.
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
