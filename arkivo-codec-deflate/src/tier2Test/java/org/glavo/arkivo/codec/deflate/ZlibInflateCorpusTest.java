// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Test classifications adapted from zlib 1.3.1 infcover.c by Mark Adler.
// This Java adaptation differs from the C test runner; see NOTICE and LICENSES/Zlib.txt.

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises zlib's public-stream inflate vectors through fragmented caller-owned buffers.
@NotNullByDefault
final class ZlibInflateCorpusTest {
    /// Matches the literal vector calls in the pinned source, including adjacent C string literals.
    private static final Pattern VECTOR_CALL = Pattern.compile(
            "\\b(try|inf)\\(\\s*((?:\"[0-9a-fA-F\\s]*\"\\s*)+),\\s*\"([^\"]+)\"\\s*,\\s*([^;]*?)\\);",
            Pattern.DOTALL
    );

    /// Upstream API-parameter tests that do not describe invalid compressed streams.
    private static final @Unmodifiable Set<String> PARAMETER_TESTS = Set.of(
            "bad window size", "bad zlib window size"
    );

    /// Rejects unexpectedly large output before a malformed vector can exhaust the test process.
    private static final int MAXIMUM_OUTPUT_SIZE = 1 << 20;

    /// Checks that every literal upstream vector is accounted for, including the two parameter-only cases.
    @Test
    void accountsForEveryLiteralVector() throws IOException {
        List<Vector> vectors = vectors();
        assertEquals(47, vectors.size());
        assertEquals(2, vectors.stream().filter(v -> PARAMETER_TESTS.contains(v.name())).count());
        assertEquals(1, vectors.stream().filter(v -> v.status().equals("Z_NEED_DICT")).count());
        Path directory = corpusDirectory();
        for (String name : List.of("LICENSE", "zlib.h", "UPSTREAM.properties")) {
            assertTrue(Files.size(directory.resolve(name)) > 0, name);
        }
    }

