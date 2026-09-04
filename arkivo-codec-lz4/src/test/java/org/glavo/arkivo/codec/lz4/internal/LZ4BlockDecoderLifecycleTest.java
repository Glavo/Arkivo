// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies raw LZ4 block collection, output backpressure, reset behavior, and terminal states.
@NotNullByDefault
final class LZ4BlockDecoderLifecycleTest {
    /// The primitive sentinel used for an unlimited decoder resource bound.
    private static final long UNLIMITED = -1L;

    /// Verifies fragmented compressed input is owned and decoded output drains without accepting later input.
    @Test
    void collectsAndDrainsOneBlockUnderBackpressure() throws IOException {
        byte[] expected = patternedBytes(257);
        byte[] encoded = encode(expected);
        int split = encoded.length / 2;
        LZ4BlockDecoder decoder = decoder(expected.length, encoded.length);
        ByteBuffer firstSource = ByteBuffer.wrap(encoded, 0, split).slice().asReadOnlyBuffer();
        ByteBuffer secondSource = ByteBuffer.wrap(encoded, split, encoded.length - split).slice().asReadOnlyBuffer();
        ByteBuffer ignoredTarget = ByteBuffer.allocateDirect(0);

        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(firstSource, ignoredTarget));
        assertFalse(firstSource.hasRemaining());
        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(secondSource, ignoredTarget));
        assertFalse(secondSource.hasRemaining());

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocateDirect(7);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(ByteBuffer.allocate(0), target));
        drain(target, decoded);

        IllegalStateException decodeFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals("Cannot decode while raw LZ4 decoder state is DRAINING", decodeFailure.getMessage());

        ByteBuffer lateSource = ByteBuffer.wrap(new byte[]{99});
        IllegalStateException finishFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(lateSource, ByteBuffer.allocate(1))
        );
        assertEquals(
                "Cannot supply more input after raw LZ4 decoding has started",
                finishFailure.getMessage()
        );
        assertEquals(0, lateSource.position());

        CodecOutcome outcome;
        do {
            target = ByteBuffer.allocateDirect(11);
            outcome = decoder.finish(ByteBuffer.allocate(0), target);
            drain(target, decoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
        assertArrayEquals(expected, decoded.toByteArray());

        ByteBuffer trailing = ByteBuffer.wrap(new byte[]{1, 2, 3});
        assertEquals(CodecOutcome.FINISHED, decoder.decode(trailing, ByteBuffer.allocate(0)));
        assertEquals(0, trailing.position());
        assertEquals(CodecOutcome.FINISHED, decoder.finish(trailing, ByteBuffer.allocate(0)));
        assertEquals(0, trailing.position());
        decoder.close();
    }

    /// Verifies reset discards both collected compressed bytes and undrained decoded output.
    @Test
    void resetsCollectedInputAndPendingOutput() throws IOException {
        byte[] discardedInput = encode("discarded input".getBytes(StandardCharsets.UTF_8));
        byte[] discardedOutput = encode("discarded output".getBytes(StandardCharsets.UTF_8));
        byte[] expected = "retained after reset".getBytes(StandardCharsets.UTF_8);
        byte[] expectedEncoded = encode(expected);
        LZ4BlockDecoder decoder = new LZ4BlockDecoder(128, 128, UNLIMITED, UNLIMITED);

        assertEquals(
                CodecOutcome.NEEDS_INPUT,
                decoder.decode(ByteBuffer.wrap(discardedInput), ByteBuffer.allocate(0))
        );
        decoder.reset();

        assertEquals(
                CodecOutcome.NEEDS_OUTPUT,
                decoder.finish(ByteBuffer.wrap(discardedOutput), ByteBuffer.allocate(0))
        );
        decoder.reset();

        ByteBuffer target = ByteBuffer.allocate(expected.length);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(expectedEncoded), target));
        target.flip();
        byte[] actual = new byte[target.remaining()];
        target.get(actual);
        assertArrayEquals(expected, actual);

        decoder.reset();
        ByteBuffer repeated = ByteBuffer.allocate(expected.length);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(expectedEncoded), repeated));
        repeated.flip();
        byte[] repeatedBytes = new byte[repeated.remaining()];
        repeated.get(repeatedBytes);
        assertArrayEquals(expected, repeatedBytes);
        decoder.close();
    }

    /// Verifies constructor and input-storage bounds fail before consuming caller bytes.
    @Test
    void validatesConstructionAndCollectionBounds() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new LZ4BlockDecoder(-1, 0, UNLIMITED, UNLIMITED));
        assertThrows(IllegalArgumentException.class, () -> new LZ4BlockDecoder(0, -1, UNLIMITED, UNLIMITED));

        ByteBuffer compressedOverflow = ByteBuffer.wrap(new byte[]{1, 2, 3});
        LZ4BlockDecoder compressedBounded = new LZ4BlockDecoder(16, 2, UNLIMITED, UNLIMITED);
        IOException compressedFailure = assertThrows(
                IOException.class,
                () -> compressedBounded.decode(compressedOverflow, ByteBuffer.allocate(0))
        );
        assertEquals(
                "Raw LZ4 block exceeds its configured compressed-size bound",
                compressedFailure.getMessage()
        );
        assertEquals(0, compressedOverflow.position());
        compressedBounded.close();

        ByteBuffer memoryOverflow = ByteBuffer.wrap(new byte[]{1, 2, 3});
        LZ4BlockDecoder memoryBounded = new LZ4BlockDecoder(16, 16, UNLIMITED, 2L);
        DecompressionMemoryLimitException memoryFailure = assertThrows(
                DecompressionMemoryLimitException.class,
                () -> memoryBounded.decode(memoryOverflow, ByteBuffer.allocate(0))
        );
        assertEquals(2L, memoryFailure.maximumMemorySize());
        assertEquals(3L, memoryFailure.requiredMemorySize());
        assertEquals(0, memoryOverflow.position());
        memoryBounded.close();

        ByteBuffer exactMemory = ByteBuffer.wrap(new byte[]{1, 2, 3});
        LZ4BlockDecoder exactlyBounded = new LZ4BlockDecoder(16, 16, UNLIMITED, 3L);
        assertEquals(CodecOutcome.NEEDS_INPUT, exactlyBounded.decode(exactMemory, ByteBuffer.allocate(0)));
        assertFalse(exactMemory.hasRemaining());
        exactlyBounded.close();
    }

    /// Verifies null validation precedes state changes and closure permanently rejects decoder operations.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsAndClosedState() {
        LZ4BlockDecoder decoder = decoder(16, 16);
        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        decoder.close();
        decoder.close();
        IllegalStateException resetFailure = assertThrows(IllegalStateException.class, decoder::reset);
        assertEquals("Raw LZ4 decoder is closed", resetFailure.getMessage());
        IllegalStateException decodeFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("Raw LZ4 decoder is closed", decodeFailure.getMessage());
        IllegalStateException finishFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("Raw LZ4 decoder is closed", finishFailure.getMessage());
    }

    /// Creates a raw-block decoder with unlimited window and memory bounds.
    private static LZ4BlockDecoder decoder(int maximumBlockSize, int maximumCompressedSize) {
        return new LZ4BlockDecoder(maximumBlockSize, maximumCompressedSize, UNLIMITED, UNLIMITED);
    }

    /// Encodes one raw LZ4 block through the public codec implementation.
    private static byte[] encode(byte[] content) throws IOException {
        ByteBuffer encoded = LZ4BlockCodec.DEFAULT
                .withMaximumBlockSize(Math.max(1, content.length))
                .compress(ByteBuffer.wrap(content));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Copies produced bytes out of one caller-owned target buffer.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic bytes containing both literals and repeated match candidates.
    private static byte[] patternedBytes(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((index * 17) ^ (index >>> 2) ^ (index % 29));
        }
        return result;
    }
}
