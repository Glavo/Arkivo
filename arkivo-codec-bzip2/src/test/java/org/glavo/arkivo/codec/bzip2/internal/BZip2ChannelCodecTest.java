// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the channel-first BZip2 encoder and decoder.
@NotNullByDefault
public final class BZip2ChannelCodecTest {
    /// Independently generated BZip2 data for the independent fixture.
    private static final String INDEPENDENT_FIXTURE =
            "QlpoOTFBWSZTWeHHcTAAAAWZgEAAEAAXIVZQIAAiJ6IHlNqEAABSaMKuRnrU+AFl3p/F3JFOFCQ4cdxMAA==";

    /// Decodes a stream produced by an independent BZip2 implementation.
    @Test
    public void decodesIndependentFixture() throws IOException {
        byte[] compressed = Base64.getDecoder().decode(INDEPENDENT_FIXTURE);
        try (BZip2ChannelDecoder decoder = decoder(compressed, ChannelOwnership.CLOSE)) {
            assertArrayEquals("independent bzip2 fixture".getBytes(StandardCharsets.UTF_8), readAll(decoder));
            assertEquals(compressed.length, decoder.inputBytes());
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

    /// Preserves prefetched container bytes when decoding stops at one BZip2 frame.
    @Test
    public void stopsAtFrameWithoutLosingReadAhead() throws IOException {
        byte[] expected = "bounded compressed body".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(expected, 9);
        byte[] suffix = new byte[]{0x50, 0x4b, 0x07, 0x08};
        byte[] sourceBytes = Arrays.copyOf(compressed, compressed.length + suffix.length);
        System.arraycopy(suffix, 0, sourceBytes, compressed.length, suffix.length);
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(sourceBytes));

        try (BZip2ChannelDecoder decoder =
                     new BZip2ChannelDecoder(source, ChannelOwnership.RETAIN)) {
            assertArrayEquals(expected, readFrame(decoder));
            assertEquals(compressed.length, decoder.inputBytes());
            assertEquals(sourceBytes.length, decoder.sourceBytes());

            ByteBuffer unconsumed = decoder.unconsumedInput();
            byte[] actualSuffix = new byte[unconsumed.remaining()];
            unconsumed.get(actualSuffix);
            assertArrayEquals(suffix, actualSuffix);
            assertTrue(source.isOpen());
        }
        assertTrue(source.isOpen());
        source.close();
    }

    /// Encodes and decodes multiple BZip2 frames through explicit frame boundaries.
    @Test
    public void roundTripsConcatenatedFrames() throws IOException {
        byte[] first = "first BZip2 frame".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second BZip2 frame".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (BZip2ChannelEncoder encoder = new BZip2ChannelEncoder(
                Channels.newChannel(target),
                ChannelOwnership.RETAIN,
                1
        )) {
            writeAll(encoder, first);
            encoder.finishFrame();
            writeAll(encoder, second);
        }

