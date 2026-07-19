// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests standard-frame and raw-block LZ4 codec behavior.
@NotNullByDefault
public final class LZ4CodecTest {
    /// Verifies that exact operation metadata is emitted in the standard frame descriptor.
    @Test
    public void writesDeclaredContentSize() throws IOException {
        byte[] input = testData(10_003);
        byte[] compressed;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = LZ4Codec.DEFAULT.newEncoder(
                EncodingOptions.ofSourceSize(input.length)
        )) {
            encode(encoder, ByteBuffer.wrap(input), output, 7);
            finish(encoder, output, 3);
            compressed = output.toByteArray();
        }

        assertTrue((compressed[4] & 0x08) != 0);
        assertEquals(input.length, ByteArrayAccess.readLongLittleEndian(compressed, 6));
        assertArrayEquals(input, decompress(LZ4Codec.DEFAULT, compressed));
    }

    /// Verifies every standard block-size descriptor through independent frame round trips.
    @Test
    public void roundTripsEveryFrameBlockSize() throws IOException {
        byte[] input = testData(300_017);
        for (LZ4BlockSize blockSize : LZ4BlockSize.values()) {
            LZ4Codec codec = LZ4Codec.builder()
                    .blockSize(blockSize)
                    .blockChecksum(true)
                    .contentChecksum(true)
                    .build();
            byte[] compressed = compress(codec, input);
            assertArrayEquals(input, decompress(codec, compressed), blockSize.name());
            assertTrue(codec.maxCompressedSize(input.length) >= compressed.length, blockSize.name());
        }
    }

    /// Verifies linked blocks use and reproduce the preceding 64 KiB history.
    @Test
    public void roundTripsLinkedBlocks() throws IOException {
        byte[] first = testData(LZ4BlockSize.KIB_64.byteSize());
        byte[] input = Arrays.copyOf(first, first.length + 40_003);
        System.arraycopy(first, 1, input, first.length, input.length - first.length);
        LZ4Codec codec = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .independentBlocks(false)
                .blockChecksum(true)
                .contentChecksum(true)
                .build();

        assertArrayEquals(input, decompress(codec, compress(codec, input)));
    }

    /// Verifies raw block encoding and a manually specified interoperable block sequence.
    @Test
    public void roundTripsRawBlocksAndDecodesKnownSequence() throws IOException {
        LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(128 * 1024L);
        byte[] input = "raw lz4 block ".repeat(4096).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(input, decompress(codec, compress(codec, input)));

        byte[] encoded = {
                0x44, 'a', 'b', 'c', 'd', 0x04, 0x00,
                0x50, '1', '2', '3', '4', '5'
        };
        assertArrayEquals(
                "abcdabcdabcd12345".getBytes(StandardCharsets.US_ASCII),
                decompress(codec, encoded)
        );
        assertArrayEquals(new byte[0], decompress(codec, new byte[]{0}));
        assertThrows(IOException.class, () -> decompress(codec, new byte[0]));
        assertThrows(IOException.class, () -> decompress(codec, new byte[]{0x00, 0x00, 0x00}));
    }

    /// Verifies tiny direct buffers, frame boundaries, and trailing-input preservation.
    @Test
    public void fragmentedEnginesPreserveBoundariesAndTrailingInput() throws IOException {
        byte[] first = testData(70_003);
        byte[] second = testData(33_019);
        LZ4Codec codec = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .blockChecksum(true)
                .build();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Framed encoder = codec.newEncoder()) {
            encode(encoder, ByteBuffer.wrap(first), encoded, 3);
            finishFrame(encoder, encoded, 2);
            encode(encoder, ByteBuffer.wrap(second), encoded, 4);
            finishFrame(encoder, encoded, 1);
            finish(encoder, encoded, 3);
        }

        byte[] tail = {9, 8, 7};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.size() + tail.length);
        source.put(encoded.toByteArray()).put(tail).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = codec.newDecoder()) {
            while (true) {
                ByteBuffer target = ByteBuffer.allocateDirect(2);
                CodecOutcome outcome = decoder.decode(source, target);
                drain(target, decoded);
                if (outcome == CodecOutcome.FINISHED) {
                    break;
                }
            }
        }
        assertArrayEquals(first, decoded.toByteArray());
        assertTrue(source.remaining() > tail.length);

        byte[] expected = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, decompress(codec, encoded.toByteArray()));
    }

    /// Verifies stream adapters skip a complete skippable frame before decoding following content.
    @Test
    public void skipsSkippableFramesInConcatenatedStreams() throws IOException {
        byte[] expected = "after skippable frame".getBytes(StandardCharsets.UTF_8);
        byte[] standardFrame = compress(new LZ4Codec(), expected);
        ByteBuffer source = ByteBuffer.allocate(8 + 3 + standardFrame.length);
        source.put(new byte[]{0x51, 0x2a, 0x4d, 0x18});
        source.put(new byte[]{3, 0, 0, 0});
        source.put(new byte[]{9, 8, 7});
        source.put(standardFrame);

        assertArrayEquals(expected, decompress(new LZ4Codec(), source.array()));
    }

    /// Verifies content and block checksum corruption is rejected when verification is enabled.
    @Test
    public void rejectsChecksumCorruption() throws IOException {
        byte[] input = testData(20_017);
        LZ4Codec contentCodec = new LZ4Codec();
        byte[] badContent = compress(contentCodec, input);
        badContent[badContent.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decompress(contentCodec, badContent));

        LZ4Codec blockCodec = contentCodec.withBlockChecksum(true);
        byte[] badBlock = compress(blockCodec, input);
        int payloadSize = ByteArrayAccess.readIntLittleEndian(badBlock, 7) & 0x7fff_ffff;
        badBlock[11 + payloadSize] ^= 1;
        assertThrows(IOException.class, () -> decompress(blockCodec, badBlock));
        assertArrayEquals(input, decompress(blockCodec.withVerifyChecksums(false), badBlock));
    }

    /// Verifies owned compressed payloads are rejected before exceeding decoder working-memory limits.
    @Test
    public void enforcesMemoryLimitsBeforeRetainingPayloads() throws IOException {
        int maximumMemorySize = 65_535;
        try (CompressionDecoder decoder = new LZ4BlockCodec()
                .withMaximumBlockSize(128 * 1024L)
                .withMaximumMemorySize(maximumMemorySize)
                .newDecoder()) {
            ByteBuffer source = ByteBuffer.allocate(maximumMemorySize + 1);
            assertThrows(
                    DecompressionMemoryLimitException.class,
                    () -> decoder.decode(source, ByteBuffer.allocate(1))
            );
            assertEquals(0, source.position());
        }

        byte[] framePrefix = Arrays.copyOf(compress(new LZ4Codec(), new byte[0]), 11);
        ByteArrayAccess.writeIntLittleEndian(framePrefix, 7, maximumMemorySize + 1);
        try (CompressionDecoder decoder = new LZ4Codec()
                .withMaximumMemorySize(maximumMemorySize)
                .newDecoder()) {
            assertThrows(
                    DecompressionMemoryLimitException.class,
                    () -> decoder.decode(ByteBuffer.wrap(framePrefix), ByteBuffer.allocate(1))
            );
        }
    }

    /// Verifies standard, legacy, and skippable magic detection plus installed aliases.
    @Test
    public void formatMetadataAndDiscovery() {
        assertSame(LZ4Format.instance(), CompressionFormats.require("lz4"));
        assertSame(LZ4Format.instance(), CompressionFormats.require("lz4-frame"));
        assertSame(LZ4BlockFormat.instance(), CompressionFormats.require("lz4-raw"));
        assertTrue(LZ4Format.instance().matches(ByteBuffer.wrap(new byte[]{0x04, 0x22, 0x4d, 0x18})));
        assertTrue(LZ4Format.instance().matches(ByteBuffer.wrap(new byte[]{0x50, 0x2a, 0x4d, 0x18})));
        assertTrue(LZ4Format.instance().matches(ByteBuffer.wrap(new byte[]{0x02, 0x21, 0x4c, 0x18})));
        assertFalse(LZ4Format.instance().matches(ByteBuffer.wrap(new byte[]{0x02, 0x21, 0x4c, 0x19})));
        assertEquals(0, LZ4BlockFormat.instance().probeSize());
    }

    /// Compresses one byte array through a codec output stream.
    private static byte[] compress(org.glavo.arkivo.codec.CompressionCodec<?> codec, byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.io.OutputStream encoder = codec.newOutputStream(output)) {
            encoder.write(input);
        }
        return output.toByteArray();
    }

    /// Decompresses one complete encoding through a codec input stream.
    private static byte[] decompress(org.glavo.arkivo.codec.CompressionCodec<?> codec, byte[] input) throws IOException {
        try (java.io.InputStream decoder = codec.newInputStream(new ByteArrayInputStream(input))) {
            return decoder.readAllBytes();
        }
    }

    /// Supplies a complete source buffer to an incremental encoder.
    private static void encode(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        while (source.hasRemaining()) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            CodecOutcome outcome = encoder.encode(source, target);
            drain(target, output);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
        }
    }

    /// Drains one explicit nonterminal frame boundary.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
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

    /// Drains terminal encoder finalization.
    private static void finish(
            CompressionEncoder encoder,
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

    /// Copies produced bytes from one target buffer into a byte stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Returns deterministic incompressible-looking test bytes.
    private static byte[] testData(int length) {
        byte[] bytes = new byte[length];
        new Random(0x4c5a_3401L + length).nextBytes(bytes);
        return bytes;
    }

}
