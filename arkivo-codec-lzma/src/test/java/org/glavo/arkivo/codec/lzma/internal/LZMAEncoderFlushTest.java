// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies nonterminal flush boundaries and lifecycle transitions in the pure Java LZMA encoders.
@NotNullByDefault
final class LZMAEncoderFlushTest {
    /// Shared model properties used by encoders and independent public decoders.
    private static final LZMAProperties PROPERTIES = new LZMAProperties(3, 0, 2, 1 << 16);

    /// Verifies a partial LZMA2 chunk can be flushed, independently terminated, and followed by more input.
    @Test
    void flushesLzma2ChunkAndContinues() throws IOException {
        byte[] first = patternedBytes(5_003, 17);
        byte[] second = patternedBytes(7_019, 83);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        LZMA2Encoder encoder = new LZMA2Encoder(PROPERTIES);

        encode(encoder, ByteBuffer.wrap(first), encoded, 5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));

        ByteBuffer blockedSource = ByteBuffer.wrap(new byte[]{99});
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(blockedSource, ByteBuffer.allocate(1))
        );
        assertEquals(0, blockedSource.position());
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));

        drain(encoder::flush, CodecOutcome.FLUSHED, encoded, 3);
        assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));

        byte[] firstMember = Arrays.copyOf(encoded.toByteArray(), encoded.size() + 1);
        assertArrayEquals(first, decode(new LZMA2Codec(PROPERTIES), firstMember));

        encode(encoder, ByteBuffer.wrap(second), encoded, 7);
        drain(encoder::finish, CodecOutcome.FINISHED, encoded, 2);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        encoder.close();

        assertArrayEquals(
                concatenate(first, second),
                decode(new LZMA2Codec(PROPERTIES), encoded.toByteArray())
        );
    }

    /// Verifies raw LZMA flush drains available range output and permits continued encoding in the same stream.
    @Test
    void flushesRawRangeOutputAndContinues() throws IOException {
        byte[] first = patternedBytes(6_011, 29);
        byte[] second = patternedBytes(8_021, 101);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        LZMARawEncoder encoder = new LZMARawEncoder(
                PROPERTIES,
                CompressionCodec.UNKNOWN_SIZE,
                true
        );

        encode(encoder, ByteBuffer.wrap(first), encoded, 5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));

        ByteBuffer blockedSource = ByteBuffer.wrap(new byte[]{100});
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(blockedSource, ByteBuffer.allocate(1))
        );
        assertEquals(0, blockedSource.position());
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));

        drain(encoder::flush, CodecOutcome.FLUSHED, encoded, 3);
        assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));
        encode(encoder, ByteBuffer.wrap(second), encoded, 7);
        drain(encoder::finish, CodecOutcome.FINISHED, encoded, 2);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        encoder.close();

        RawLZMACodec codec = RawLZMACodec.DEFAULT
                .withProperties(PROPERTIES)
                .withEndMarker(true)
                .withDecodedSize(CompressionCodec.UNKNOWN_SIZE);
        assertArrayEquals(concatenate(first, second), decode(codec, encoded.toByteArray()));
    }

    /// Verifies reset discards an incomplete flush and closed encoders reject every stateful operation.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void resetsPendingFlushesAndValidatesClosedState() throws IOException {
        byte[] discarded = patternedBytes(257, 7);
        byte[] expected = patternedBytes(521, 41);

        LZMA2Encoder lzma2 = new LZMA2Encoder(PROPERTIES);
        encode(lzma2, ByteBuffer.wrap(discarded), new ByteArrayOutputStream(), 5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, lzma2.flush(ByteBuffer.allocate(0)));
        lzma2.reset();
        ByteArrayOutputStream lzma2Encoded = new ByteArrayOutputStream();
        encode(lzma2, ByteBuffer.wrap(expected), lzma2Encoded, 5);
        ByteBuffer completeFlush = ByteBuffer.allocateDirect(4_096);
        assertEquals(CodecOutcome.FLUSHED, lzma2.flush(completeFlush));
        drain(completeFlush, lzma2Encoded);
        drain(lzma2::finish, CodecOutcome.FINISHED, lzma2Encoded, 3);
        assertArrayEquals(expected, decode(new LZMA2Codec(PROPERTIES), lzma2Encoded.toByteArray()));
        lzma2.close();
        lzma2.close();
        assertThrows(NullPointerException.class, () -> lzma2.flush(null));
        assertThrows(IllegalStateException.class, () -> lzma2.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, lzma2::reset);

        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMARawEncoder(PROPERTIES, CompressionCodec.UNKNOWN_SIZE - 1L, true)
        );
        LZMARawEncoder raw = new LZMARawEncoder(PROPERTIES, CompressionCodec.UNKNOWN_SIZE, true);
        encode(raw, ByteBuffer.wrap(discarded), new ByteArrayOutputStream(), 5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, raw.flush(ByteBuffer.allocate(0)));
        raw.reset();
        ByteArrayOutputStream rawEncoded = new ByteArrayOutputStream();
        encode(raw, ByteBuffer.wrap(expected), rawEncoded, 5);
        drain(raw::finish, CodecOutcome.FINISHED, rawEncoded, 3);
        RawLZMACodec rawCodec = RawLZMACodec.DEFAULT
                .withProperties(PROPERTIES)
                .withEndMarker(true)
                .withDecodedSize(CompressionCodec.UNKNOWN_SIZE);
        assertArrayEquals(expected, decode(rawCodec, rawEncoded.toByteArray()));
        raw.close();
        raw.close();
        assertThrows(NullPointerException.class, () -> raw.flush(null));
        assertThrows(IllegalStateException.class, () -> raw.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, raw::reset);
    }

    /// Verifies standalone header backpressure, nonterminal flush, reset, and wrapper-specific closed states.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void flushesAndResetsStandaloneWrapper() throws IOException {
        byte[] discarded = patternedBytes(257, 13);
        byte[] first = patternedBytes(3_011, 37);
        byte[] second = patternedBytes(4_019, 71);
        byte[] expected = concatenate(first, second);
        LZMAEncoder encoder = new LZMAEncoder(PROPERTIES, CompressionCodec.UNKNOWN_SIZE);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));
        ByteBuffer partialHeader = ByteBuffer.allocate(5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(partialHeader));
        assertEquals(5, partialHeader.position());
        encode(encoder, ByteBuffer.wrap(discarded), new ByteArrayOutputStream(), 17);
        encoder.reset();

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encode(encoder, ByteBuffer.wrap(first), encoded, 5);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));
        drain(encoder::flush, CodecOutcome.FLUSHED, encoded, 3);
        encode(encoder, ByteBuffer.wrap(second), encoded, 7);
        drain(encoder::finish, CodecOutcome.FINISHED, encoded, 2);
        assertArrayEquals(expected, decode(new LZMACodec(PROPERTIES), encoded.toByteArray()));

        encoder.reset();
        ByteArrayOutputStream resetEncoding = new ByteArrayOutputStream();
        encode(encoder, ByteBuffer.wrap(expected), resetEncoding, 11);
        drain(encoder::finish, CodecOutcome.FINISHED, resetEncoding, 5);
        assertArrayEquals(expected, decode(new LZMACodec(PROPERTIES), resetEncoding.toByteArray()));

        encoder.close();
        encoder.close();
        assertThrows(NullPointerException.class, () -> encoder.flush(null));
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
    }

    /// Encodes all source bytes while forcing transport-independent pending-output retries.
    private static void encode(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        while (source.hasRemaining()) {
            int sourcePosition = source.position();
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            CodecOutcome outcome = encoder.encode(source, target);
            int produced = target.position();
            drain(target, output);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            assertTrue(source.position() > sourcePosition || produced > 0);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
            }
        }
    }

    /// Repeats one boundary operation until its expected terminal outcome is reached.
    private static void drain(
            BufferOperation operation,
            CodecOutcome expectedOutcome,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = operation.apply(target);
            drain(target, output);
            assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == expectedOutcome);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(expectedOutcome, outcome);
    }

    /// Copies produced bytes from one target buffer into an output stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Decodes one complete encoding through the corresponding public codec.
    private static byte[] decode(CompressionCodec<?> codec, byte[] encoded) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Concatenates two immutable test arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Returns deterministic data containing short repetitions and position-dependent literals.
    private static byte[] patternedBytes(int size, int seed) {
        byte[] result = new byte[size];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((index % 97 < 61) ? index % 13 : index * 31 + seed);
        }
        return result;
    }

    /// Performs one buffer-oriented encoder boundary operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface BufferOperation {
        /// Applies the operation to one caller-owned target buffer.
        CodecOutcome apply(ByteBuffer target) throws IOException;
    }
}
