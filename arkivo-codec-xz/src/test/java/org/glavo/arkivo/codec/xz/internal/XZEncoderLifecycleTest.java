// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.glavo.arkivo.codec.xz.XZFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies XZ encoder Stream boundaries, pending output, reset behavior, and terminal states.
@NotNullByDefault
final class XZEncoderLifecycleTest {
    /// The small dictionary used by lifecycle encoders.
    private static final int DICTIONARY_SIZE = 1 << 16;

    /// The CRC-32 integrity-check identifier.
    private static final int CHECK_CRC32 = 1;

    /// Verifies every incomplete boundary operation excludes data and all other boundary operations.
    @Test
    void enforcesMutuallyExclusiveBoundaryOperations() throws IOException {
        byte[] first = patternedBytes(129, 3);
        byte[] second = patternedBytes(97, 11);
        byte[] third = patternedBytes(65, 29);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        XZEncoder encoder = newEncoder();

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
        assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));
        assertEquals(
                CodecOutcome.NEEDS_INPUT,
                encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );

        encode(encoder, ByteBuffer.wrap(third), encoded, 3);
        finishFrame(encoder, encoded, 2);
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

    /// Verifies terminal finalization after a completed Stream boundary emits no additional empty Stream.
    @Test
    void finishesWithoutAddingStreamAfterBoundary() throws IOException {
        byte[] expected = patternedBytes(513, 37);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        XZEncoder encoder = newEncoder();

        encode(encoder, ByteBuffer.wrap(expected), encoded, 5);
        finishFrame(encoder, encoded, 3);
        int boundarySize = encoded.size();

        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertEquals(boundarySize, encoded.size());
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertArrayEquals(expected, decompress(encoded.toByteArray(), expected.length));
        encoder.close();
    }

    /// Verifies reset discards an undrained Stream and restores a fresh initial Stream.
    @Test
    void resetsWhileBoundaryOutputIsPending() throws IOException {
        byte[] expected = patternedBytes(4_097, 71);
        XZEncoder encoder = newEncoder();

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finishFrame(ByteBuffer.allocate(0)));
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

    /// Verifies constructor, null-argument, active-state, and closed-state validation.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsAndClosedState() throws IOException {
        LZMAProperties properties = LZMAProperties.defaults(DICTIONARY_SIZE);
        assertThrows(
                NullPointerException.class,
                () -> new XZEncoder(null, CHECK_CRC32, XZFilterChain.EMPTY, 0L)
        );
        assertThrows(
                NullPointerException.class,
                () -> new XZEncoder(properties, CHECK_CRC32, null, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new XZEncoder(properties, CHECK_CRC32, XZFilterChain.EMPTY, -1L)
        );
        assertThrows(
                IOException.class,
                () -> new XZEncoder(properties, 2, XZFilterChain.EMPTY, 0L)
        );

        XZEncoder encoder = newEncoder();
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
        assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));

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

    /// Creates one encoder with small deterministic properties and Block boundaries.
    private static XZEncoder newEncoder() throws IOException {
        return new XZEncoder(
                LZMAProperties.defaults(DICTIONARY_SIZE),
                CHECK_CRC32,
                XZFilterChain.EMPTY,
                64L
        );
    }

    /// Verifies operations other than the selected boundary fail while its output remains pending.
    private static void assertRejectsDataAndOtherBoundaries(
            XZEncoder encoder,
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

    /// Supplies all remaining source bytes while draining bounded direct target buffers.
    private static void encode(
            XZEncoder encoder,
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
            XZEncoder encoder,
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

    /// Drains one complete nonterminal Stream finalization.
    private static void finishFrame(
            XZEncoder encoder,
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
            XZEncoder encoder,
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

    /// Decodes every concatenated XZ Stream from one encoded byte array.
    private static byte[] decompress(byte[] encoded, int expectedSize) throws IOException {
        ByteBuffer decoded = XZCodec.DEFAULT
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

    /// Identifies the boundary operation whose output is being drained.
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