    /// Compares valid output and stream boundaries, rejects malformed input, and repeats after reset.
    @ParameterizedTest(name = "{0}; input={1}, output={2}, buffers={3}")
    @MethodSource("cases")
    void decodesUpstreamVector(Vector vector, int inputSize, int outputSize, int bufferKind) throws Exception {
        try (CompressionDecoder decoder = vector.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                if (vector.status().equals("Z_NEED_DICT")) {
                    verifyDictionaryRequest(decoder, vector, inputSize, outputSize, bufferKind);
                } else if (vector.invalid()) {
                    assertThrows(IOException.class, () -> reference(vector));
                    IOException failure = assertThrows(IOException.class,
                            () -> decode(decoder, vector.bytes(), inputSize, outputSize, bufferKind, true));
                    if (vector.raw()) {
                        assertFalse(failure instanceof EOFException,
                                "Malformed Deflate must not be mistaken for incomplete input");
                    }
                } else {
                    Decoded expected = reference(vector);
                    if (vector.status().equals("Z_STREAM_END")) {
                        assertEquals(CodecOutcome.FINISHED, expected.outcome());
                    }
                    Decoded actual = decode(decoder, vector.bytes(), inputSize, outputSize, bufferKind, false);
                    assertArrayEquals(expected.bytes(), actual.bytes());
                    assertEquals(expected.consumed(), actual.consumed());
                    assertEquals(expected.outcome(), actual.outcome());
                    if (actual.outcome() == CodecOutcome.NEEDS_INPUT) {
                        assertThrows(EOFException.class,
                                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(32768)));
                    } else {
                        ByteBuffer untouched = ByteBuffer.wrap(new byte[]{12, 34, 56});
                        assertEquals(CodecOutcome.FINISHED, decoder.finish(untouched, ByteBuffer.allocate(0)));
                        assertEquals(0, untouched.position());
                    }
                }
                decoder.reset();
            }
        }
    }

    /// Adds a distinct suffix after each independently verified complete stream and preserves it on completion.
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeVectors")
    void preservesTrailingInput(Vector vector) throws IOException {
        Decoded expected = reference(vector);
        byte[] withTail = Arrays.copyOf(vector.bytes(), vector.bytes().length + 11);
        Arrays.fill(withTail, vector.bytes().length, withTail.length, (byte) 0xa5);
        for (int kind = 0; kind < 4; kind++) {
            try (CompressionDecoder decoder = vector.newDecoder()) {
                Decoded actual = decode(decoder, withTail, withTail.length, 259, kind, true);
                assertArrayEquals(expected.bytes(), actual.bytes());
                assertEquals(expected.consumed(), actual.consumed());
                assertEquals(CodecOutcome.FINISHED, actual.outcome());
            }
        }
    }

    /// Rejects every byte prefix before the independently established stream boundary, including empty input.
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeVectors")
    void rejectsEveryTruncatedPrefix(Vector vector) throws IOException {
        int boundary = reference(vector).consumed();
        for (int kind = 0; kind < 4; kind++) {
            int selectedKind = kind;
            try (CompressionDecoder decoder = vector.newDecoder()) {
                for (int length = 0; length < boundary; length++) {
                    byte[] prefix = Arrays.copyOf(vector.bytes(), length);
                    assertThrows(EOFException.class,
                            () -> decode(decoder, prefix, 2, 259, selectedKind, true),
                            "Prefix length: " + length);
                    decoder.reset();
                }
            }
        }
    }

    /// Returns all public stream cases with input fragmentation and output sizes around the 258-byte match boundary.
    private static Stream<Arguments> cases() throws IOException {
        return vectors().stream().filter(v -> !PARAMETER_TESTS.contains(v.name())).flatMap(vector ->
                Stream.of(new int[]{1, 1}, new int[]{2, 7}, new int[]{7, 258},
                                new int[]{Integer.MAX_VALUE, 257}, new int[]{Integer.MAX_VALUE, 259},
                                new int[]{Integer.MAX_VALUE, 32768})
                        .flatMap(sizes -> Stream.of(0, 1, 2, 3)
                                .map(kind -> Arguments.of(vector, sizes[0], sizes[1], kind)))
        );
    }

    /// Selects complete streams using an independent decoder, excluding upstream partial-stream vectors.
    private static Stream<Vector> completeVectors() throws IOException {
        List<Vector> complete = new ArrayList<>();
        for (Vector vector : vectors()) {
            if (!PARAMETER_TESTS.contains(vector.name()) && !vector.invalid()
                    && !vector.status().equals("Z_NEED_DICT")
                    && reference(vector).outcome() == CodecOutcome.FINISHED) {
                complete.add(vector);
            }
        }
        assertFalse(complete.isEmpty());
        return complete.stream();
    }

    /// Reads literal bytes directly from the verified upstream source instead of maintaining a second copy.
    private static @Unmodifiable List<Vector> vectors() throws IOException {
        String source = Files.readString(corpusDirectory().resolve("test/infcover.c"));
        List<Vector> result = new ArrayList<>();
        Matcher matcher = VECTOR_CALL.matcher(source);
        while (matcher.find()) {
            String hex = matcher.group(2).replace("\"", "").strip();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!hex.isEmpty()) {
                for (String token : hex.split("\\s+")) {
                    assertTrue(token.length() <= 2, "Unexpected upstream hex token: " + token);
                    bytes.write(Integer.parseInt(token, 16));
                }
            }
            String[] parameters = matcher.group(4).split("\\s*,\\s*");
            boolean rawCall = matcher.group(1).equals("try");
            int windowBits = rawCall ? (Integer.parseInt(parameters[0].strip()) < 0 ? 47 : -15)
                    : Integer.parseInt(parameters[1]);
            String status = rawCall ? (parameters[0].strip().equals("0") ? "Z_OK" : "Z_DATA_ERROR")
                    : parameters[3].strip();
            byte[] input = bytes.toByteArray();
            boolean gzip = windowBits > 15 && input.length >= 2
                    && input[0] == 0x1f && Byte.toUnsignedInt(input[1]) == 0x8b;
            result.add(new Vector(result.size() + 1, matcher.group(3), input, windowBits < 0, gzip, status));
        }
        return List.copyOf(result);
    }

    /// Returns the Gradle-prepared source directory; missing preparation is a test failure.
    private static Path corpusDirectory() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.zlib.testDataDirectory"),
                "Missing prepared zlib corpus directory"));
    }

    /// Checks the requested identifier and dictionary replacement without requiring a nonexistent body in the vector.
    private static void verifyDictionaryRequest(
            CompressionDecoder decoder, Vector vector, int inputSize, int outputSize, int bufferKind
    ) throws IOException {
        Decoded decoded = decode(decoder, vector.bytes(), inputSize, outputSize, bufferKind, false);
        assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoded.outcome());
        assertEquals(vector.bytes().length, decoded.consumed());
        assertEquals(0, decoded.bytes().length);
        assertInstanceOf(CompressionDecoder.DictionaryAware.class, decoder);
        @SuppressWarnings("unchecked")
        var dictionaryDecoder = (CompressionDecoder.DictionaryAware<ZlibDictionary, ZlibDictionaryRequest>) decoder;
        assertEquals(new ZlibDictionaryRequest(1L), dictionaryDecoder.dictionaryRequest());
        assertThrows(IllegalArgumentException.class,
                () -> dictionaryDecoder.provideDictionary(ZlibDictionary.of(new byte[]{1})));
        dictionaryDecoder.provideDictionary(ZlibDictionary.of(new byte[0]));
        assertEquals(CodecOutcome.NEEDS_INPUT,
                decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(7)));
        assertThrows(EOFException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(7)));
    }

    /// Decodes with fresh offset buffers, checks buffer state, and limits output and operation count.
    private static Decoded decode(
            CompressionDecoder decoder, byte @Unmodifiable [] bytes,
            int inputSize, int outputSize, int bufferKind, boolean finish
    ) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int consumed = 0;
        for (int calls = 0; calls < 2 * MAXIMUM_OUTPUT_SIZE; calls++) {
            int count = Math.min(inputSize, bytes.length - consumed);
            ByteBuffer storage = (bufferKind & 1) == 0
                    ? ByteBuffer.allocate(count + 6) : ByteBuffer.allocateDirect(count + 6);
            storage.position(3).put(bytes, consumed, count).limit(count + 3).position(3);
            ByteBuffer input = (bufferKind & 2) == 0 ? storage : storage.asReadOnlyBuffer();
            input.order(ByteOrder.LITTLE_ENDIAN);
            // An empty target must be resumable even when a header or an empty stream can be consumed.
            int capacity = calls == 0 ? 0 : outputSize;
            ByteBuffer output = (bufferKind & 1) == 0
                    ? ByteBuffer.allocate(capacity + 4) : ByteBuffer.allocateDirect(capacity + 4);
            output.put(0, (byte) 0x5a).put(capacity + 3, (byte) 0x6b);
            output.position(2).limit(capacity + 2).order(ByteOrder.LITTLE_ENDIAN);
            CodecOutcome outcome;
            try {
                outcome = finish && consumed + count == bytes.length
                        ? decoder.finish(input, output) : decoder.decode(input, output);
            } finally {
                assertEquals(count + 3, input.limit());
                assertEquals(capacity + 2, output.limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
                assertEquals(ByteOrder.LITTLE_ENDIAN, output.order());
                assertEquals(0x5a, output.get(0));
                assertEquals(0x6b, output.duplicate().clear().get(capacity + 3));
            }
            int produced = output.position() - 2;
            assertTrue(result.size() + produced <= MAXIMUM_OUTPUT_SIZE, "Upstream output limit exceeded");
            byte[] part = new byte[produced];
            output.flip().position(2);
            output.get(part);
            result.writeBytes(part);
            int progress = input.position() - 3;
            consumed += progress;
            // Neither source bytes already consumed nor the previous output may remain borrowed between calls.
            for (int index = 3; index < 3 + progress; index++) {
                storage.put(index, (byte) 0xcc);
            }
            output.clear();
            while (output.hasRemaining()) {
                output.put((byte) 0xdd);
            }
            if (outcome == CodecOutcome.FINISHED || outcome == CodecOutcome.NEEDS_DICTIONARY
                    || (outcome == CodecOutcome.NEEDS_INPUT && consumed == bytes.length)) {
                return new Decoded(result.toByteArray(), consumed, outcome);
            }
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(input.hasRemaining());
            }
            assertTrue(capacity == 0 || progress > 0 || produced > 0, "Decoder made no progress");
        }
        throw new AssertionError("Upstream vector exceeded the operation limit");
    }

    /// Obtains independent output, stream completion, and compressed-byte consumption from the JDK.
    private static Decoded reference(Vector vector) throws IOException {
        if (vector.gzip()) {
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(vector.bytes()))) {
                byte[] bytes = input.readNBytes(MAXIMUM_OUTPUT_SIZE + 1);
                assertTrue(bytes.length <= MAXIMUM_OUTPUT_SIZE);
                return new Decoded(bytes, vector.bytes().length, CodecOutcome.FINISHED);
            }
        }
        Inflater inflater = new Inflater(vector.raw());
        try {
            inflater.setInput(vector.bytes());
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            while (true) {
                int count = inflater.inflate(buffer);
                result.write(buffer, 0, count);
                assertTrue(result.size() <= MAXIMUM_OUTPUT_SIZE);
                if (inflater.finished() || inflater.needsDictionary() || (count == 0 && inflater.needsInput())) {
                    CodecOutcome outcome = inflater.finished() ? CodecOutcome.FINISHED
                            : inflater.needsDictionary() ? CodecOutcome.NEEDS_DICTIONARY : CodecOutcome.NEEDS_INPUT;
                    return new Decoded(result.toByteArray(), Math.toIntExact(inflater.getBytesRead()), outcome);
                }
                assertTrue(count > 0, "Reference inflater made no progress");
            }
        } catch (DataFormatException exception) {
            throw new IOException("Invalid upstream vector", exception);
        } finally {
            inflater.end();
        }
    }

    /// One upstream call and its stream-level interpretation.
    ///
    /// @param index one-based source order, distinguishing duplicate upstream descriptions
    /// @param name upstream case description
    /// @param bytes decoded hexadecimal input, never modified
    /// @param raw whether the input has no container header
    /// @param gzip whether the input carries a gzip header
    /// @param status upstream expected result code
    @NotNullByDefault
    private record Vector(int index, String name, byte @Unmodifiable [] bytes,
                          boolean raw, boolean gzip, String status) {
        /// Creates the matching public codec's decoder.
        CompressionDecoder newDecoder() throws IOException {
            return raw ? DeflateCodec.DEFAULT.newDecoder()
                    : gzip ? GzipCodec.DEFAULT.newDecoder() : ZlibCodec.DEFAULT.newDecoder();
        }

        /// Returns whether upstream expects a compressed-data error.
        boolean invalid() {
            return status.equals("Z_DATA_ERROR");
        }

        /// Identifies a parameterized case using its upstream source order and description.
        @Override
        public String toString() {
            return index + ": " + name;
        }
    }

    /// An independently observable decoding result.
    ///
    /// @param bytes decoded output
    /// @param consumed compressed bytes consumed, excluding trailing input
    /// @param outcome stream completion or the resource needed to continue
    @NotNullByDefault
    private record Decoded(byte @Unmodifiable [] bytes, int consumed, CodecOutcome outcome) {
    }
}
