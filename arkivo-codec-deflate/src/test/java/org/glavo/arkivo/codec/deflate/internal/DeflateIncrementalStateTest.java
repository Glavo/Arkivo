// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises suspended grammar fields, long Huffman codes, and reused history across input boundaries.
@NotNullByDefault
final class DeflateIncrementalStateTest {
    /// Matches spanning calls and windows preserve slice boundaries, byte order, and trailing input.
    ///
    /// @param direct whether input and output use direct storage
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesThroughWindowedOutputSlices(boolean direct) throws Exception {
        byte[] body = new byte[150_031];
        new Random(0x57494e44L).nextBytes(body);
        for (int i = 16_384; i < body.length; i++) body[i] = body[i % 16_384];
        Arrays.fill(body, 65_500, 75_000, (byte) 'a');
        byte[] compressed = compress(body, false);
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                for (int outputSize : new int[]{257, 258, 259, 8192, 32768, 65536}) {
                    for (int chunk : new int[]{7, 8192, compressed.length + 8}) {
                        decoder.reset();
                        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(compressed.length + 13)
                                : ByteBuffer.allocate(compressed.length + 13);
                        storage.position(5).put(compressed).putLong(0x123456789abcdef0L).flip().position(5);
                        ByteBuffer source = storage.asReadOnlyBuffer().order(order);
                        source.limit(Math.min(storage.limit(), 5 + chunk));
                        ByteBuffer outputStorage = direct ? ByteBuffer.allocateDirect(outputSize + 15)
                                : ByteBuffer.allocate(outputSize + 15);
                        ByteBuffer output = outputStorage.slice(4, outputSize + 7).order(order);
                        byte[] scratch = new byte[outputSize];
                        var decoded = new ByteArrayOutputStream();
                        CodecOutcome outcome;
                        do {
                            output.clear();
                            for (int i = 0; i < output.capacity(); i++) output.put(i, (byte) 0x5a);
                            output.position(3).limit(3 + outputSize);
                            outcome = source.limit() == storage.limit()
                                    ? decoder.finish(source, output) : decoder.decode(source, output);
                            int produced = output.position() - 3;
                            output.get(3, scratch, 0, produced);
                            decoded.write(scratch, 0, produced);
                            assertEquals((byte) 0x5a, output.get(2));
                            assertEquals(3 + outputSize, output.limit());
                            output.limit(output.capacity());
                            assertEquals((byte) 0x5a, output.get(3 + outputSize));
                            assertSame(order, source.order());
                            assertSame(order, output.order());
                            if (outcome == CodecOutcome.NEEDS_INPUT) {
                                assertFalse(source.hasRemaining());
                                assertTrue(source.limit() < storage.limit());
                                source.limit(Math.min(storage.limit(), source.limit() + chunk));
                            } else {
                                assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
                            }
                        } while (outcome != CodecOutcome.FINISHED);
                        assertArrayEquals(body, decoded.toByteArray());
                        assertEquals(compressed.length + 5, source.position());
                    }
                }
            }
        }
    }

    /// An invalid fast-path match reports completed literals without exposing speculative output or consuming a trailer.
    @Test
    void preservesProgressWhenFastDistanceValidationFails() throws Exception {
        BitWriter writer = new BitWriter();
        writer.write(3, 3);
        for (int i = 0; i < 16; i++) writer.write(Integer.reverse(48 + 'a') >>> 24, 8);
        writer.write(Integer.reverse(1) >>> 25, 7); // Length three.
        writer.write(Integer.reverse(14) >>> 27, 5); // Distance 129 exceeds sixteen produced literals.
        writer.write(0, 6);
        byte[] invalid = writer.finish();
        byte[] framed = Arrays.copyOf(invalid, invalid.length + 8);
        Arrays.fill(framed, invalid.length, framed.length, (byte) 0x7f);
        for (boolean direct : new boolean[]{false, true}) {
            try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
                ByteBuffer source = ByteBuffer.wrap(framed).asReadOnlyBuffer();
                ByteBuffer target = direct ? ByteBuffer.allocateDirect(300) : ByteBuffer.allocate(300);
                target.position(3);
                IOException failure = assertThrows(IOException.class, () -> decoder.finish(source, target));
                assertTrue(failure.getMessage().contains("exceeds available history 16"));
                assertEquals(19, target.position());
                assertTrue(source.position() <= invalid.length);
                for (int i = 3; i < 19; i++) assertEquals((byte) 'a', target.get(i));
                assertEquals((byte) 0, target.get(19));
                decoder.reset();
                byte[] valid = compress(new byte[]{1, 2, 3}, false);
                assertArrayEquals(new byte[]{1, 2, 3}, decode(decoder, valid, 1, 0, 7));
            }
        }
    }

    /// Reserved symbols are rejected before producing a match or consuming speculative trailer bytes.
    ///
    /// @param symbol the reserved literal/length or distance symbol
    @ParameterizedTest
    @ValueSource(ints = {286, 287, 30, 31})
    void rejectsReservedFastPathSymbols(int symbol) throws Exception {
        BitWriter writer = new BitWriter();
        writer.write(3, 3);
        for (int i = 0; i < 16; i++) writer.write(Integer.reverse(48 + 'a') >>> 24, 8);
        if (symbol < 32) {
            writer.write(Integer.reverse(1) >>> 25, 7);
            writer.write(Integer.reverse(symbol) >>> 27, 5);
        } else {
            writer.write(Integer.reverse(192 + symbol - 280) >>> 24, 8);
        }
        byte[] invalid = writer.finish();
        byte[] framed = Arrays.copyOf(invalid, invalid.length + 8);
        Arrays.fill(framed, invalid.length, framed.length, (byte) 0x7f);
        for (boolean direct : new boolean[]{false, true}) {
            try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
                ByteBuffer source = ByteBuffer.wrap(framed).asReadOnlyBuffer();
                ByteBuffer target = direct ? ByteBuffer.allocateDirect(300) : ByteBuffer.allocate(300);
                IOException failure = assertThrows(IOException.class, () -> decoder.finish(source, target));
                assertTrue(failure.getMessage().contains("symbol " + symbol + " is invalid"));
                assertEquals(16, target.position());
                assertTrue(source.position() <= invalid.length);
                for (int i = 0; i < 16; i++) assertEquals((byte) 'a', target.get(i));
                assertEquals((byte) 0, target.get(16));
            }
        }
    }

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
    ///
    /// @param minimumLength the shortest code, selecting dense or byte-wide alphabets
    @ParameterizedTest
    @ValueSource(ints = {1, 7})
    void resumesLongCanonicalCodesAndRejectsTruncation(int minimumLength) throws Exception {
        int symbols = (1 << minimumLength) - 1 + 15 - minimumLength;
        byte[] body = new byte[symbols * 5];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i % symbols);
        byte[] compressed = longCodes(body, minimumLength);
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

    /// Short end codes stop at every final-byte alignment without consuming a following record.
    ///
    /// @param maximumLength the maximum code length in the test alphabet
    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void shortCodesPreserveEveryTrailingByteAlignment(int maximumLength) throws Exception {
        int[] lengths = new int[257];
        if (maximumLength == 1) {
            lengths['a'] = lengths[256] = 1;
        } else {
            Arrays.fill(lengths, 0, 126, 7);
            lengths[126] = lengths[127] = 8;
            lengths[256] = 7;
        }
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (int size = 0; size < 16; size++) {
                byte[] body = new byte[size];
                Arrays.fill(body, (byte) 'a');
                byte[] compressed = literalCodes(body, lengths);
                for (int shape = 0; shape < 3; shape++) {
                    decoder.reset();
                    ByteBuffer storage = shape == 1
                            ? ByteBuffer.allocateDirect(compressed.length + 8)
                            : ByteBuffer.allocate(compressed.length + 8);
                    storage.position(3).put(compressed).put(new byte[]{1, 2, 3, 4, 5}).flip().position(3);
                    ByteBuffer source = shape == 2 ? storage.asReadOnlyBuffer() : storage;
                    int originalLimit = source.limit();
                    var decoded = new ByteArrayOutputStream();
                    ByteBuffer target = ByteBuffer.allocate(1);
                    CodecOutcome outcome;
                    do {
                        target.clear();
                        outcome = decoder.finish(source, target);
                        if (target.position() != 0) decoded.write(target.get(0));
                        assertTrue(outcome == CodecOutcome.FINISHED || outcome == CodecOutcome.NEEDS_OUTPUT);
                    } while (outcome != CodecOutcome.FINISHED);
                    assertArrayEquals(body, decoded.toByteArray());
                    assertEquals(originalLimit, source.limit());
                    assertEquals(compressed.length + 3, source.position());
                    assertEquals(5, source.remaining());
                }
            }
        }
    }

    /// Complete canonical alphabets with different short-code and continuation layouts agree with the JDK.
    @Test
    void decodesGeneratedCanonicalAlphabets() throws Exception {
        Random random = new Random(0x48554646L);
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (int iteration = 0; iteration < 48; iteration++) {
                int leafCount = switch (iteration % 4) {
                    case 0 -> 2;
                    case 1 -> 16;
                    case 2 -> 128;
                    default -> 257;
                };
                int[] lengths = new int[257];
                for (int count = 1; count < leafCount; count++) {
                    int split;
                    do {
                        split = random.nextInt(count);
                    } while (lengths[split] == 15);
                    lengths[count] = ++lengths[split];
                }
                for (int index = lengths.length - 1; index > 0; index--) {
                    int other = random.nextInt(index + 1);
                    int length = lengths[index];
                    lengths[index] = lengths[other];
                    lengths[other] = length;
                }
                if (lengths[256] == 0) {
                    int symbol = 0;
                    while (lengths[symbol] == 0) symbol++;
                    lengths[256] = lengths[symbol];
                    lengths[symbol] = 0;
                }
                var bodyBytes = new ByteArrayOutputStream();
                for (int repeat = 0; repeat < 16; repeat++) {
                    for (int symbol = 0; symbol < 256; symbol++) {
                        if (lengths[symbol] != 0) bodyBytes.write(symbol);
                    }
                }
                byte[] body = bodyBytes.toByteArray();
                byte[] compressed = literalCodes(body, lengths);
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
                for (int chunk : new int[]{1, 7, compressed.length}) {
                    decoder.reset();
                    assertArrayEquals(body, decode(decoder, compressed, chunk, iteration % 3, 7));
                }
                for (int shape = 0; shape < 3; shape++) {
                    decoder.reset();
                    assertArrayEquals(body, decode(decoder, compressed, compressed.length, shape, 8192));
                }
            }
        }
    }

    /// Verifies maximum-width length/distance tokens at every byte alignment and an exact trailing-input boundary.
    @Test
    void decodesMaximumWidthTokensWithLargeCallerBuffers() throws Exception {
        for (DeflateDecoderEngine.Format format : DeflateDecoderEngine.Format.values()) {
            int windowSize = format == DeflateDecoderEngine.Format.DEFLATE ? 32768 : 65536;
            byte[] history = new byte[windowSize];
            new Random(0x48494646L).nextBytes(history);
            try (var decoder = new DeflateDecoderEngine(format, history)) {
                for (int padding = 0; padding < 8; padding++) {
                    byte[] compressed = maximumWidthTokens(format, padding);
                    byte[] expected = new byte[padding + 32 * 257];
                    System.arraycopy(history, padding, expected, padding, 32 * 257);
                    if (format == DeflateDecoderEngine.Format.DEFLATE) {
                        Inflater reference = new Inflater(true);
                        try {
                            reference.setDictionary(history);
                            reference.setInput(compressed);
                            byte[] actual = new byte[expected.length + 1];
                            assertEquals(expected.length, reference.inflate(actual));
                            assertTrue(reference.finished());
                            assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
                        } finally {
                            reference.end();
                        }
                    }
                    for (boolean direct : new boolean[]{false, true}) {
                        decoder.reset();
                        ByteBuffer source = ByteBuffer.allocate(compressed.length + 16);
                        source.put(compressed).put(new byte[16]).flip();
                        source = source.asReadOnlyBuffer();
                        ByteBuffer target = direct ? ByteBuffer.allocateDirect(expected.length + 8)
                                : ByteBuffer.allocate(expected.length + 8);
                        for (int i = 0; i < target.capacity(); i++) target.put(i, (byte) 0x55);
                        target.position(3).limit(4 + expected.length);
                        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                        assertEquals(compressed.length, source.position());
                        assertEquals(3 + expected.length, target.position());
                        assertEquals((byte) 0x55, target.get(2));
                        assertEquals((byte) 0x55, target.get(target.position()));
                        byte[] actual = new byte[expected.length];
                        target.get(3, actual);
                        assertArrayEquals(expected, actual);
                    }
                }
            }
        }
    }

    /// Builds complete comb alphabets whose match requires 48 bits, or 49 with an extended distance.
    private static byte[] maximumWidthTokens(DeflateDecoderEngine.Format format, int padding) {
        int[] literals = new int[286];
        int[] distances = new int[format == DeflateDecoderEngine.Format.DEFLATE ? 30 : 32];
        for (int i = 0; i < 14; i++) literals[i] = distances[i] = i + 1;
        literals[256] = literals[284] = 15;
        distances[distances.length - 2] = distances[distances.length - 1] = 15;
        int[] literalCodes = canonicalCodes(literals);
        int[] distanceCodes = canonicalCodes(distances);
        BitWriter writer = new BitWriter();
        writer.write(5, 3);
        writer.write(literals.length - 257, 5);
        writer.write(distances.length - 1, 5);
        writer.write(15, 4);
        int @Unmodifiable [] order = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
        for (int symbol : order) writer.write(symbol < 16 ? 4 : 0, 3);
        for (int length : literals) writer.write(Integer.reverse(length) >>> 28, 4);
        for (int length : distances) writer.write(Integer.reverse(length) >>> 28, 4);
        for (int i = 0; i < padding; i++) writeCanonical(writer, literalCodes, literals, 0);
        int distanceBits = format == DeflateDecoderEngine.Format.DEFLATE ? 13 : 14;
        for (int i = 0; i < 32; i++) {
            writeCanonical(writer, literalCodes, literals, 284);
            writer.write(30, 5);
            writeCanonical(writer, distanceCodes, distances, distances.length - 1);
            writer.write((1 << distanceBits) - 1, distanceBits);
        }
        writeCanonical(writer, literalCodes, literals, 256);
        return writer.finish();
    }

    /// Assigns canonical codes in symbol order for an independently constructed test alphabet.
    private static int[] canonicalCodes(int[] lengths) {
        int[] counts = new int[16];
        for (int length : lengths) if (length != 0) counts[length]++;
        int[] next = new int[16];
        for (int length = 1; length < next.length; length++) next[length] = (next[length - 1] + counts[length - 1]) << 1;
        int[] codes = new int[lengths.length];
        for (int symbol = 0; symbol < lengths.length; symbol++) {
            if (lengths[symbol] != 0) codes[symbol] = next[lengths[symbol]]++;
        }
        return codes;
    }

    /// Writes one canonical test symbol in Deflate wire order.
    private static void writeCanonical(BitWriter writer, int[] codes, int[] lengths, int symbol) {
        writer.write(Integer.reverse(codes[symbol]) >>> (32 - lengths[symbol]), lengths[symbol]);
    }

    /// An unused one-bit branch is rejected after a complete alphabet has occupied the same workspace.
    @Test
    void rejectsUnusedSingletonBranchAfterReset() throws Exception {
        int[] lengths = new int[257];
        lengths[256] = 1;
        byte[] empty = literalCodes(new byte[0], lengths);
        byte[] invalid = empty.clone();
        int endBit = 3 + 5 + 5 + 4 + 19 * 3 + 258 * 4;
        invalid[endBit >>> 3] |= (byte) (1 << (endBit & 7));
        byte[] body = {0, 1, 14, 2, 13};
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE, null)) {
            for (int chunk : new int[]{1, 7, empty.length}) {
                decoder.reset();
                assertArrayEquals(body, decode(decoder, longCodes(body, 1), chunk, 0, 7));
                decoder.reset();
                assertArrayEquals(new byte[0], decode(decoder, empty, chunk, 0, 7));
                decoder.reset();
                assertThrows(IOException.class, () -> decode(decoder, invalid, chunk, 0, 7));
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

    /// Splits one leaf of a complete alphabet into a comb ending in two fifteen-bit codes.
    private static byte[] longCodes(byte[] body, int minimumLength) {
        int[] lengths = new int[257];
        int symbol = (1 << minimumLength) - 1;
        Arrays.fill(lengths, 0, symbol, minimumLength);
        for (int length = minimumLength + 1; length < 15; length++) lengths[symbol++] = length;
        lengths[symbol] = lengths[256] = 15;
        return literalCodes(body, lengths);
    }

    /// Encodes a literal-only final block using independently assigned canonical codes.
    private static byte[] literalCodes(byte[] body, int[] lengths) {
        BitWriter writer = new BitWriter();
        writer.write(5, 3);
        writer.write(0, 5);
        writer.write(0, 5);
        writer.write(15, 4);
        int @Unmodifiable [] order = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
        for (int symbol : order) writer.write(symbol < 16 ? 4 : 0, 3);
        for (int length : lengths) writer.write(Integer.reverse(length) >>> 28, 4);
        writer.write(8, 4); // The one-bit distance code length, encoded by the code-length alphabet.
        int[] codes = canonicalCodes(lengths);
        for (byte value : body) {
            int symbol = Byte.toUnsignedInt(value);
            writer.write(Integer.reverse(codes[symbol]) >>> (32 - lengths[symbol]), lengths[symbol]);
        }
        writer.write(Integer.reverse(codes[256]) >>> (32 - lengths[256]), lengths[256]);
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
