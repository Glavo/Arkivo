// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0 AND MIT
// Generators adapted from libdeflate 1.26; see NOTICE and LICENSES/Libdeflate-MIT.txt.

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises libdeflate's Huffman, input-overread, slow-input, and literal-run regressions.
@NotNullByDefault
final class LibdeflateRegressionTest {
    /// A generated stream and its independently specified decoded bytes.
    ///
    /// @param name the upstream scenario
    /// @param compressed the complete raw Deflate encoding
    /// @param expected the expected decoded bytes
    @NotNullByDefault
    private record Vector(String name, byte @Unmodifiable [] compressed, byte @Unmodifiable [] expected) {
        /// Returns the upstream scenario name for parameterized test reports.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Crosses the four incomplete-code cases with input, output, and buffer layouts.
    private static Stream<Arguments> incompleteCases() {
        return vectors().stream().flatMap(vector -> Stream.of(1, 2, 7, 8192).flatMap(inputSize ->
                Stream.of(1, 7).flatMap(outputSize -> Stream.of(0, 1, 2).map(kind ->
                        Arguments.of(vector, inputSize, outputSize, kind)))));
    }

    /// Accepts legal incomplete trees, preserves trailing input, and discards prior state on reset.
    @ParameterizedTest(name = "{0}; input={1}, output={2}, buffers={3}")
    @MethodSource("incompleteCases")
    void acceptsIncompleteCodes(Vector vector, int inputSize, int outputSize, int kind) throws Exception {
        assertArrayEquals(vector.expected(), reference(vector.compressed(), 16));
        try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                assertArrayEquals(vector.expected(), decode(decoder, vector.compressed(), inputSize,
                        outputSize, kind, true, 16));
                decoder.reset();
            }
        }
    }

    /// Rejects every byte-prefix truncation and recovers the same decoder with reset.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsTruncatedIncompleteCodes(int kind) throws Exception {
        try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
            for (Vector vector : vectors()) {
                for (int length = 0; length < vector.compressed().length; length++) {
                    byte[] truncated = Arrays.copyOf(vector.compressed(), length);
                    assertThrows(EOFException.class, () -> reference(truncated, 16));
                    assertThrows(EOFException.class, () -> decode(decoder, truncated, 1, 1, kind, false, 16));
                    decoder.reset();
                    assertArrayEquals(vector.expected(), decode(decoder, vector.compressed(), 2, 1,
                            kind, true, 16));
                    decoder.reset();
                }
            }
        }
    }

    /// Rejects a repeat that overruns the declared literal/length and distance alphabets.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsTooManyCodewordLengths(int kind) throws Exception {
        Bits bits = header(1, 0, 0, 1, 0, 0, 1);
        bits.fields(1, 1, 117, 7, 1, 1, 116, 7, 0, 1, 0, 1, 1, 1, 117, 7, 1, 1);
        byte[] compressed = bits.bytes();
        assertThrows(DataFormatException.class, () -> reference(compressed, 128));
        try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
            IOException failure = assertThrows(IOException.class,
                    () -> decode(decoder, compressed, 1, 1, kind, false, 128));
            assertFalse(failure instanceof EOFException, "Malformed lengths are not truncation");
        }
    }

    /// Exhausted input must not act as an infinite supply of zero-bit literal codewords.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void doesNotInventLiteralsAfterInputEnds(int kind) throws Exception {
        Bits bits = header(0, 0, 0, 1, 0, 0, 1);
        bits.fields(0, 1, 1, 1, 117, 7, 1, 1, 116, 7, 0, 1, 0, 1);
        byte[] compressed = bits.bytes();
        assertThrows(EOFException.class, () -> reference(compressed, 256));
        try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
            assertThrows(EOFException.class, () -> decode(decoder, compressed, 1, 1, kind, false, 256));
        }
    }

    /// Rejects the original issue-33 reproducer extracted from the verified release.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void rejectsOriginalSlowDecompressionReproducer(int inputSize) throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("arkivo.libdeflate.testDataDirectory")));
        assertTrue(Files.size(root.resolve("COPYING")) > 0);
        assertTrue(Files.size(root.resolve("UPSTREAM.properties")) > 0);
        String source = Files.readString(root.resolve("programs/test_slow_decompression.c"));
        var declaration = Pattern.compile("orig_repro\\[3962]\\s*=\\s*((?:\"(?:\\\\x[0-9a-fA-F]{2})*\"\\s*)+);")
                .matcher(source);
        assertTrue(declaration.find(), "Missing upstream reproducer");
        var octets = Pattern.compile("\\\\x([0-9a-fA-F]{2})").matcher(declaration.group(1));
        StringBuilder hex = new StringBuilder();
        while (octets.find()) {
            hex.append(octets.group(1));
        }
        byte[] compressed = HexFormat.of().parseHex(hex);
        assertEquals(3962, compressed.length);
        assertFalse(declaration.find(), "Ambiguous upstream reproducer");
        assertThrows(DataFormatException.class, () -> reference(compressed, 10000));
        for (int kind = 0; kind < 3; kind++) {
            int bufferKind = kind;
            try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
                IOException failure = assertThrows(IOException.class,
                        () -> decode(decoder, compressed, inputSize, 7, bufferKind, false, 10000));
                assertFalse(failure instanceof EOFException);
            }
        }
    }

    /// Traverses many empty blocks without producing output or treating a nonfinal sequence as complete.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlesManyEmptyBlocks(boolean dynamic) throws Exception {
        for (boolean finalBlock : new boolean[]{false, true}) {
            Bits bits = new Bits();
            for (int block = 0; block < 4096; block++) {
                int last = finalBlock && block == 4095 ? 1 : 0;
                if (dynamic) {
                    writeHeader(bits, last, 0, 0, 1, 0, 0, 1);
                    bits.fields(1, 1, 117, 7, 1, 1, 117, 7, 0, 1, 0, 1, 0, 1);
                } else {
                    bits.fields(last, 1, 1, 2, 0, 7);
                }
            }
            byte[] compressed = bits.bytes();
            if (finalBlock) {
                assertArrayEquals(new byte[0], reference(compressed, 1));
            } else {
                assertThrows(EOFException.class, () -> reference(compressed, 1));
            }
            for (int inputSize : new int[]{1, 7, 8192}) {
                try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
                    if (finalBlock) {
                        assertArrayEquals(new byte[0], decode(decoder, compressed, inputSize, 1, 1, true, 1));
                    } else {
                        assertThrows(EOFException.class,
                                () -> decode(decoder, compressed, inputSize, 1, 1, false, 1));
                    }
                }
            }
        }
    }

    /// Uses the upstream long-literal distribution with independent encoders and decoders.
    @ParameterizedTest
    @ValueSource(ints = {3, 6, 9})
    void roundTripsLongLiteralRuns(int level) throws Exception {
        byte[] content = new byte[2 * 250 * 251];
        int index = 0;
        for (int repeat = 0; repeat < 2; repeat++) {
            for (int stride = 1; stride < 251; stride++) {
                for (int multiple = 0; multiple < 251; multiple++) {
                    content[index++] = (byte) ((stride * multiple) % 251);
                }
            }
        }
        assertEquals(content.length, index);
        ByteBuffer encodedBuffer = DeflateCodec.DEFAULT.withCompressionLevel(level).compress(ByteBuffer.wrap(content));
        byte[] encoded = new byte[encodedBuffer.remaining()];
        encodedBuffer.get(encoded);
        assertArrayEquals(content, reference(encoded, content.length));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(level, true);
        try {
            deflater.setInput(content);
            deflater.finish();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                assertTrue(count > 0);
                compressed.write(buffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        try (var decoder = DeflateCodec.DEFAULT.newDecoder()) {
            for (int inputSize : new int[]{1, 127, 8192}) {
                assertArrayEquals(content, decode(decoder, compressed.toByteArray(), inputSize, 127,
                        2, true, content.length));
                decoder.reset();
            }
        }
    }

    /// Recreates the four bitstreams in test_incomplete_codes.c without using production Huffman tables.
    private static @Unmodifiable List<Vector> vectors() {
        Bits emptyDistance = header(1, 0, 0, 1, 3, 2, 3);
        emptyDistance.fields(0, 1, 54, 7, 7, 3, 1, 2, 0, 1, 89, 7, 0, 1, 78, 7,
                1, 2, 3, 3, 0, 1, 1, 2, 0, 1, 0, 1, 3, 2);
        Bits singletonLiteral = header(1, 0, 0, 1, 2, 0, 2);
        singletonLiteral.fields(0, 1, 117, 7, 0, 1, 117, 7, 3, 2, 1, 2, 0, 1);
        Bits singletonDistance = header(1, 1, 0, 1, 0, 2, 2);
        singletonDistance.fields(0, 1, 117, 7, 0, 1, 116, 7, 1, 2, 3, 2, 3, 2, 1, 2,
                0, 1, 3, 2, 0, 1, 1, 2);
        Bits nonzeroDistance = header(1, 1, 1, 2, 2, 2, 2);
        nonzeroDistance.fields(3, 2, 117, 7, 3, 2, 115, 7, 1, 2, 1, 2, 1, 2, 1, 2,
                0, 2, 2, 2, 0, 2, 2, 2, 3, 2, 0, 1, 1, 2);
        return List.of(new Vector("empty offset code", emptyDistance.bytes(), new byte[]{65, 66, 65, 65}),
                new Vector("singleton literal code", singletonLiteral.bytes(), new byte[0]),
                new Vector("singleton offset zero", singletonDistance.bytes(), new byte[]{-1, -1, -1, -1}),
                new Vector("singleton offset nonzero", nonzeroDistance.bytes(), new byte[]{-2, -1, -2, -1, -2}));
    }

    /// Creates a dynamic header whose precode uses only symbols 0, 1, 2, and 18.
    private static Bits header(int last, int literals, int distances, int repeat, int zero, int two, int one) {
        Bits bits = new Bits();
        writeHeader(bits, last, literals, distances, repeat, zero, two, one);
        return bits;
    }

    /// Writes counts and the eighteen explicit precode lengths in Deflate wire order.
    private static void writeHeader(Bits bits, int last, int literals, int distances,
                                    int repeat, int zero, int two, int one) {
        bits.fields(last, 1, 2, 2, literals, 5, distances, 5, 14, 4);
        for (int length : new int[]{0, 0, repeat, zero, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, two, 0, one}) {
            bits.fields(length, 3);
        }
    }

    /// Decodes through guarded, fragmented buffers with no initial output space and bounded progress.
    private static byte[] decode(CompressionDecoder decoder, byte[] compressed, int inputSize, int outputSize,
                                 int kind, boolean trailing, int maximumOutput) throws IOException {
        int size = compressed.length + (trailing ? 3 : 0);
        ByteBuffer input = kind == 1 ? ByteBuffer.allocateDirect(size + 2) : ByteBuffer.allocate(size + 2);
        input.put((byte) 0x5a).put(compressed);
        if (trailing) input.put(new byte[]{0x35, 0x46, 0x57});
        input.put((byte) 0x6b).position(1).limit(1 + Math.min(inputSize, size));
        if (kind == 2) input = input.asReadOnlyBuffer();
        input.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = kind == 1 ? ByteBuffer.allocateDirect(outputSize + 2) : ByteBuffer.allocate(outputSize + 2);
        output.order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int call = 0; call < 2 * (size + maximumOutput + 16); call++) {
            output.clear().put((byte) 0x12);
            output.put(output.capacity() - 1, (byte) 0x34);
            output.limit(call == 0 ? 1 : 1 + outputSize);
            int inputLimit = input.limit();
            int outputLimit = output.limit();
            CodecOutcome outcome = inputLimit == size + 1
                    ? decoder.finish(input, output) : decoder.decode(input, output);
            assertEquals(inputLimit, input.limit());
            assertEquals(outputLimit, output.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
            assertEquals(ByteOrder.LITTLE_ENDIAN, output.order());
            assertEquals((byte) 0x12, output.get(0));
            int written = output.position() - 1;
            output.clear();
            assertEquals((byte) 0x34, output.get(output.capacity() - 1));
            for (int i = 0; i < written; i++) result.write(output.get(i + 1));
            assertTrue(result.size() <= maximumOutput, "Unexpected expansion");
            if (outcome == CodecOutcome.FINISHED) {
                assertEquals(compressed.length + 1, input.position(), "Frame boundary");
                input.limit(input.capacity());
                assertEquals((byte) 0x5a, input.get(0));
                assertEquals((byte) 0x6b, input.get(input.capacity() - 1));
                if (trailing) {
                    assertEquals((byte) 0x35, input.get());
                    assertEquals((byte) 0x46, input.get());
                    assertEquals((byte) 0x57, input.get());
                }
                return result.toByteArray();
            }
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(inputLimit, input.position());
                assertTrue(inputLimit < size + 1, "finish must not request input");
                input.limit(Math.min(size + 1, inputLimit + inputSize));
            }
        }
        throw new AssertionError("Decoder exceeded the progress bound");
    }

    /// Uses the JDK's independent inflater and rejects truncation separately from malformed codes.
    private static byte[] reference(byte[] compressed, int maximumOutput) throws DataFormatException, EOFException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] output = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(output);
                result.write(output, 0, count);
                assertTrue(result.size() <= maximumOutput, "Reference exceeded the output bound");
                if (inflater.finished()) break;
                if (inflater.needsInput()) throw new EOFException("Truncated reference stream");
                assertTrue(count > 0, "Reference made no progress");
            }
            assertEquals(compressed.length, inflater.getBytesRead());
            return result.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Writes upstream LSB-first fields, independent of the production encoder.
    @NotNullByDefault
    private static final class Bits {
        /// Completed bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// Pending low-order bits.
        private int pending;
        /// Number of pending bits.
        private int count;

        /// Appends alternating field values and bit widths.
        private void fields(int... fields) {
            assertEquals(0, fields.length % 2);
            for (int i = 0; i < fields.length; i += 2) {
                for (int bit = 0; bit < fields[i + 1]; bit++) {
                    pending |= ((fields[i] >>> bit) & 1) << count++;
                    if (count == 8) {
                        output.write(pending);
                        pending = 0;
                        count = 0;
                    }
                }
            }
        }

        /// Pads the last byte with zero bits and returns the generated stream.
        private byte[] bytes() {
            if (count != 0) {
                output.write(pending);
                pending = 0;
                count = 0;
            }
            return output.toByteArray();
        }
    }
}
