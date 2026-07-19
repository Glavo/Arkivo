// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public raw PPMd7 codec contract.
@NotNullByDefault
final class PPMdCodecTest {
    /// Verifies official format discovery and immutable PPMd configuration.
    @Test
    void exposesImmutableConfiguration() {
        PPMdCodec codec = new PPMdCodec();

        assertEquals(PPMdCodec.DEFAULT_MAXIMUM_ORDER, codec.maximumOrder());
        assertEquals(PPMdCodec.DEFAULT_MEMORY_SIZE, codec.memorySize());
        assertEquals(PPMdCodec.UNKNOWN_SIZE, codec.decodedSize());
        PPMdCodec configured = codec.withMaximumOrder(4).withMemorySize(1L << 20);
        assertEquals(4, configured.maximumOrder());
        assertEquals(1L << 20, configured.memorySize());
        assertEquals(PPMdCodec.DEFAULT_MAXIMUM_ORDER, codec.maximumOrder());
        assertSame(
                CompressionFormats.require(PPMdFormat.NAME),
                CompressionFormats.require("ppmd7")
        );
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

        PPMdCodec codec = modelCodec();
        for (byte[] sample : samples) {
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            CodecTransferResult compression = codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(sample)),
                    Channels.newChannel(compressedBytes)
            );
            assertEquals(sample.length, compression.inputBytes());
            assertEquals(compressedBytes.size(), compression.outputBytes());
            assertTrue(compressedBytes.size() >= 5);

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            CodecTransferResult decompression = codec.withDecodedSize(sample.length).decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
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
        ByteBuffer decoded = codec
                .withDecodedSize(expected.length)
                .withMaximumOutputSize(expected.length)
                .decompress(compressed);

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

    /// Verifies compression-configuration ranges and endpoint ownership.
    @Test
    void validatesCompressionParametersAndHonorsOwnership() throws IOException {
        PPMdCodec codec = new PPMdCodec();
        WritableByteChannel lowOrderTarget = Channels.newChannel(new ByteArrayOutputStream());
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumOrder(1));
        assertTrue(lowOrderTarget.isOpen());

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        WritableByteChannel ownedTarget = Channels.newChannel(encoded);
        try (var encoder = modelCodec().newWritableByteChannel(
                ownedTarget,
                EncodingOptions.DEFAULT,
                ResourceOwnership.OWNED
        )) {
            encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
            assertEquals(4L, encoder.inputBytes());
        }
        assertFalse(ownedTarget.isOpen());
        assertTrue(encoded.size() >= 5);
    }

    /// Verifies required model metadata, output limits, counters, and source ownership.
    @Test
    void validatesRawParametersAndHonorsOwnership() throws IOException {
        PPMdCodec codec = new PPMdCodec();
        ReadableByteChannel missingOptions = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        assertThrows(
                IllegalStateException.class,
                () -> codec.newReadableByteChannel(
                        missingOptions,
                        ResourceOwnership.BORROWED
                )
        );

        ByteBuffer singleByteFrame = modelCodec().compress(ByteBuffer.wrap(new byte[]{1}));
        byte[] singleByteFrameBytes = new byte[singleByteFrame.remaining()];
        singleByteFrame.get(singleByteFrameBytes);
        try (DecompressingReadableByteChannel limitedDecoder = decoderCodec(1L)
                .withMaximumOutputSize(0L).newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(singleByteFrameBytes)),
                ResourceOwnership.BORROWED
        )) {
            assertThrows(
                    DecompressionLimitException.class,
                    () -> limitedDecoder.read(ByteBuffer.allocate(1))
            );
        }

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[5]));
        try (DecompressingReadableByteChannel decoder = decoderCodec(0L).newReadableByteChannel(
                source,
                ResourceOwnership.OWNED
        )) {
            assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
            assertEquals(5L, decoder.inputBytes());
            assertEquals(0L, decoder.outputBytes());
        }
        assertFalse(source.isOpen());
    }

    /// Rejects a configured PPMd model arena that exceeds the operation memory limit.
    @Test
    void enforcesModelMemoryLimit() {
        PPMdCodec codec = decoderCodec(0L);
        long maximumMemorySize = codec.memorySize() - 1L;

        DecompressionMemoryLimitException exception = assertThrows(
                DecompressionMemoryLimitException.class,
                () -> codec.withMaximumMemorySize(maximumMemorySize).newDecoder()
        );

        assertEquals(maximumMemorySize, exception.maximumMemorySize());
        assertEquals(codec.memorySize(), exception.requiredMemorySize());
        assertEquals(DecompressionLimitException.Kind.MEMORY_SIZE, exception.kind());
    }

    /// Creates a valid raw PPMd7 configuration for the given exact decoded size.
    private static PPMdCodec decoderCodec(long decodedSize) {
        return modelCodec().withDecodedSize(decodedSize);
    }

    /// Creates the explicit model configuration used by channel round-trip tests.
    private static PPMdCodec modelCodec() {
        return new PPMdCodec().withMaximumOrder(4).withMemorySize(1L << 20);
    }
}
