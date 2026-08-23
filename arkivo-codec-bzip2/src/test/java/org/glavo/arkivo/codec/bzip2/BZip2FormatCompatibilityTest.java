// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies BZip2 format compatibility and malformed-stream handling through the public codec API.
@NotNullByDefault
final class BZip2FormatCompatibilityTest {
    /// Independently generated BZip2 data for the reference fixture.
    private static final String INDEPENDENT_FIXTURE =
            "QlpoOTFBWSZTWeHHcTAAAAWZgEAAEAAXIVZQIAAiJ6IHlNqEAABSaMKuRnrU+AFl3p/F3JFOFCQ4cdxMAA==";

    /// Maximum decoded size accepted by this test suite's allocating helper.
    private static final long MAXIMUM_TEST_OUTPUT_SIZE = 1_000_000L;

    /// Shared default BZip2 codec.
    private static final BZip2Codec CODEC = new BZip2Codec();

    /// Decodes a stream produced by an independent BZip2 implementation.
    @Test
    void decodesIndependentFixture() throws IOException {
        assertArrayEquals(
                "independent bzip2 fixture".getBytes(StandardCharsets.UTF_8),
                decompress(Base64.getDecoder().decode(INDEPENDENT_FIXTURE))
        );
    }

    /// Round-trips empty and small inputs through every declared block size.
    @Test
    void roundTripsEveryBlockSize() throws IOException {
        byte[][] inputs = {
                new byte[0],
                new byte[]{0},
                "native BZip2 round trip".getBytes(StandardCharsets.UTF_8),
                allByteValues(4)
        };
        for (int blockSize = 1; blockSize <= 9; blockSize++) {
            for (byte[] input : inputs) {
                assertArrayEquals(input, decompress(compress(input, blockSize)));
            }
        }
    }

    /// Round-trips first-stage runs longer than the format's 259-byte segment limit.
    @Test
    void roundTripsLongRuns() throws IOException {
        byte[] input = new byte[300_000];
        Arrays.fill(input, 0, 150_000, (byte) 'A');
        Arrays.fill(input, 150_000, input.length, (byte) 'B');
        assertArrayEquals(input, decompress(compress(input, 1)));
    }

    /// Round-trips several incompressible blocks and validates stream-level CRC chaining.
    @Test
    void roundTripsMultipleBlocks() throws IOException {
        byte[] input = new byte[320_000];
        new Random(0x41524b49564fL).nextBytes(input);
        assertArrayEquals(input, decompress(compress(input, 1)));
    }

    /// Accepts the legacy randomized-block flag when the block ends before the first scheduled bit toggle.
    @Test
    void decodesLegacyRandomizedBlock() throws IOException {
        byte[] expected = "legacy randomized block".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(expected, 9);
        compressed[14] |= (byte) 0x80;
        assertArrayEquals(expected, decompress(compressed));
    }

    /// Rejects invalid stream signatures and block-size digits.
    @Test
    void rejectsInvalidHeaders() {
        assertThrows(IOException.class, () -> decompress(new byte[]{'B', 'Z', '0', '9'}));
        assertThrows(IOException.class, () -> decompress(new byte[]{'B', 'Z', 'h', '0'}));
    }

    /// Rejects corrupted block and stream CRC values.
    @Test
    void rejectsCrcCorruption() throws IOException {
        byte[] compressed = compress("CRC protected block".getBytes(StandardCharsets.UTF_8), 9);
        compressed[10] ^= 0x40;
        assertThrows(IOException.class, () -> decompress(compressed));

        byte[] empty = compress(new byte[0], 9);
        empty[empty.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decompress(empty));
    }

    /// Rejects truncation in headers, compressed blocks, and stream trailers.
    @Test
    void rejectsTruncatedStreams() throws IOException {
        byte[] compressed = compress(allByteValues(8), 9);
        int[] truncationPoints = {3, 4, 9, compressed.length / 2, compressed.length - 1};
        for (int length : truncationPoints) {
            byte[] truncated = Arrays.copyOf(compressed, length);
            assertThrows(IOException.class, () -> decompress(truncated));
        }
    }

    /// Compresses bytes with the requested BZip2 block size.
    private static byte[] compress(byte[] input, int blockSize) throws IOException {
        ByteBuffer encoded = CODEC.withCompressionLevel(blockSize).compress(ByteBuffer.wrap(input));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Decompresses one or more complete BZip2 frames under the test output bound.
    private static byte[] decompress(byte[] compressed) throws IOException {
        ByteBuffer decoded = CODEC.withMaximumOutputSize(MAXIMUM_TEST_OUTPUT_SIZE)
                .decompress(ByteBuffer.wrap(compressed));
        byte[] result = new byte[decoded.remaining()];
        decoded.get(result);
        return result;
    }

    /// Returns repeated ascending byte values.
    private static byte[] allByteValues(int repetitions) {
        byte[] values = new byte[256 * repetitions];
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) index;
        }
        return values;
    }
}
