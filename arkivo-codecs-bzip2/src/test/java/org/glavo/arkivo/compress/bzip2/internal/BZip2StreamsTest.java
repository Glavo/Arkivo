// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the native BZip2 stream encoder and decoder.
@NotNullByDefault
public final class BZip2StreamsTest {
    /// Independently generated BZip2 data for `independent bzip2 fixture`.
    private static final String INDEPENDENT_FIXTURE =
            "QlpoOTFBWSZTWeHHcTAAAAWZgEAAEAAXIVZQIAAiJ6IHlNqEAABSaMKuRnrU+AFl3p/F3JFOFCQ4cdxMAA==";

    /// Decodes a stream produced by an independent BZip2 implementation.
    @Test
    public void decodesIndependentFixture() throws IOException {
        byte[] compressed = Base64.getDecoder().decode(INDEPENDENT_FIXTURE);
        try (BZip2InputStream input = new BZip2InputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals("independent bzip2 fixture".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            assertEquals(compressed.length, input.compressedByteCount());
        }
    }

    /// Round-trips empty and small inputs through every declared block size.
    @Test
    public void roundTripsEveryBlockSize() throws IOException {
        byte[][] inputs = {
                new byte[0],
                new byte[]{0},
                "native BZip2 round trip".getBytes(StandardCharsets.UTF_8),
                allByteValues(4)
        };
        for (int blockSize = 1; blockSize <= 9; blockSize++) {
            for (byte[] input : inputs) {
                assertArrayEquals(input, roundTrip(input, blockSize));
            }
        }
    }

    /// Round-trips first-stage runs longer than the format's 259-byte segment limit.
    @Test
    public void roundTripsLongRuns() throws IOException {
        byte[] input = new byte[300_000];
        Arrays.fill(input, 0, 150_000, (byte) 'A');
        Arrays.fill(input, 150_000, input.length, (byte) 'B');
        assertArrayEquals(input, roundTrip(input, 1));
    }

    /// Round-trips several incompressible blocks and validates stream-level CRC chaining.
    @Test
    public void roundTripsMultipleBlocks() throws IOException {
        byte[] input = new byte[320_000];
        new Random(0x41524b49564fL).nextBytes(input);
        assertArrayEquals(input, roundTrip(input, 1));
    }

    /// Stops exactly after the BZip2 trailer without consuming following container bytes.
    @Test
    public void doesNotReadPastTrailer() throws IOException {
        byte[] expected = "bounded compressed body".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(expected, 9);
        byte[] suffix = new byte[]{0x50, 0x4b, 0x07, 0x08};
        byte[] sourceBytes = Arrays.copyOf(compressed, compressed.length + suffix.length);
        System.arraycopy(suffix, 0, sourceBytes, compressed.length, suffix.length);
        ByteArrayInputStream source = new ByteArrayInputStream(sourceBytes);

        BZip2InputStream input = new BZip2InputStream(source);
        assertArrayEquals(expected, input.readAllBytes());
        assertEquals(compressed.length, input.compressedByteCount());
        assertEquals(suffix.length, source.available());
        assertEquals(Byte.toUnsignedInt(suffix[0]), source.read());
    }

    /// Accepts the legacy randomized-block flag when the block ends before the first scheduled bit toggle.
    @Test
    public void decodesLegacyRandomizedBlock() throws IOException {
        byte[] expected = "legacy randomized block".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(expected, 9);
        compressed[14] |= (byte) 0x80;
        try (BZip2InputStream input = new BZip2InputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Rejects invalid stream headers and block-size digits.
    @Test
    public void rejectsInvalidHeaders() {
        assertThrows(IOException.class, () -> new BZip2InputStream(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> new BZip2InputStream(
                new ByteArrayInputStream(new byte[]{'B', 'Z', '0', '9'})
        ));
        assertThrows(IOException.class, () -> new BZip2InputStream(
                new ByteArrayInputStream(new byte[]{'B', 'Z', 'h', '0'})
        ));
    }

    /// Rejects corrupted block and stream CRC values.
    @Test
    public void rejectsCrcCorruption() throws IOException {
        byte[] compressed = compress("CRC protected block".getBytes(StandardCharsets.UTF_8), 9);
        compressed[10] ^= 0x40;
        try (BZip2InputStream input = new BZip2InputStream(new ByteArrayInputStream(compressed))) {
            assertThrows(IOException.class, input::readAllBytes);
        }

        byte[] empty = compress(new byte[0], 9);
        empty[empty.length - 1] ^= 1;
        try (BZip2InputStream input = new BZip2InputStream(new ByteArrayInputStream(empty))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Rejects truncation in headers, compressed blocks, and stream trailers.
    @Test
    public void rejectsTruncatedStreams() throws IOException {
        byte[] compressed = compress(allByteValues(8), 9);
        int[] truncationPoints = {0, 3, 4, 9, compressed.length / 2, compressed.length - 1};
        for (int length : truncationPoints) {
            byte[] truncated = Arrays.copyOf(compressed, length);
            assertThrows(IOException.class, () -> {
                try (BZip2InputStream input = new BZip2InputStream(new ByteArrayInputStream(truncated))) {
                    input.readAllBytes();
                }
            });
        }
    }

    /// Validates block-size arguments and finish-versus-close ownership behavior.
    @Test
    public void validatesOutputLifecycle() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new BZip2OutputStream(new ByteArrayOutputStream(), 0));
        assertThrows(IllegalArgumentException.class, () -> new BZip2OutputStream(new ByteArrayOutputStream(), 10));

        TrackingOutputStream target = new TrackingOutputStream();
        BZip2OutputStream output = new BZip2OutputStream(target, 1);
        output.write('A');
        output.finish();
        assertFalse(target.closed);
        assertThrows(IOException.class, () -> output.write('B'));
        output.close();
        assertTrue(target.closed);
    }

    /// Closing a decoder closes its compressed source and prevents further reads.
    @Test
    public void closesDecoderSource() throws IOException {
        TrackingInputStream source = new TrackingInputStream(compress(new byte[0], 9));
        BZip2InputStream input = new BZip2InputStream(source);
        input.close();
        assertTrue(source.closed);
        assertThrows(IOException.class, input::read);
    }

    /// Compresses and then decompresses bytes with the requested block size.
    private static byte[] roundTrip(byte[] input, int blockSize) throws IOException {
        byte[] compressed = compress(input, blockSize);
        try (BZip2InputStream decoder = new BZip2InputStream(new ByteArrayInputStream(compressed))) {
            return decoder.readAllBytes();
        }
    }

    /// Compresses bytes with the requested block size.
    private static byte[] compress(byte[] input, int blockSize) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (BZip2OutputStream output = new BZip2OutputStream(target, blockSize)) {
            output.write(input);
        }
        return target.toByteArray();
    }

    /// Returns repeated ascending byte values.
    private static byte[] allByteValues(int repetitions) {
        byte[] values = new byte[256 * repetitions];
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) index;
        }
        return values;
    }

    /// Records whether an in-memory target has closed.
    @NotNullByDefault
    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Marks this stream closed.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /// Records whether an in-memory source has closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Creates a source over the given bytes.
        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Marks this stream closed.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
