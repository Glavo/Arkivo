// Copyright (c) 2026 Glavo
// Test expectations adapted from Go's compress/zlib tests; see LICENSES/Go-BSD-3-Clause.txt.
// SPDX-License-Identifier: MPL-2.0 AND BSD-3-Clause

package org.glavo.arkivo.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays Go's zlib reader vectors with fragmented buffers and explicit stream-boundary checks.
@NotNullByDefault
final class GoZlibCorpusTest {
    /// Matches the five literal fields of each vector in the pinned Go source.
    private static final Pattern VECTOR = Pattern.compile(
            "\\{\\s*\"([^\"]+)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*"
                    + "\\[\\]byte\\{([^}]*)},\\s*(nil|\\[\\]byte\\{[^}]*}),\\s*([\\w.]+),\\s*}",
            Pattern.DOTALL);

    /// Rejects accidental expansion before a malformed fixture can exhaust memory.
    private static final int OUTPUT_LIMIT = 1024;

    /// One source vector whose byte arrays are never modified by the test driver.
    ///
    /// @param name upstream description
    /// @param raw expected plaintext for successful decoding
    /// @param compressed complete upstream input, including any trailing bytes
    /// @param dictionary preset dictionary, or null when none was supplied
    /// @param error upstream error name, or "nil" for success
    @NotNullByDefault
    private record Vector(String name, byte @Unmodifiable [] raw, byte @Unmodifiable [] compressed,
                          byte @Nullable @Unmodifiable [] dictionary, String error) {
    }