        try (BZip2ChannelDecoder decoder = decoder(target.toByteArray(), ChannelOwnership.CLOSE)) {
            assertArrayEquals(first, readFrame(decoder));
            assertArrayEquals(second, readFrame(decoder));
            ByteBuffer end = ByteBuffer.allocate(1);
            CodecResult result = decoder.decode(end);
            assertEquals(CodecResult.Status.END_OF_INPUT, result.status());
        }
    }

    /// Accepts the legacy randomized-block flag when the block ends before the first scheduled bit toggle.
    @Test
    public void decodesLegacyRandomizedBlock() throws IOException {
        byte[] expected = "legacy randomized block".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(expected, 9);
        compressed[14] |= (byte) 0x80;
        try (BZip2ChannelDecoder decoder = decoder(compressed, ChannelOwnership.CLOSE)) {
            assertArrayEquals(expected, readAll(decoder));
        }
    }

    /// Rejects invalid stream headers and block-size digits.
    @Test
    public void rejectsInvalidHeaders() {
        assertThrows(IOException.class, () -> decoder(new byte[0], ChannelOwnership.CLOSE));
        assertThrows(IOException.class, () -> decoder(
                new byte[]{'B', 'Z', '0', '9'},
                ChannelOwnership.CLOSE
        ));
        assertThrows(IOException.class, () -> decoder(
                new byte[]{'B', 'Z', 'h', '0'},
                ChannelOwnership.CLOSE
        ));
    }

    /// Rejects corrupted block and stream CRC values.
    @Test
    public void rejectsCrcCorruption() throws IOException {
        byte[] compressed = compress("CRC protected block".getBytes(StandardCharsets.UTF_8), 9);
        compressed[10] ^= 0x40;
        try (BZip2ChannelDecoder decoder = decoder(compressed, ChannelOwnership.CLOSE)) {
            assertThrows(IOException.class, () -> readAll(decoder));
        }

        byte[] empty = compress(new byte[0], 9);
        empty[empty.length - 1] ^= 1;
        try (BZip2ChannelDecoder decoder = decoder(empty, ChannelOwnership.CLOSE)) {
            assertThrows(IOException.class, () -> readAll(decoder));
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
                try (BZip2ChannelDecoder decoder = decoder(truncated, ChannelOwnership.CLOSE)) {
                    readAll(decoder);
                }
            });
        }
    }

    /// Validates block-size arguments, ownership, and terminal encoder lifecycle.
    @Test
    public void validatesEncoderLifecycle() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new BZip2ChannelEncoder(
                Channels.newChannel(new ByteArrayOutputStream()),
                ChannelOwnership.RETAIN,
                0
        ));
        assertThrows(IllegalArgumentException.class, () -> new BZip2ChannelEncoder(
                Channels.newChannel(new ByteArrayOutputStream()),
                ChannelOwnership.RETAIN,
                10
        ));

        TrackingOutputStream retainedTarget = new TrackingOutputStream();
        BZip2ChannelEncoder retained = new BZip2ChannelEncoder(
                Channels.newChannel(retainedTarget),
                ChannelOwnership.RETAIN,
                1
        );
        writeAll(retained, new byte[]{'A'});
        retained.finish();
        assertFalse(retainedTarget.closed);
        assertThrows(ClosedChannelException.class, () -> retained.write(ByteBuffer.wrap(new byte[]{'B'})));
        retained.close();
        assertFalse(retainedTarget.closed);

        TrackingOutputStream ownedTarget = new TrackingOutputStream();
        BZip2ChannelEncoder owned = new BZip2ChannelEncoder(
                Channels.newChannel(ownedTarget),
                ChannelOwnership.CLOSE,
                1
        );
        owned.finish();
        owned.close();
        assertTrue(ownedTarget.closed);
    }

    /// Closing a decoder closes its owned compressed source and prevents further reads.
    @Test
    public void closesDecoderSource() throws IOException {
        TrackingInputStream source = new TrackingInputStream(compress(new byte[0], 9));
        BZip2ChannelDecoder decoder = new BZip2ChannelDecoder(
                Channels.newChannel(source),
                ChannelOwnership.CLOSE
        );
        decoder.close();
        decoder.close();
        assertTrue(source.closed);
        assertThrows(ClosedChannelException.class, () -> decoder.read(ByteBuffer.allocate(1)));
    }

    /// Compresses and then decompresses bytes with the requested block size.
    private static byte[] roundTrip(byte[] input, int blockSize) throws IOException {
        byte[] compressed = compress(input, blockSize);
        try (BZip2ChannelDecoder decoder = decoder(compressed, ChannelOwnership.CLOSE)) {
            return readAll(decoder);
        }
    }

    /// Compresses bytes with the requested block size.
    private static byte[] compress(byte[] input, int blockSize) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (BZip2ChannelEncoder encoder = new BZip2ChannelEncoder(
                Channels.newChannel(target),
                ChannelOwnership.RETAIN,
                blockSize
        )) {
            writeAll(encoder, input);
        }
        return target.toByteArray();
    }

    /// Opens a decoder over in-memory compressed bytes.
    private static BZip2ChannelDecoder decoder(byte[] compressed, ChannelOwnership ownership) throws IOException {
        return new BZip2ChannelDecoder(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                ownership
        );
    }

    /// Writes all source bytes to an encoder.
    private static void writeAll(BZip2ChannelEncoder encoder, byte[] input) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(input);
        while (source.hasRemaining()) {
            int written = encoder.write(source);
            if (written == 0) {
                throw new IOException("BZip2 encoder made no progress");
            }
        }
    }

    /// Reads one complete BZip2 frame from a decoder.
    private static byte[] readFrame(BZip2ChannelDecoder decoder) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocateDirect(31);
        while (true) {
            target.clear();
            CodecResult result = decoder.decodeFrame(target);
            target.flip();
            byte[] chunk = new byte[target.remaining()];
            target.get(chunk);
            output.writeBytes(chunk);
            if (result.status() == CodecResult.Status.FRAME_FINISHED) {
                return output.toByteArray();
            }
            if (result.status() == CodecResult.Status.END_OF_INPUT) {
                throw new IOException("BZip2 stream ended before a frame boundary");
            }
            if (result.inputBytes() == 0L && result.outputBytes() == 0L) {
                throw new IOException("BZip2 decoder made no progress");
            }
        }
    }

    /// Reads all decoded bytes from a decoder.
    private static byte[] readAll(BZip2ChannelDecoder decoder) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocateDirect(8192);
        while (true) {
            target.clear();
            int read = decoder.read(target);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                throw new IOException("BZip2 decoder made no progress");
            }
            target.flip();
            byte[] chunk = new byte[target.remaining()];
            target.get(chunk);
            output.writeBytes(chunk);
        }
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