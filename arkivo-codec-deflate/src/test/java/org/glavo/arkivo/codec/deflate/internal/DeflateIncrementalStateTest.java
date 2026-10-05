// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises suspended grammar fields, long Huffman codes, and reused history across input boundaries.
@NotNullByDefault
final class DeflateIncrementalStateTest {
    /// Dynamic headers and overlapping matches survive independent input and output fragmentation.
    @Test
    void resumesAcrossBufferShapesAndReset() throws Exception {
        byte[] body = new byte[70_000];
        new Random(42).nextBytes(body);
        for (int i = 0; i < body.length; i++) body[i] &= 15;
        byte[] blocks = compress(body, true);
        byte[] matches = new byte[70_000];
        Arrays.fill(matches, 0, 35_000, (byte) 'a');
        for (int i = 35_000; i < matches.length; i++) matches[i] = (byte) ('a' + i % 3);
        byte[] repeated = compress(matches, false);
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (int chunk : new int[]{1, 2, 7, 8192, 1048576}) {
                for (int shape = 0; shape < 3; shape++) {
                    for (int outputSize : new int[]{1, 7, 8192}) {
                        decoder.reset();
                        assertArrayEquals(body, decode(decoder, blocks, chunk, shape, outputSize));
                        decoder.reset();
                        assertArrayEquals(matches, decode(decoder, repeated, chunk, shape, outputSize));
                    }
                }
            }
        }
    }

    /// Fifteen-bit symbols retain their traversal node when their input ends after any byte.
    @Test
    void resumesLongCanonicalCodesAndRejectsTruncation() throws Exception {
        byte[] body = new byte[75];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i % 15);
        byte[] compressed = longCodes(body);
        Inflater reference = new Inflater(true);
        try {
            reference.setInput(compressed);
            byte[] actual = new byte[body.length + 1];
            assertEquals(body.length, reference.inflate(actual));
            assertTrue(reference.finished());
            assertArrayEquals(body, Arrays.copyOf(actual, body.length));
        } finally {
            reference.end();
        }
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (int chunk : new int[]{1, 2, 7, compressed.length}) {
                decoder.reset();
                assertArrayEquals(body, decode(decoder, compressed, chunk, 2, 3));
            }
            for (int cut = 0; cut < compressed.length; cut++) {
                decoder.reset();
                byte[] prefix = Arrays.copyOf(compressed, cut);
                assertThrows(IOException.class, () -> decode(decoder, prefix, 1, 0, 7), "cut=" + cut);
                decoder.reset();
                assertArrayEquals(body, decode(decoder, compressed, 7, 0, 8192));
            }
        }
    }

    /// A completed stream does not consume a following record, even when its last code needs fewer than eight bits.
    @Test
    void leavesTrailingInputAndRetainsWorkspace() throws Exception {
        byte[] body = new byte[4096];
        new Random(99).nextBytes(body);
        byte[] compressed = compress(body, true);
        byte[] framed = Arrays.copyOf(compressed, compressed.length + 4);
        Arrays.fill(framed, compressed.length, framed.length, (byte) 0x7f);
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            var workspace = DeflateDecoderEngine.class.getDeclaredField("dynamicLiterals");
            workspace.setAccessible(true);
            Object original = workspace.get(decoder);
            for (int iteration = 0; iteration < 4; iteration++) {
                decoder.reset();
                ByteBuffer source = ByteBuffer.wrap(framed).asReadOnlyBuffer();
                assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(source, ByteBuffer.allocate(0)));
                assertEquals(0, source.position());
                ByteBuffer target = ByteBuffer.allocate(body.length + 1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                assertEquals(compressed.length, source.position());
                assertEquals(4, source.remaining());
                assertArrayEquals(body, Arrays.copyOf(target.array(), target.position()));
                assertSame(original, workspace.get(decoder));
            }
        }
    }

    /// Supplies disjoint caller buffers and accumulates small output fragments until the raw stream ends.
    private static byte[] decode(
            CompressionDecoder decoder, byte[] compressed, int chunk, int shape, int outputSize
    ) throws IOException {
        var decoded = new ByteArrayOutputStream();
        int supplied = 0;
        int capacity = Math.min(chunk, Math.max(1, compressed.length));
        ByteBuffer storage = shape == 1 ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        ByteBuffer source = shape == 2 ? storage.asReadOnlyBuffer() : storage;
        source.limit(0);
        ByteBuffer output = shape == 1 ? ByteBuffer.allocateDirect(outputSize) : ByteBuffer.allocate(outputSize);
        byte[] scratch = new byte[outputSize];
        for (int operations = 0; operations < compressed.length * 8 + 300_000; operations++) {
            if (!source.hasRemaining() && supplied < compressed.length) {
                int count = Math.min(chunk, compressed.length - supplied);
                storage.clear().put(compressed, supplied, count).flip();
                source.position(0).limit(count);
                supplied += count;
            }
            output.clear();
            CodecOutcome result = supplied == compressed.length
                    ? decoder.finish(source, output) : decoder.decode(source, output);
            output.flip();
            int count = output.remaining();
            output.get(scratch, 0, count);
            decoded.write(scratch, 0, count);
            if (result == CodecOutcome.FINISHED) {
                assertFalse(source.hasRemaining());
                return decoded.toByteArray();
            }
            if (result == CodecOutcome.NEEDS_INPUT) assertFalse(source.hasRemaining());
        }
        throw new AssertionError("Decoder did not terminate");
    }

    /// Creates common JDK streams with either many dynamic headers or short-distance matches.
    private static byte[] compress(byte[] body, boolean blocks) {
        Deflater deflater = new Deflater(6, true);
        try {
            if (blocks) deflater.setStrategy(Deflater.HUFFMAN_ONLY);
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int offset = 0; offset < body.length; offset += 1024) {
                deflater.setInput(body, offset, Math.min(1024, body.length - offset));
                while (true) {
                    int count = deflater.deflate(buffer, 0, buffer.length,
                            blocks ? Deflater.FULL_FLUSH : Deflater.NO_FLUSH);
                    output.write(buffer, 0, count);
                    if (deflater.needsInput() && count < buffer.length) break;
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Builds a complete comb-shaped literal alphabet with code lengths from one through fifteen.
    private static byte[] longCodes(byte[] body) {
        BitWriter writer = new BitWriter();
        writer.write(5, 3);
        writer.write(0, 5);
        writer.write(0, 5);
        writer.write(15, 4);
        int @Unmodifiable [] order = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
        for (int symbol : order) writer.write(symbol < 16 ? 4 : 0, 3);
        int[] lengths = new int[257];
        for (int symbol = 0; symbol < 14; symbol++) lengths[symbol] = symbol + 1;
        lengths[14] = lengths[256] = 15;
        for (int length : lengths) writer.write(Integer.reverse(length) >>> 28, 4);
        writer.write(8, 4); // The one-bit distance code length, encoded by the code-length alphabet.
        int[] counts = new int[16];
        for (int length : lengths) if (length != 0) counts[length]++;
        int[] next = new int[16];
        for (int length = 1; length < next.length; length++) next[length] = (next[length - 1] + counts[length - 1]) << 1;
        int[] codes = new int[lengths.length];
        for (int symbol = 0; symbol < lengths.length; symbol++) {
            if (lengths[symbol] != 0) codes[symbol] = next[lengths[symbol]]++;
        }
        for (byte value : body) {
            int symbol = Byte.toUnsignedInt(value);
            writer.write(Integer.reverse(codes[symbol]) >>> (32 - lengths[symbol]), lengths[symbol]);
        }
        writer.write(Integer.reverse(codes[256]) >>> 17, 15);
        return writer.finish();
    }

    /// Packs independent test fields in Deflate's least-significant-bit-first byte order.
    @NotNullByDefault
    private static final class BitWriter {
        /// Completed bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// Pending low-order bits.
        private int bits;
        /// Pending bit count.
        private int count;

        /// Appends one unsigned field.
        private void write(int value, int width) {
            bits |= value << count;
            count += width;
            while (count >= 8) {
                output.write(bits & 255);
                bits >>>= 8;
                count -= 8;
            }
        }

        /// Pads the final partial byte and returns the encoded stream.
        private byte[] finish() {
            if (count != 0) output.write(bits & 255);
            return output.toByteArray();
        }
    }
}