    /// Reads only the literal table; a source-layout change must fail the inventory checks.
    private static @Unmodifiable List<Vector> vectors() throws IOException {
        Path source = Path.of(Objects.requireNonNull(System.getProperty("arkivo.go.testDataDirectory")))
                .resolve("src/compress/zlib/reader_test.go");
        var matcher = VECTOR.matcher(Files.readString(source));
        List<Vector> vectors = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group(2).replace("\\n", "\n");
            assertFalse(raw.contains("\\"), "unrecognized Go string escape");
            String dictionary = matcher.group(4);
            vectors.add(new Vector(matcher.group(1), raw.getBytes(StandardCharsets.UTF_8),
                    bytes(matcher.group(3)), dictionary.equals("nil") ? null
                    : bytes(dictionary.substring(dictionary.indexOf('{') + 1, dictionary.length() - 1)),
                    matcher.group(5)));
        }
        assertEquals(14, vectors.size(), "upstream vector inventory changed");
        assertEquals(14, vectors.stream().map(Vector::name).distinct().count());
        return List.copyOf(vectors);
    }

    /// Converts Go hexadecimal byte literals without accepting other expressions.
    private static byte[] bytes(String literal) {
        assertTrue(literal.matches("\\s*(?:0x[0-9a-f]{2}\\s*,?\\s*)*"), literal);
        return HexFormat.of().parseHex(literal.replace("0x", "").replaceAll("[\\s,]", ""));
    }

    /// Exercises every vector with both one-byte and larger output buffers.
    private static Stream<Arguments> cases() throws IOException {
        return vectors().stream().flatMap(vector -> Stream.of(1, 3, 64).flatMap(inputSize ->
                Stream.of(1, 32).map(outputSize -> Arguments.of(vector.name(), vector, inputSize, outputSize))));
    }

    /// Requires every success, truncation, header, checksum, and dictionary case to remain represented.
    @Test
    void accountsForEveryVector() throws IOException {
        var vectors = vectors();
        assertEquals(4, vectors.stream().filter(vector -> vector.error().equals("nil")).count());
        assertEquals(6, vectors.stream().filter(vector -> vector.error().equals("io.ErrUnexpectedEOF")).count());
        assertEquals(2, vectors.stream().filter(vector -> vector.error().equals("ErrHeader")).count());
        assertEquals(1, vectors.stream().filter(vector -> vector.error().equals("ErrChecksum")).count());
        assertEquals(1, vectors.stream().filter(vector -> vector.error().equals("ErrDictionary")).count());
    }

    /// Checks error classification, successful output, trailing bytes, and reuse after success or failure.
    @ParameterizedTest(name = "{0}, input={2}, output={3}")
    @MethodSource("cases")
    void decodesUpstreamVector(String name, Vector vector, int inputSize, int outputSize) throws Exception {
        assertEquals(name, vector.name());
        ZlibCodec codec = ZlibCodec.DEFAULT.withMaximumOutputSize(OUTPUT_LIMIT);
        if (vector.dictionary() != null) codec = codec.withDictionary(ZlibDictionary.of(vector.dictionary()));
        try (var decoder = codec.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                if (vector.error().equals("nil")) {
                    assertArrayEquals(vector.raw(), decode(decoder, vector, inputSize, outputSize));
                    verifyReference(vector);
                } else {
                    IOException failure = assertThrows(IOException.class,
                            () -> decode(decoder, vector, inputSize, outputSize));
                    assertFalse(failure instanceof DecompressionLimitException);
                    assertEquals(vector.error().equals("io.ErrUnexpectedEOF"), failure instanceof EOFException);
                }
                decoder.reset();
            }
        }
    }

    /// Negotiates the upstream dictionary after rejecting a mismatching dictionary without losing the request.
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void suppliesDictionaryAfterRequest(int inputSize) throws IOException {
        Vector vector = vectors().stream().filter(value -> value.name().equals("dictionary")).toList().get(0);
        Vector wrong = vectors().stream().filter(value -> value.name().equals("wrong dictionary")).toList().get(0);
        @UnmodifiableView ByteBuffer source = ByteBuffer.wrap(vector.compressed()).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(OUTPUT_LIMIT);
        try (var decoder = ZlibCodec.DEFAULT.withMaximumOutputSize(OUTPUT_LIMIT).newDecoder()) {
            CodecOutcome outcome;
            do {
                int limit = Math.min(source.capacity(), source.position() + inputSize);
                source.limit(limit);
                outcome = decoder.decode(source, target);
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_DICTIONARY);
            } while (outcome == CodecOutcome.NEEDS_INPUT && source.position() < source.capacity());
            assertEquals(CodecOutcome.NEEDS_DICTIONARY, outcome);
            assertEquals(6, source.position());
            assertEquals(0, target.position());
            ZlibDictionary dictionary = ZlibDictionary.of(Objects.requireNonNull(vector.dictionary()));
            assertEquals(dictionary.adler32(), decoder.dictionaryRequest().adler32());
            assertThrows(IllegalArgumentException.class, () -> decoder.provideDictionary(
                    ZlibDictionary.of(Objects.requireNonNull(wrong.dictionary()))));
            assertEquals(dictionary.adler32(), decoder.dictionaryRequest().adler32());
            decoder.provideDictionary(dictionary);
            source.limit(source.capacity());
            assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
            assertEquals(0, source.remaining());
            assertArrayEquals(vector.raw(), Arrays.copyOf(target.array(), target.position()));
        }
    }

    /// Offers fresh slices and requires completion before any fixture suffix or caller trailer is consumed.
    private static byte[] decode(CompressionDecoder decoder, Vector vector, int inputSize, int outputSize)
            throws IOException {
        byte[] compressed = vector.compressed();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        @UnmodifiableView ByteBuffer source = ByteBuffer.allocate(0).asReadOnlyBuffer();
        int supplied = 0;
        int consumed = 0;
        for (int calls = 0; calls < OUTPUT_LIMIT + compressed.length + 16; calls++) {
            if (!source.hasRemaining() && supplied < compressed.length) {
                int count = Math.min(inputSize, compressed.length - supplied);
                ByteBuffer storage = inputSize == 1 ? ByteBuffer.allocate(count + 3)
                        : ByteBuffer.allocateDirect(count + 3);
                storage.position(2).put(compressed, supplied, count);
                supplied += count;
                if (supplied == compressed.length && vector.error().equals("nil")) storage.put((byte) 0xa5);
                source = storage.flip().position(2).asReadOnlyBuffer();
            }
            ByteBuffer target = outputSize == 1 ? ByteBuffer.allocate(outputSize + 2)
                    : ByteBuffer.allocateDirect(outputSize + 2);
            target.put(0, (byte) 0x5a).put(outputSize + 1, (byte) 0x5a).position(1).limit(outputSize + 1);
            int before = source.position();
            int limit = source.limit();
            CodecOutcome outcome = supplied == compressed.length ? decoder.finish(source, target)
                    : decoder.decode(source, target);
            consumed += source.position() - before;
            assertEquals(limit, source.limit());
            assertEquals(outputSize + 1, target.limit());
            int count = target.position() - 1;
            target.flip().position(1);
            while (target.hasRemaining()) output.write(target.get());
            target.clear();
            assertEquals((byte) 0x5a, target.get(0));
            assertEquals((byte) 0x5a, target.get(outputSize + 1));
            assertTrue(output.size() <= OUTPUT_LIMIT);
            if (outcome == CodecOutcome.FINISHED) {
                int expectedConsumed = vector.name().equals("excess data is silently ignored") ? 8 : compressed.length;
                assertEquals(expectedConsumed, consumed);
                for (int index = consumed; index < supplied; index++) assertEquals(compressed[index], source.get());
                if (supplied == compressed.length) assertEquals((byte) 0xa5, source.get());
                assertFalse(source.hasRemaining());
                return output.toByteArray();
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
                assertTrue(supplied < compressed.length, "finish requested more input");
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(outputSize, count);
            }
        }
        throw new AssertionError("decoder did not terminate");
    }

    /// Independently verifies successful vectors and the unconsumed suffix with JDK zlib.
    private static void verifyReference(Vector vector) throws Exception {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(vector.compressed());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[OUTPUT_LIMIT];
            for (int calls = 0; !inflater.finished() && calls < 32; calls++) {
                int count = inflater.inflate(buffer);
                output.write(buffer, 0, count);
                if (inflater.needsDictionary()) inflater.setDictionary(Objects.requireNonNull(vector.dictionary()));
                else assertTrue(count > 0 || inflater.finished());
                assertTrue(output.size() <= OUTPUT_LIMIT);
            }
            assertTrue(inflater.finished());
            assertArrayEquals(vector.raw(), output.toByteArray());
            assertEquals(vector.name().equals("excess data is silently ignored") ? 3 : 0, inflater.getRemaining());
        } finally {
            inflater.end();
        }
    }
}
