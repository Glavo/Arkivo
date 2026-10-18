// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw PPMd7 engine backpressure, boundary operations, reset behavior, and terminal states.
@NotNullByDefault
final class PPMd7EngineLifecycleTest {
    /// Maximum context order used by direct engine instances.
    private static final int MAXIMUM_ORDER = 4;

    /// Model arena size used by direct engine instances.
    private static final long MEMORY_SIZE = 1L << 20;

    /// Accounts for retained capacity and simultaneous old/new arenas across repeated initialization.
    @Test
    void accountsForInitializationPeaks() throws IOException {
        PPMd7Model model = new PPMd7Model(new PPMd7BufferRangeDecoder());
        assertEquals(35_228, model.retainedMemorySize());
        long[][] requests = {
                {2_048, 37_292, 37_292}, {2_049, 37_292, 37_292},
                {2_052, 39_368, 37_304}, {4_096, 41_420, 39_344},
                {2_048, 39_344, 39_344}, {8_192, 47_552, 43_436},
                {1_048_576, 1_092_032, 1_083_824}, {2_097_152, 3_180_992, 2_132_396}
        };
        for (long[] request : requests) {
            long retained = model.retainedMemorySize();
            assertEquals(request[1], model.initializationPeakMemorySize(true, 4, request[0]));
            assertEquals(retained, model.retainedMemorySize());
            model.initialize(true, 4, request[0]);
            assertEquals(request[2], model.retainedMemorySize());
            assertEquals(request[2], model.initializationPeakMemorySize(false, -1, -1));
            model.initialize(false, -1, -1);
            model.reset();
            assertEquals(request[2], model.retainedMemorySize());
            assertThrows(IOException.class, () -> model.initializationPeakMemorySize(false, 4, 2_048));
        }
        IOException preview = assertThrows(IOException.class,
                () -> model.initializationPeakMemorySize(true, 1, -1));
        IOException actual = assertThrows(IOException.class, () -> model.initialize(true, 1, -1));
        assertEquals(actual.getMessage(), preview.getMessage());
    }

    /// Compares the declared payload with the actual primitive arrays around arena alignment boundaries.
    @ParameterizedTest
    @ValueSource(longs = {2_048, 2_049, 2_051, 2_052, 2_063, 2_064, 1_048_576})
    void accountsForAllocatedModelArrays(long arenaSize) throws Exception {
        PPMd7Model model = new PPMd7Model(new PPMd7BufferRangeDecoder());
        var allocatorField = PPMd7Model.class.getDeclaredField("allocator");
        allocatorField.setAccessible(true);
        Object allocator = allocatorField.get(model);
        assertEquals(35_228, primitiveArrayPayload(model) + primitiveArrayPayload(allocator));
        model.initialize(true, MAXIMUM_ORDER, arenaSize);
        assertEquals(PPMd7Model.requiredMemorySize(arenaSize),
                primitiveArrayPayload(model) + primitiveArrayPayload(allocator));
        model.reset();
        assertEquals(PPMd7Model.requiredMemorySize(arenaSize),
                primitiveArrayPayload(model) + primitiveArrayPayload(allocator));
    }

    /// Checks size calculation without allocating large arenas and rejects out-of-range requests.
    @Test
    void calculatesModelMemoryWithoutAllocation() throws IOException {
        assertEquals(37_292, PPMd7Model.requiredMemorySize(2_048));
        assertEquals(1_083_824, PPMd7Model.requiredMemorySize(1L << 20));
        assertEquals(2_132_396, PPMd7Model.requiredMemorySize(2L << 20));
        assertEquals(268_470_704, PPMd7Model.requiredMemorySize(256L << 20));
        for (long invalid : new long[]{-1, 0, 2_047, (256L << 20) + 1, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> PPMd7Model.requiredMemorySize(invalid));
        }
    }

    /// Preserves allocated capacity on shrink and leaves the existing arena intact after invalid requests.
    @Test
    void preservesAllocatorOnRejectedInitialization() throws Exception {
        PPMd7Allocator allocator = new PPMd7Allocator();
        allocator.initialize(4_096);
        allocator.setSymbol(1, 123);
        allocator.setFrequency(1, 17);
        allocator.setSuccessor(1, 456);
        long retained = primitiveArrayPayload(allocator);
        for (long invalid : new long[]{2_047, (256L << 20) + 1, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> allocator.initialize(invalid));
            assertEquals(retained, primitiveArrayPayload(allocator));
            assertEquals(123, allocator.symbol(1));
            assertEquals(17, allocator.frequency(1));
            assertEquals(456, allocator.successor(1));
        }
        allocator.initialize(2_048);
        allocator.restart();
        assertEquals(retained, primitiveArrayPayload(allocator));
        allocator.initialize(8_192);
        allocator.restart();
        assertEquals(PPMd7Allocator.FIXED_MEMORY_SIZE + PPMd7Allocator.arenaMemorySize(8_192),
                primitiveArrayPayload(allocator));
    }

    /// Sums instance-owned array payloads independently of the production size formula.
    private static long primitiveArrayPayload(Object holder) throws IllegalAccessException {
        long total = 0;
        for (var field : holder.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType().isArray()) {
                field.setAccessible(true);
                total += arrayPayload(field.get(holder));
            }
        }
        return total;
    }

    /// Counts primitive elements, excluding reference arrays and non-array objects.
    private static long arrayPayload(@Nullable Object value) {
        if (value instanceof byte[] bytes) return bytes.length;
        if (value instanceof int[] values) return (long) values.length * Integer.BYTES;
        if (value instanceof Object[] arrays) {
            long total = 0;
            for (@Nullable Object array : arrays) total += arrayPayload(array);
            return total;
        }
        return 0;
    }

    /// Reuses the model on reset but drops its arena on close, independently of garbage-collection scheduling.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesModelOnClose(boolean encoding) throws Exception {
        try (AutoCloseable engine = encoding ? new PPMd7Encoder(MAXIMUM_ORDER, MEMORY_SIZE)
                : new PPMd7Decoder(MAXIMUM_ORDER, MEMORY_SIZE, 0)) {
            var field = engine.getClass().getDeclaredField("model");
            field.setAccessible(true);
            @Nullable Object model = field.get(engine);
            assertNotNull(model);
            if (engine instanceof PPMd7Encoder encoder) encoder.reset();
            else ((PPMd7Decoder) engine).reset();
            assertSame(model, field.get(engine));
            engine.close();
            assertNull(field.get(engine));
            engine.close();
            assertNull(field.get(engine));
        }
    }

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
