// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;
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

/// Tests raw LZ4 block interoperability with Aircompressor.
///
/// The literal, repeated-copy, and state-reuse cases mirror the core data model used by Aircompressor's compression
/// tests while keeping the default suite independent of external corpus files.
@NotNullByDefault
public final class LZ4AircompressorInteroperabilityTest {
    /// Verifies that Arkivo decodes blocks produced by a reused Aircompressor instance.
    @Test
    public void decodesAircompressorBlocks() throws IOException {
        Lz4Compressor compressor = new Lz4Compressor();
        for (byte[] input : testCases()) {
            byte[] compressed = compressWithAircompressor(compressor, input);
            assertArrayEquals(input, decompressWithArkivo(compressed, input.length), context(input));
        }
    }

    /// Verifies that Aircompressor decodes raw blocks produced by Arkivo.
    @Test
    public void aircompressorDecodesArkivoBlocks() throws IOException {
        Lz4Decompressor decompressor = new Lz4Decompressor();
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

    /// Compresses one raw block with Aircompressor.
    private static byte[] compressWithAircompressor(Lz4Compressor compressor, byte[] input) {
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

    /// Compresses one raw block with Arkivo.
    private static byte[] compressWithArkivo(byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codecFor(input.length).newOutputStream(compressed)) {
            output.write(input);
        }
        return compressed.toByteArray();
    }

    /// Decompresses one complete raw block with Arkivo.
    private static byte[] decompressWithArkivo(byte[] compressed, int decodedSize) throws IOException {
        try (InputStream input = codecFor(decodedSize).newInputStream(new ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    /// Creates an exact-size raw block configuration for one test operation.
    private static LZ4BlockCodec codecFor(int decodedSize) {
        return LZ4BlockCodec.DEFAULT
                .withMaximumBlockSize(Math.max(1L, decodedSize))
                .withMaximumOutputSize(decodedSize);
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
                "Arkivo LZ4 copy sequence ".repeat(257).getBytes(StandardCharsets.UTF_8),
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

    /// Returns a deterministic 65,537-byte pseudo-random vector.
    private static byte[] randomBytes() {
        byte[] result = new byte[65_537];
        new Random(0x4c5a_3401L).nextBytes(result);
        return result;
    }

    /// Describes a vector in assertion failures.
    private static String context(byte[] input) {
        return "decoded size " + input.length;
    }
}
