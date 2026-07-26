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

/// Tests exhaustive small-literal, offset, and output-padding interoperability with Aircompressor.
@NotNullByDefault
public final class ZstdAircompressorCompatibilityMatrixTest {
    /// Verifies every literal length from zero through 255 in both encoding directions.
    @Test
    public void interoperatesForEverySmallLiteralLength() throws IOException {
        ZstdCompressor compressor = new ZstdCompressor();
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] literals = ascendingBytes();

        for (int size = 0; size <= literals.length; size++) {
            assertInteroperable(compressor, decompressor, Arrays.copyOf(literals, size), 7, 11);
        }
    }

    /// Verifies frame-window and block boundaries with padded source and destination buffers.
    @Test
    public void interoperatesAtFrameBoundariesWithOffsets() throws IOException {
        ZstdCompressor compressor = new ZstdCompressor();
        ZstdDecompressor decompressor = new ZstdDecompressor();
        int[] sizes = {
                1, 255, 256, 1_023, 1_024,
                65_535, 65_536,
                131_071, 131_072, 131_073,
                262_144, 1_048_589
        };

        for (int size : sizes) {
            assertInteroperable(compressor, decompressor, repeatedBytes(size), 19, 23);
            assertInteroperable(
                    compressor,
                    decompressor,
                    randomBytes(size, 0x5a57_4400L + size),
                    29,
                    31
            );
        }
    }

    /// Verifies both encoding directions while using nonzero source and destination offsets.
    private static void assertInteroperable(
            ZstdCompressor compressor,
            ZstdDecompressor decompressor,
            byte[] input,
            int inputOffset,
            int outputOffset
    ) throws IOException {
        byte[] paddedInput = new byte[inputOffset + input.length + 13];
        System.arraycopy(input, 0, paddedInput, inputOffset, input.length);

        int maximumCompressedSize = compressor.maxCompressedLength(input.length);
        byte[] paddedCompressed = new byte[outputOffset + maximumCompressedSize + 17];
        int compressedSize = compressor.compress(
                paddedInput,
                inputOffset,
                input.length,
                paddedCompressed,
                outputOffset,
                maximumCompressedSize
        );
        byte[] encodedByAircompressor = Arrays.copyOfRange(
                paddedCompressed,
                outputOffset,
                outputOffset + compressedSize
        );
        assertArrayEquals(
                input,
                decompressWithArkivo(encodedByAircompressor, input.length),
                context(input)
        );

        byte[] encodedByArkivo = compressWithArkivo(input);
        byte[] paddedArkivoEncoding = new byte[inputOffset + encodedByArkivo.length + 13];
        System.arraycopy(encodedByArkivo, 0, paddedArkivoEncoding, inputOffset, encodedByArkivo.length);
        byte[] decoded = new byte[outputOffset + input.length + 17];
        Arrays.fill(decoded, (byte) 0x5a);
        byte[] expectedPaddedOutput = decoded.clone();
        System.arraycopy(input, 0, expectedPaddedOutput, outputOffset, input.length);

        int decodedSize = decompressor.decompress(
                paddedArkivoEncoding,
                inputOffset,
                encodedByArkivo.length,
                decoded,
                outputOffset,
                input.length
        );
        assertEquals(input.length, decodedSize, context(input));
        assertArrayEquals(expectedPaddedOutput, decoded, context(input));
    }

    /// Compresses one frame with Arkivo.
    private static byte[] compressWithArkivo(byte[] input) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = ZstdCodec.DEFAULT.newOutputStream(encoded)) {
            output.write(input);
        }
        return encoded.toByteArray();
    }

    /// Decompresses one frame with Arkivo.
    private static byte[] decompressWithArkivo(byte[] encoded, int decodedSize) throws IOException {
        try (InputStream input = ZstdCodec.DEFAULT
                .withMaximumOutputSize(decodedSize)
                .newInputStream(new ByteArrayInputStream(encoded))) {
            return input.readAllBytes();
        }
    }

    /// Returns the 255 nonempty-prefix bytes used by the small-literal matrix.
    private static byte[] ascendingBytes() {
        byte[] result = new byte[255];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    /// Returns a deterministic input with both short and long copy opportunities.
    private static byte[] repeatedBytes(int size) {
        byte[] result = new byte[size];
        byte[] pattern = "Arkivo-Zstandard-copy-pattern/".getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < result.length; index++) {
            result[index] = pattern[index % pattern.length];
        }
        return result;
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte[] randomBytes(int size, long seed) {
        byte[] result = new byte[size];
        new Random(seed).nextBytes(result);
        return result;
    }

    /// Describes a vector in assertion failures.
    private static String context(byte[] input) {
        return "decoded size " + input.length;
    }
}
