// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw PPMd7 engine backpressure, boundary operations, reset behavior, and terminal states.
@NotNullByDefault
final class PPMd7EngineLifecycleTest {
    /// Maximum context order used by direct engine instances.
    private static final int MAXIMUM_ORDER = 4;

    /// Model arena size used by direct engine instances.
    private static final long MEMORY_SIZE = 1L << 20;

    /// Verifies flush and finish remain mutually exclusive while their pending bytes are drained.
    @Test
    void enforcesEncoderBoundaryStateMachine() throws IOException {
        byte[] expected = pseudoRandomBytes(200_003, 0x50504d64);
        ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 9);
        source.position(5);
        source.put(expected);
        source.flip();
        source.position(5);
        int initialPosition = source.position();
        int sourceLimit = source.limit();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PPMd7Encoder encoder = new PPMd7Encoder(MAXIMUM_ORDER, MEMORY_SIZE);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
        int positionWithPendingOutput = source.position();
        assertTrue(positionWithPendingOutput > initialPosition);
        assertTrue(positionWithPendingOutput < sourceLimit);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
        assertEquals(positionWithPendingOutput, source.position());

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        flush(encoder, encoded, 257);

        encode(encoder, source, encoded, 257);
        assertFalse(source.hasRemaining());
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        finish(encoder, encoded, 31);

        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertArrayEquals(expected, decode(encoded.toByteArray(), expected.length));
        encoder.close();
    }

    /// Verifies reset discards pending terminal bytes and all operations validate null and closed states.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void resetsPendingOutputAndValidatesArguments() throws IOException {
        byte[] expected = patternedBytes(4_097);
        PPMd7Encoder encoder = new PPMd7Encoder(MAXIMUM_ORDER, MEMORY_SIZE);

        assertThrows(
                NullPointerException.class,
                () -> encoder.encode(null, ByteBuffer.allocate(1))
        );
        assertThrows(
                NullPointerException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), null)
        );
        assertThrows(NullPointerException.class, () -> encoder.flush(null));
        assertThrows(NullPointerException.class, () -> encoder.finish(null));

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        encoder.reset();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encode(encoder, ByteBuffer.wrap(expected), encoded, 31);
        finish(encoder, encoded, 17);
        assertArrayEquals(expected, decode(encoded.toByteArray(), expected.length));

        encoder.reset();
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        finish(encoder, empty, 1);
        assertArrayEquals(new byte[0], decode(empty.toByteArray(), 0));

        encoder.close();
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
    }

    /// Verifies decoder initialization, exact-size completion, trailing input, reset, and closed states.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void enforcesDecoderLifecycleAndExactBoundary() throws IOException {
        byte[] expected = patternedBytes(8_193);
        byte[] encoded = encode(expected);
        byte[] tail = {91, 92, 93};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);
        ByteBuffer source = ByteBuffer.wrap(withTail);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        PPMd7Decoder decoder = new PPMd7Decoder(MAXIMUM_ORDER, MEMORY_SIZE, expected.length);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(source, ByteBuffer.allocate(0)));
        assertEquals(5, source.position());
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(3);
            outcome = decoder.decode(source, target);
            drain(target, decoded);
            assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
        } while (outcome != CodecOutcome.FINISHED);

        assertArrayEquals(expected, decoded.toByteArray());
        assertTrue(source.position() <= encoded.length);
        int finishedPosition = source.position();
        assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(1)));
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, ByteBuffer.allocate(1)));
        assertEquals(finishedPosition, source.position());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, encoded.length, withTail.length));

        decoder.reset();
        assertArrayEquals(expected, decode(decoder, encoded, expected.length));
        decoder.reset();
        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies a zero-sized logical stream consumes only its range prefix before becoming terminal.
    @Test
    void zeroSizedDecoderPreservesFollowingInput() throws IOException {
        byte[] encoded = encode(new byte[0]);
        byte[] tail = {11, 22, 33};
        ByteBuffer source = ByteBuffer.wrap(concatenate(encoded, tail));

        try (PPMd7Decoder decoder = new PPMd7Decoder(MAXIMUM_ORDER, MEMORY_SIZE, 0L)) {
            assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(0)));
            assertEquals(encoded.length, source.position());
            assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(1)));
            assertEquals(encoded.length, source.position());
        }
    }

    /// Encodes every remaining source byte while draining bounded direct target buffers.
    private static void encode(
            PPMd7Encoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertFalse(source.hasRemaining());
    }

    /// Drains one complete nonterminal flush operation.
    private static void flush(
            PPMd7Encoder encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.flush(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FLUSHED, outcome);
    }

    /// Drains complete terminal encoder output.
    private static void finish(
            PPMd7Encoder encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finish(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Encodes one complete byte array with a fresh direct engine.
    private static byte[] encode(byte[] source) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (PPMd7Encoder encoder = new PPMd7Encoder(MAXIMUM_ORDER, MEMORY_SIZE)) {
            encode(encoder, ByteBuffer.wrap(source), encoded, 7);
            finish(encoder, encoded, 3);
        }
        return encoded.toByteArray();
    }

    /// Decodes one exactly sized raw stream with a fresh direct engine.
    private static byte[] decode(byte[] encoded, int decodedSize) throws IOException {
        try (PPMd7Decoder decoder = new PPMd7Decoder(
                MAXIMUM_ORDER,
                MEMORY_SIZE,
                decodedSize
        )) {
            return decode(decoder, encoded, decodedSize);
        }
    }

    /// Decodes one exactly sized raw stream with an existing direct engine.
    private static byte[] decode(PPMd7Decoder decoder, byte[] encoded, int decodedSize) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(decodedSize);
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(257);
            outcome = decoder.finish(source, target);
            drain(target, decoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
        return decoded.toByteArray();
    }

    /// Copies produced target bytes into an owned byte stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Concatenates two byte arrays in encounter order.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates deterministic source bytes that exercise varied PPMd contexts.
    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31 + (index >>> 3) + index % 251);
        }
        return bytes;
    }

    /// Creates deterministic high-entropy source bytes from a size and nonzero seed.
    private static byte[] pseudoRandomBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        int state = seed;
        for (int index = 0; index < bytes.length; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            bytes[index] = (byte) state;
        }
        return bytes;
    }
}
