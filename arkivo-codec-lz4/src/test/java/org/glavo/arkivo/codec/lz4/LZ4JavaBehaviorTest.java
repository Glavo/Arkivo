// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Ports format-level raw-block and frame edge cases from the lz4-java 1.8.0 test suite.
@NotNullByDefault
public final class LZ4JavaBehaviorTest {
    /// Verifies the standard raw-block bound and encoded output for representative source lengths.
    @Test
    public void rawBlockBoundCoversEncodedOutput() throws IOException {
        int @Unmodifiable [] lengths = {0, 1, 14, 15, 16, 255, 256, 65_535, 65_536, 1_048_577};
        for (int length : lengths) {
            LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(Math.max(1, length));
            byte @Unmodifiable [] input = randomBytes(length, 0x51b0_0dL + length);
            byte @Unmodifiable [] compressed = compress(codec, input);

            assertEquals(length + length / 255L + 16L, codec.maxCompressedSize(length));
            assertTrue(compressed.length <= codec.maxCompressedSize(length), "length " + length);
            assertArrayEquals(input, decompress(codec, compressed), "length " + length);
        }
    }

    /// Verifies literal-only worst-case blocks across token and extension-length boundaries.
    @Test
    public void decodesLiteralOnlyWorstCaseBlocks() throws IOException {
        int @Unmodifiable [] lengths = {0, 1, 14, 15, 16, 269, 270, 65_537};
        for (int length : lengths) {
            byte @Unmodifiable [] expected = randomBytes(length, 0x71ce_4aL + length);
            byte @Unmodifiable [] compressed = literalOnlyBlock(expected);
            LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(Math.max(1, length));

            assertArrayEquals(expected, decompress(codec, compressed), "length " + length);
        }
    }

    /// Verifies malformed match endings, short final literals, and a zero match offset are rejected.
    @Test
    public void rejectsMalformedRawBlockSequences() {
        LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(64);
        byte @Unmodifiable [] endsWithMatch = {0x60, 42, 43, 44, 45, 46, 47, 5, 0};
        assertThrows(IOException.class, () -> decompress(codec, endsWithMatch));

        for (int finalLiteralCount = 1; finalLiteralCount < 5; finalLiteralCount++) {
            byte[] tooFewFinalLiterals = Arrays.copyOf(
                    endsWithMatch,
                    endsWithMatch.length + 1 + finalLiteralCount
            );
            tooFewFinalLiterals[endsWithMatch.length] = (byte) (finalLiteralCount << 4);
            assertThrows(
                    IOException.class,
                    () -> decompress(codec, tooFewFinalLiterals),
                    "final literal count " + finalLiteralCount
            );
        }

        byte @Unmodifiable [] zeroOffset = {
                0x10, 42, 0, 0, (byte) 0x80, 42, 42, 42, 42, 42, 42, 42, 42
        };
        assertThrows(IOException.class, () -> decompress(codec, zeroOffset));
    }

    /// Verifies highly repetitive, random, and maximum-match-distance patterns round-trip.
    @Test
    public void roundTripsRepresentativeRawBlockPatterns() throws IOException {
        LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(256 * 1024L);

        byte[] equal = new byte[100_003];
        Arrays.fill(equal, (byte) 0xa5);
        assertArrayEquals(equal, decompress(codec, compress(codec, equal)));

        byte[] maximumDistance = Arrays.copyOf(randomBytes(180_000, 0x65_535L), 180_000);
        System.arraycopy(maximumDistance, 1024, maximumDistance, 1024 + 65_535, 32_768);
        assertArrayEquals(maximumDistance, decompress(codec, compress(codec, maximumDistance)));

        byte @Unmodifiable [] random = randomBytes(131_101, 0x4c5a_3401L);
        assertArrayEquals(random, decompress(codec, compress(codec, random)));
    }

