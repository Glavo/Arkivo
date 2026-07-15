// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public raw PPMd7 codec contract.
@NotNullByDefault
final class PPMdCodecTest {
    /// Verifies service discovery and the bidirectional capability surface.
    @Test
    void exposesCompressionCapabilities() {
        PPMdCodec codec = new PPMdCodec();

        assertTrue(codec.canCompress());
        assertTrue(codec.canDecompress());
        assertTrue(codec.canCompressBuffers());
        assertTrue(codec.canDecompressBuffers());
        assertTrue(codec.capabilities().supports(CompressionFeature.DIRECT_BYTE_BUFFER));
        assertTrue(codec.capabilities().compressionOptions().contains(PPMdCodecOptions.MAXIMUM_ORDER));
        assertTrue(codec.capabilities().compressionOptions().contains(PPMdCodecOptions.MEMORY_SIZE));
        assertNotNull(CompressionCodecs.find(PPMdCodec.NAME));
        assertNotNull(CompressionCodecs.find("ppmd7"));
    }

    /// Round-trips empty, repetitive, full-alphabet, and randomized data through channel contexts.
    @Test
    void roundTripsRawStreamsThroughChannels() throws IOException {
        byte[] random = new byte[128 * 1_024];
        new Random(0x50504d64L).nextBytes(random);
        byte[] alphabet = new byte[256 * 64];
        for (int index = 0; index < alphabet.length; index++) {
            alphabet[index] = (byte) index;
        }
        byte[][] samples = {
                new byte[0],
                {(byte) 0xa5},
                "PPMd Variant H adaptive context compression ".repeat(512)
                        .getBytes(StandardCharsets.UTF_8),
                alphabet,
                random
        };

        PPMdCodec codec = new PPMdCodec();
        CodecOptions compressionOptions = modelOptions();
        for (byte[] sample : samples) {
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            CodecTransferResult compression = codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(sample)),
                    Channels.newChannel(compressedBytes),
                    compressionOptions
            );
            assertEquals(sample.length, compression.inputBytes());
            assertEquals(compressedBytes.size(), compression.outputBytes());
            assertTrue(compressedBytes.size() >= 5);

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            CodecTransferResult decompression = codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes),
                    options(sample.length)
            );
            assertEquals(sample.length, decompression.outputBytes());
            assertArrayEquals(sample, decodedBytes.toByteArray());
        }
    }

    /// Round-trips direct buffers through the one-shot convenience API and its default encoder parameters.
    @Test
    void roundTripsDirectBuffersWithDefaultCompressionOptions() throws IOException {
        byte[] expected = ("direct PPMd buffer 0123456789abcdef;").repeat(1_024)
                .getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(expected.length).put(expected).flip();
        PPMdCodec codec = new PPMdCodec();

        ByteBuffer compressed = codec.compress(source);
        ByteBuffer decoded = codec.decompress(compressed, expected.length, defaultModelOptions(expected.length));

        assertFalse(source.hasRemaining());
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Matches a raw PPMd7 stream independently produced by 7-Zip 26.02 with order 6 and 16 MiB of model memory.
    @Test
    void matchesOfficialSevenZipVector() throws IOException {
        byte[] input = "Arkivo PPMd7 interoperability vector".getBytes(StandardCharsets.UTF_8);
        byte[] expected = HexFormat.of().parseHex(
                "004132f3e8a4c33d237af0157d27f1c7a7de5502f672000f78d5223a5a2c352cb8e543b300"
        );

        ByteBuffer actual = new PPMdCodec().compress(ByteBuffer.wrap(input));

        byte[] actualBytes = new byte[actual.remaining()];
        actual.get(actualBytes);
        assertArrayEquals(expected, actualBytes);
    }

    /// Verifies compression option ranges and endpoint ownership.
    @Test
    void validatesCompressionParametersAndHonorsOwnership() throws IOException {
        PPMdCodec codec = new PPMdCodec();
        WritableByteChannel lowOrderTarget = Channels.newChannel(new ByteArrayOutputStream());
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openEncoder(
                        lowOrderTarget,
                        CodecOptions.builder().set(PPMdCodecOptions.MAXIMUM_ORDER, 1L).build(),
                        ChannelOwnership.RETAIN
                )
        );
        assertTrue(lowOrderTarget.isOpen());

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        WritableByteChannel ownedTarget = Channels.newChannel(encoded);
        try (var encoder = codec.openEncoder(ownedTarget, modelOptions(), ChannelOwnership.CLOSE)) {
            encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
            assertEquals(4L, encoder.inputBytes());
        }
        assertFalse(ownedTarget.isOpen());
        assertTrue(encoded.size() >= 5);
    }

    /// Verifies required model options, output limits, counters, and source ownership.
    @Test
    void validatesRawParametersAndHonorsOwnership() throws IOException {
        PPMdCodec codec = new PPMdCodec();
        ReadableByteChannel missingOptions = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openDecoder(missingOptions, CodecOptions.EMPTY, ChannelOwnership.RETAIN)
        );

        CodecOptions limited = CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 4L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 1L << 20)
                .set(PPMdCodecOptions.DECODED_SIZE, 1L)
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, 0L)
                .build();
        assertThrows(
                DecompressionLimitException.class,
                () -> codec.openDecoder(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        limited,
                        ChannelOwnership.RETAIN
                )
        );

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[5]));
        try (CompressionDecoder decoder = codec.openDecoder(
                source,
                options(0L),
                ChannelOwnership.CLOSE
        )) {
            assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
            assertEquals(5L, decoder.inputBytes());
            assertEquals(0L, decoder.outputBytes());
        }
        assertFalse(source.isOpen());
    }

    /// Creates valid raw PPMd7 options for the given exact decoded size.
    private static CodecOptions options(long decodedSize) {
        return CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 4L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 1L << 20)
                .set(PPMdCodecOptions.DECODED_SIZE, decodedSize)
                .build();
    }

    /// Creates the explicit model options used by channel round-trip tests.
    private static CodecOptions modelOptions() {
        return CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 4L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 1L << 20)
                .build();
    }

    /// Creates decoder options matching the codec's default encoder model.
    private static CodecOptions defaultModelOptions(long decodedSize) {
        return CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 6L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 16L << 20)
                .set(PPMdCodecOptions.DECODED_SIZE, decodedSize)
                .build();
    }
}
