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
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests exhaustive small-literal, offset, and output-padding interoperability with Aircompressor.
@NotNullByDefault
public final class LZ4AircompressorCompatibilityMatrixTest {
    /// Verifies every raw literal length from zero through 255 in both encoding directions.
    @Test
    public void interoperatesForEverySmallLiteralLength() throws IOException {
        Lz4Compressor compressor = new Lz4Compressor();
        Lz4Decompressor decompressor = new Lz4Decompressor();
        byte[] literals = ascendingBytes();

        for (int size = 0; size <= literals.length; size++) {
            assertInteroperable(compressor, decompressor, Arrays.copyOf(literals, size), 7, 11);
        }
    }

    /// Verifies representative token, extension-length, and block-size boundaries with padded buffers.
    @Test
    public void interoperatesAtBlockBoundariesWithOffsets() throws IOException {
        Lz4Compressor compressor = new Lz4Compressor();
        Lz4Decompressor decompressor = new Lz4Decompressor();
        int[] sizes = {
                1, 14, 15, 16, 254, 255, 256,
                65_535, 65_536, 65_537,
                262_144, 1_048_589
        };

        for (int size : sizes) {
            assertInteroperable(compressor, decompressor, repeatedBytes(size), 19, 23);
            assertInteroperable(
                    compressor,
                    decompressor,
                    randomBytes(size, 0x4c5a_3400L + size),
                    29,
                    31
            );
        }
    }

    /// Verifies both encoding directions while using nonzero source and destination offsets.
    private static void assertInteroperable(
            Lz4Compressor compressor,
            Lz4Decompressor decompressor,
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

    /// Compresses one raw block with Arkivo.
    private static byte[] compressWithArkivo(byte[] input) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = codecFor(input.length).newOutputStream(encoded)) {
            output.write(input);
        }
        return encoded.toByteArray();
    }

    /// Decompresses one raw block with Arkivo.
    private static byte[] decompressWithArkivo(byte[] encoded, int decodedSize) throws IOException {
        try (InputStream input = codecFor(decodedSize).newInputStream(new ByteArrayInputStream(encoded))) {
            return input.readAllBytes();
        }
    }

    /// Creates an exact-size raw LZ4 configuration.
    private static LZ4BlockCodec codecFor(int decodedSize) {
        return LZ4BlockCodec.DEFAULT
                .withMaximumBlockSize(Math.max(1L, decodedSize))
                .withMaximumOutputSize(decodedSize);
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
        byte[] pattern = "Arkivo-LZ4-copy-pattern/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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