    /// Verifies read-only output buffers fail through both incremental engine directions.
    @Test
    public void rejectsReadOnlyEngineTargets() throws IOException {
        LZ4Codec codec = new LZ4Codec();
        byte @Unmodifiable [] input = randomBytes(1024, 0x8bad_f00dL);
        try (CompressionEncoder encoder = codec.newEncoder()) {
            assertThrows(
                    ReadOnlyBufferException.class,
                    () -> encoder.encode(
                            ByteBuffer.wrap(input),
                            ByteBuffer.allocate(64).asReadOnlyBuffer()
                    )
            );
        }

        byte @Unmodifiable [] compressed = compress(codec, input);
        try (CompressionDecoder decoder = codec.newDecoder()) {
            assertThrows(
                    ReadOnlyBufferException.class,
                    () -> decoder.decode(
                            ByteBuffer.wrap(compressed),
                            ByteBuffer.allocate(input.length).asReadOnlyBuffer()
                    )
            );
        }
    }

    /// Verifies an incompressible-bit variant of the zero-length frame EndMark is accepted.
    @Test
    public void acceptsIncompressibleZeroLengthEndMark() throws IOException {
        LZ4Codec codec = new LZ4Codec().withContentChecksum(false);
        byte @Unmodifiable [] input = {(byte) 0xee};
        byte[] compressed = compress(codec, input);
        ByteBuffer.wrap(compressed)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(compressed.length - Integer.BYTES, 0x8000_0000);

        assertArrayEquals(input, decompress(codec, compressed));
    }

    /// Verifies skippable-only input and the parser's handling of absent, incomplete, or trailing frame magic.
    @Test
    public void handlesSkippableOnlyAndMalformedFrameTails() throws IOException {
        byte @Unmodifiable [] skippable = skippableFrame();
        assertArrayEquals(new byte[0], decompress(new LZ4Codec(), skippable));

        byte @Unmodifiable [] trailingByte = Arrays.copyOf(skippable, skippable.length + 1);
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), trailingByte));
        assertArrayEquals(new byte[0], decompress(new LZ4Codec(), new byte[0]));
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), new byte[1]));

        try (CompressionDecoder decoder = new LZ4Codec().newDecoder()) {
            assertThrows(
                    IOException.class,
                    () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
        }
    }

    /// Verifies byte-at-a-time stream operations and repeated close calls preserve content.
    @Test
    public void streamAdaptersSupportPerByteOperationsAndRepeatedClose() throws IOException {
        byte @Unmodifiable [] input = randomBytes(4097, 0x5378L);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        OutputStream encoder = new LZ4Codec().newOutputStream(compressed);
        for (byte value : input) {
            encoder.write(Byte.toUnsignedInt(value));
        }
        encoder.close();
        encoder.close();

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        InputStream decoder = new LZ4Codec().newInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        );
        int value;
        while ((value = decoder.read()) >= 0) {
            decoded.write(value);
        }
        decoder.close();
        decoder.close();
        assertArrayEquals(input, decoded.toByteArray());
    }

    /// Encodes one literal-only LZ4 block.
    private static byte @Unmodifiable [] literalOnlyBlock(byte @Unmodifiable [] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input.length < 15) {
            output.write(input.length << 4);
        } else {
            output.write(0xf0);
            int remaining = input.length - 15;
            while (remaining >= 255) {
                output.write(255);
                remaining -= 255;
            }
            output.write(remaining);
        }
        output.writeBytes(input);
        return output.toByteArray();
    }

    /// Creates one deterministic LZ4 skippable frame.
    private static byte @Unmodifiable [] skippableFrame() {
        byte @Unmodifiable [] payload = randomBytes(1024, 0x478_278L);
        return ByteBuffer.allocate(2 * Integer.BYTES + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x184d_2a57)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /// Compresses one complete byte sequence through a codec stream adapter.
    private static byte @Unmodifiable [] compress(
            org.glavo.arkivo.codec.CompressionCodec<?> codec,
            byte @Unmodifiable [] input
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream encoder = codec.newOutputStream(output)) {
            encoder.write(input);
        }
        return output.toByteArray();
    }

    /// Decompresses one complete byte sequence through a codec stream adapter.
    private static byte @Unmodifiable [] decompress(
            org.glavo.arkivo.codec.CompressionCodec<?> codec,
            byte @Unmodifiable [] input
    ) throws IOException {
        try (InputStream decoder = codec.newInputStream(new ByteArrayInputStream(input))) {
            return decoder.readAllBytes();
        }
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte @Unmodifiable [] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
