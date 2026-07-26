// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests Zstandard frame interoperability with Aircompressor.
///
/// The literal, repeated-copy, and state-reuse cases mirror the core data model used by Aircompressor's compression
/// tests while keeping the default suite independent of external corpus files.
@NotNullByDefault
public final class ZstdAircompressorInteroperabilityTest {
    /// Verifies that Arkivo decodes frames produced by a reused Aircompressor instance.
    @Test
    public void decodesAircompressorFrames() throws IOException {
        ZstdCompressor compressor = new ZstdCompressor();
        for (byte[] input : testCases()) {
            byte[] compressed = compressWithAircompressor(compressor, input);
            assertArrayEquals(input, decompressWithArkivo(compressed, input.length), context(input));
        }
    }

    /// Verifies that Aircompressor decodes frames produced by Arkivo.
    @Test
    public void aircompressorDecodesArkivoFrames() throws IOException {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        for (byte[] input : testCases()) {
            byte[] compressed = compressWithArkivo(input);
            byte[] decoded = new byte[input.length];
            int decodedSize = decompressor.decompress(
                    compressed,
                    0,
                    compressed.length,
                    decoded,
                    0,
                    decoded.length
            );
            assertEquals(input.length, decodedSize, context(input));
            assertArrayEquals(input, decoded, context(input));
        }
    }

    /// Compresses one frame with Aircompressor.
    private static byte[] compressWithAircompressor(ZstdCompressor compressor, byte[] input) {
        byte[] compressed = new byte[compressor.maxCompressedLength(input.length)];
        int compressedSize = compressor.compress(
                input,
                0,
                input.length,
                compressed,
                0,
                compressed.length
        );
        return Arrays.copyOf(compressed, compressedSize);
    }

    /// Compresses one frame with Arkivo.
    private static byte[] compressWithArkivo(byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = ZstdCodec.DEFAULT.newOutputStream(compressed)) {
            output.write(input);
        }
        return compressed.toByteArray();
    }

    /// Decompresses one complete frame with Arkivo.
    private static byte[] decompressWithArkivo(byte[] compressed, int decodedSize) throws IOException {
        try (InputStream input = ZstdCodec.DEFAULT
                .withMaximumOutputSize(decodedSize)
                .newInputStream(new ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    /// Returns representative literal, copy, empty, and incompressible inputs.
    private static byte[][] testCases() {
        return new byte[][]{
                new byte[0],
                "hello world!".getBytes(StandardCharsets.UTF_8),
                "XXXXabcdabcdABCDABCDwxyzwzyz123".getBytes(StandardCharsets.UTF_8),
                "XXXXabcdefgh abcdefgh abcdefgh abcdefgh abcdefgh abcdefgh ABC"
                        .getBytes(StandardCharsets.UTF_8),
                ascendingBytes(),
                "Arkivo Zstandard copy sequence ".repeat(257).getBytes(StandardCharsets.UTF_8),
                randomBytes()
        };
    }

    /// Returns 256 bytes whose unsigned values increase from zero through 255.
    private static byte[] ascendingBytes() {
        byte[] result = new byte[256];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    /// Returns a deterministic 131,089-byte pseudo-random vector.
    private static byte[] randomBytes() {
        byte[] result = new byte[131_089];
        new Random(0x5a57_4401L).nextBytes(result);
        return result;
    }

    /// Describes a vector in assertion failures.
    private static String context(byte[] input) {
        return "decoded size " + input.length;
    }
}
