// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies per-engine output budgets, partial progress, and reuse across installed codecs.
@NotNullByDefault
final class CodecDecoderOutputLimitTest {
    /// Content spanning several small targets and containing both repeated and varying bytes.
    private static final byte @Unmodifiable [] CONTENT = content();

    /// Sentinel surrounding the writable portion of every target.
    private static final byte GUARD = 0x5a;

    /// Covers independent input delivery, target capacity, and storage choices for every format.
    private static Stream<Arguments> configurations() {
        return CompressionFormats.installed().stream().flatMap(format -> Stream.of(false, true)
                .flatMap(direct -> Stream.of(false, true).flatMap(fragmented -> Stream.of(1, 37)
                        .map(capacity -> Arguments.of(format.name(), direct, fragmented, capacity)))));
    }

    /// Accepts empty and nonempty streams at exact, larger, and maximum representable budgets.
    @ParameterizedTest(name = "{0}, direct={1}, fragmented={2}, target={3}")
    @MethodSource("configurations")
    void acceptsOutputWithinBudget(String formatName, boolean direct, boolean fragmented, int capacity)
            throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        for (int size : new int[]{0, 1, CONTENT.length}) {
            byte[] expected = Arrays.copyOf(CONTENT, size);
            byte[] encoded = encode(codec, expected);
            for (long maximum : new long[]{size, size + 1L, Long.MAX_VALUE}) {
                CompressionCodec<?> configured = CodecContractConfigurations.decoderCodec(codec, size)
                        .withMaximumOutputSize(maximum);
                try (CompressionDecoder decoder = configured.newDecoder()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    decode(decoder, encoded, output, direct, fragmented, capacity);
                    assertArrayEquals(expected, output.toByteArray(), "maximum=" + maximum);
                }
            }
        }
    }

    /// Rejects excess output without exposing it and restores the same budget when a failed engine is reset.
    @ParameterizedTest(name = "{0}, direct={1}, fragmented={2}, target={3}")
    @MethodSource("configurations")
    void rejectsExcessOutputAndResets(String formatName, boolean direct, boolean fragmented, int capacity)
            throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        byte[] encoded = encode(codec, CONTENT);
        for (int maximum : new int[]{0, 1, 31, CONTENT.length - 1}) {
            CompressionCodec<?> configured = CodecContractConfigurations.decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(maximum);
            if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                // PPMd can reject its externally declared output size before allocating an engine.
                verifyFailure(assertThrows(DecompressionOutputLimitException.class, configured::newDecoder), maximum);
                continue;
            }
            byte[] recoveredContent = Arrays.copyOf(CONTENT, maximum);
            byte[] recoveredEncoding = encode(codec, recoveredContent);
            try (CompressionDecoder decoder = configured.newDecoder()) {
                ByteArrayOutputStream partial = new ByteArrayOutputStream();
                verifyFailure(assertThrows(DecompressionOutputLimitException.class,
                        () -> decode(decoder, encoded, partial, direct, fragmented, capacity)), maximum);
                assertTrue(partial.size() <= maximum);
                assertArrayEquals(Arrays.copyOf(CONTENT, partial.size()), partial.toByteArray());

                decoder.reset();
                decoder.reset();
                for (int attempt = 0; attempt < 2; attempt++) {
                    ByteArrayOutputStream recovered = new ByteArrayOutputStream();
                    decode(decoder, recoveredEncoding, recovered, direct, fragmented, capacity);
                    assertArrayEquals(recoveredContent, recovered.toByteArray());
                    decoder.reset();
                }
                // Reset must clear progress, not remove the configured limit.
                verifyFailure(assertThrows(DecompressionOutputLimitException.class,
                        () -> decode(decoder, encoded, new ByteArrayOutputStream(), direct, fragmented, capacity)),
                        maximum);
            }
        }
    }

    /// Checks the public exception metadata without requiring a particular excess-detection strategy.
    private static void verifyFailure(DecompressionOutputLimitException failure, int maximum) {
        assertEquals(DecompressionLimitException.Kind.OUTPUT_SIZE, failure.kind());
        assertEquals(maximum, failure.maximum());
        assertEquals(maximum, failure.maximumOutputSize());
        assertTrue(failure.actualOutputSize() > maximum);
        assertEquals(failure.actual(), failure.actualOutputSize());
    }

    /// Decodes through guarded target windows, collecting progress even when an operation throws.
    private static void decode(
            CompressionDecoder decoder,
            byte @Unmodifiable [] encoded,
            ByteArrayOutputStream output,
            boolean direct,
            boolean fragmented,
            int capacity
    ) throws IOException {
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(encoded.length + 4)
                : ByteBuffer.allocate(encoded.length + 4);
        storage.position(2).put(encoded).flip().position(2);
        ByteBuffer source = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int end = source.limit();
        if (fragmented) {
            source.limit(Math.min(end, 3));
        }
        for (int call = 0; call < 8192; call++) {
            int available = call == 0 ? 0 : capacity;
            ByteBuffer target = direct ? ByteBuffer.allocateDirect(available + 4)
                    : ByteBuffer.allocate(available + 4);
            for (int index = 0; index < target.capacity(); index++) {
                target.put(index, GUARD);
            }
            target.position(2).limit(available + 2).order(ByteOrder.LITTLE_ENDIAN).mark();
            int sourcePosition = source.position();
            int sourceLimit = source.limit();
            int outputStart = output.size();
            CodecOutcome outcome;
            try {
                outcome = !fragmented || sourceLimit == end
                        ? decoder.finish(source, target) : decoder.decode(source, target);
            } finally {
                assertEquals(sourceLimit, source.limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
                assertTrue(source.position() >= sourcePosition && source.position() <= sourceLimit);
                assertEquals(available + 2, target.limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
                int writtenEnd = target.position();
                assertTrue(writtenEnd >= 2 && writtenEnd <= target.limit());
                target.reset();
                assertEquals(2, target.position());
                while (target.position() < writtenEnd) {
                    output.write(target.get());
                }
                target.clear();
                assertEquals(GUARD, target.get(0));
                assertEquals(GUARD, target.get(1));
                for (int index = writtenEnd; index < target.capacity(); index++) {
                    assertEquals(GUARD, target.get(index));
                }
            }
            if (outcome == CodecOutcome.FINISHED) {
                assertEquals(end, source.position());
                return;
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertTrue(fragmented && sourceLimit < end, "Unexpected request after physical EOF");
                source.limit(Math.min(end, sourceLimit + 7));
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(available, output.size() - outputStart);
            }
        }
        fail("Decoder did not finish within the call bound");
    }

    /// Encodes one independent stream without setting a decoder output budget.
    private static byte @Unmodifiable [] encode(CompressionCodec<?> codec, byte @Unmodifiable [] content)
            throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(content));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Creates deterministic bytes with both short runs and literal transitions.
    private static byte @Unmodifiable [] content() {
        byte[] result = new byte[257];
        for (int index = 0; index < result.length; index++) {
            result[index] = index % 29 < 23 ? (byte) 'a' : (byte) (index * 17);
        }
        return result;
    }
}
