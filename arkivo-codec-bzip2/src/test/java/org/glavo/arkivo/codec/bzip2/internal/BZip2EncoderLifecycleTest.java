// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies BZip2 encoder buffering, frame boundaries, reset behavior, and terminal states.
@NotNullByDefault
final class BZip2EncoderLifecycleTest {
    /// Verifies every boundary operation rejects incompatible in-progress operations.
    @Test
    void enforcesMutuallyExclusiveBoundaryOperations() throws IOException {
        byte[] first = patternedBytes(129, 3);
        byte[] second = patternedBytes(97, 11);
        byte[] third = patternedBytes(65, 29);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        BZip2Encoder encoder = new BZip2Encoder(1);

        encode(encoder, ByteBuffer.wrap(first), encoded, 3);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));
        assertRejectsDataAndOtherBoundaries(encoder, BoundaryOperation.FLUSH);
        flush(encoder, encoded, 2);
        assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));

        encode(encoder, ByteBuffer.wrap(second), encoded, 3);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finishFrame(ByteBuffer.allocate(0)));
        assertRejectsDataAndOtherBoundaries(encoder, BoundaryOperation.FINISH_FRAME);
        finishFrame(encoder, encoded, 2);
        assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));
        assertEquals(
                CodecOutcome.NEEDS_INPUT,
                encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );

        encode(encoder, ByteBuffer.wrap(third), encoded, 3);
        finishFrame(encoder, encoded, 2);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));
        encoder.startFrame(EncodingOptions.ofSourceSize(0L));
        assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        assertRejectsDataAndOtherBoundaries(encoder, BoundaryOperation.FINISH);
        finish(encoder, encoded, 2);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));

        byte[] expected = concatenate(first, second, third);
        assertArrayEquals(expected, decompress(encoded.toByteArray(), expected.length));
        encoder.close();
    }

    /// Verifies terminal finalization after a completed boundary does not append another empty member.
    @Test
    void finishesWithoutAddingFrameAfterBoundary() throws IOException {
        byte[] expected = patternedBytes(513, 37);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        BZip2Encoder encoder = new BZip2Encoder(1);

        encode(encoder, ByteBuffer.wrap(expected), encoded, 5);
        finishFrame(encoder, encoded, 3);
        int boundarySize = encoded.size();

        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertEquals(boundarySize, encoded.size());
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertArrayEquals(expected, decompress(encoded.toByteArray(), expected.length));
        encoder.close();
    }

    /// Verifies pending frame output prevents source consumption until target space becomes available.
    @Test
    void preservesSourceWhilePendingOutputNeedsSpace() throws IOException {
        byte[] expected = pseudoRandomBytes(230_017, 47);
        ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 9);
        source.position(5);
        source.put(expected);
        source.flip();
        source.position(5);
        int initialPosition = source.position();
        int sourceLimit = source.limit();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (BZip2Encoder encoder = new BZip2Encoder(1)) {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
            int positionWithPendingOutput = source.position();
            assertTrue(positionWithPendingOutput > initialPosition);
            assertTrue(positionWithPendingOutput < sourceLimit);

            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
            assertEquals(positionWithPendingOutput, source.position());
            encode(encoder, source, encoded, 7);
            finish(encoder, encoded, 7);
        }

        assertFalse(source.hasRemaining());
        assertArrayEquals(expected, decompress(encoded.toByteArray(), expected.length));
    }

    /// Verifies reset discards undrained terminal bytes and creates a fresh initial frame.
    @Test
    void resetsWhileTerminalOutputIsPending() throws IOException {
        byte[] expected = patternedBytes(4_097, 71);
        BZip2Encoder encoder = new BZip2Encoder(2);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        encoder.reset();

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encode(encoder, ByteBuffer.wrap(expected), encoded, 5);
        finish(encoder, encoded, 2);
        assertArrayEquals(expected, decompress(encoded.toByteArray(), expected.length));

        encoder.reset();
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        finish(encoder, empty, 1);
        assertArrayEquals(new byte[0], decompress(empty.toByteArray(), 0));
        encoder.close();
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
    }

    /// Verifies constructor, null-argument, and closed-state validation for every operation.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsAndClosedState() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new BZip2Encoder(0));
        assertThrows(IllegalArgumentException.class, () -> new BZip2Encoder(10));

        BZip2Encoder encoder = new BZip2Encoder(1);
        assertThrows(
                NullPointerException.class,
                () -> encoder.encode(null, ByteBuffer.allocate(1))
        );
        assertThrows(
                NullPointerException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), null)
        );
        assertThrows(NullPointerException.class, () -> encoder.flush(null));
        assertThrows(NullPointerException.class, () -> encoder.finishFrame(null));
        assertThrows(NullPointerException.class, () -> encoder.finish(null));
        assertThrows(NullPointerException.class, () -> encoder.startFrame(null));

        encoder.close();
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));
    }

    /// Verifies operations other than the selected boundary are rejected while that boundary is being drained.
    private static void assertRejectsDataAndOtherBoundaries(
            BZip2Encoder encoder,
            BoundaryOperation activeOperation
    ) {
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));
        if (activeOperation != BoundaryOperation.FLUSH) {
            assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        }
        if (activeOperation != BoundaryOperation.FINISH_FRAME) {
            assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        }
        if (activeOperation != BoundaryOperation.FINISH) {
            assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        }
    }

    /// Supplies every remaining source byte to the encoder with bounded direct target buffers.
    private static void encode(
            BZip2Encoder encoder,
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

    /// Drains a complete nonterminal flush operation.
    private static void flush(
            BZip2Encoder encoder,
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

    /// Drains a complete nonterminal frame finalization.
    private static void finishFrame(
            BZip2Encoder encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finishFrame(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);
    }

    /// Drains complete terminal finalization.
    private static void finish(
            BZip2Encoder encoder,
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

    /// Copies produced target bytes into the encoded stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Decodes every concatenated BZip2 member in one encoded byte array.
    private static byte[] decompress(byte[] encoded, int expectedSize) throws IOException {
        ByteBuffer decoded = BZip2Codec.DEFAULT
                .withMaximumOutputSize(expectedSize)
                .decompress(ByteBuffer.wrap(encoded));
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        return actual;
    }

    /// Concatenates three byte arrays in encounter order.
    private static byte[] concatenate(byte[] first, byte[] second, byte[] third) {
        byte[] result = new byte[first.length + second.length + third.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(third, 0, result, first.length + second.length, third.length);
        return result;
    }

    /// Creates deterministic source bytes from a size and seed.
    private static byte[] patternedBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (seed + index * 31 + index / 17);
        }
        return bytes;
    }

    /// Creates deterministic high-entropy source bytes from a size and nonzero seed.
    private static byte[] pseudoRandomBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        int state = seed;
        for (int index = 0; index < size; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            bytes[index] = (byte) state;
        }
        return bytes;
    }

    /// Identifies the boundary operation currently being drained.
    @NotNullByDefault
    private enum BoundaryOperation {
        /// A nonterminal flush is active.
        FLUSH,

        /// A nonterminal frame finish is active.
        FINISH_FRAME,

        /// Terminal finalization is active.
        FINISH
    }
}
