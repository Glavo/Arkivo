// Copyright (c) 2026 Glavo
// Scenarios adapted from miniz's OSS-Fuzz harnesses; see NOTICE.
// SPDX-License-Identifier: MPL-2.0 AND Apache-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises miniz's streaming scenarios with independent JDK oracles and disposable caller buffers.
@NotNullByDefault
final class MinizStreamingRegressionTest {
    /// Downloaded upstream harnesses also used as deterministic, nontrivial compression inputs.
    private static final @Unmodifiable List<String> SOURCES = List.of(
            "small_fuzzer.c", "large_fuzzer.c", "flush_fuzzer.c", "compress_fuzzer.c");

    /// Sentinel bytes outside caller buffer windows.
    private static final byte GUARD = 0x5a;

    /// Combines raw and wrapped Deflate, upstream compression levels, and three buffer layouts.
    private static Stream<Arguments> configurations() {
        return Stream.of(false, true).flatMap(raw -> Stream.of(1, 3, 6, 7).flatMap(level ->
                Stream.of(0, 1, 2).map(kind -> Arguments.of(raw, level, kind))));
    }

    /// Adds the empty input boundary to the four retained upstream source files.
    private static Stream<Arguments> smallCases() {
        return configurations().flatMap(configuration -> Stream.concat(Stream.of(""), SOURCES.stream())
                .map(file -> Arguments.of(configuration.get()[0], configuration.get()[1], configuration.get()[2], file)));
    }

    /// Crosses history-window and block-size boundaries in the compress-output-as-input scenario.
    private static Stream<Arguments> feedbackCases() {
        return configurations().flatMap(configuration -> Stream.of(32767, 32768, 65537)
                .map(size -> Arguments.of(configuration.get()[0], configuration.get()[1], configuration.get()[2], size)));
    }

    /// Keeps provenance and every selected upstream harness available in offline test runs.
    @Test
    void retainsReferenceSources() throws IOException {
        assertTrue(Files.size(root().resolve("LICENSE")) > 0);
        assertTrue(Files.size(root().resolve("UPSTREAM.properties")) > 0);
        assertTrue(Files.readString(root().resolve("tests/ossfuzz.sh")).contains("Copyright 2020 Google Inc."));
        for (String file : SOURCES) {
            assertTrue(Files.readString(root().resolve("tests/" + file)).contains("LLVMFuzzerTestOneInput"));
        }
    }

    /// Finishes and verifies both directions using single-byte buffers, then resets both engines and repeats.
    @ParameterizedTest(name = "raw={0}, level={1}, buffers={2}, source={3}")
    @MethodSource("smallCases")
    void singleByteBuffers(boolean raw, int level, int kind, String file) throws Exception {
        byte[] expected = file.isEmpty() ? new byte[0] : Files.readAllBytes(root().resolve("tests/" + file));
        var codec = codec(raw, level);
        try (var encoder = codec.newEncoder(); var decoder = codec.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                encode(encoder, expected, 1, 1, kind, encoded);
                boundary(encoder, true, 1, kind, encoded);
                assertArrayEquals(expected, referenceDecode(encoded.toByteArray(), raw, expected.length));
                assertArrayEquals(expected, decode(decoder, referenceEncode(expected, raw, level), 1, 1, kind, expected.length));
                encoder.reset();
                decoder.reset();
            }
        }
    }

    /// Checks each sync-flushed prefix before supplying more input, including empty and repeated boundaries.
    @ParameterizedTest(name = "raw={0}, level={1}, buffers={2}")
    @MethodSource("configurations")
    void verifiesEveryFlushPrefix(boolean raw, int level, int kind) throws Exception {
        byte[] repeated = new byte[32769];
        Arrays.fill(repeated, (byte) 'a');
        byte[] random = new byte[1027];
        new Random(7281).nextBytes(random);
        Inflater reference = new Inflater(raw);
        try (var encoder = codec(raw, level).newEncoder()) {
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int submitted = 0;
            for (byte[] part : new byte[][]{new byte[0], {1, 2, 3}, repeated, new byte[0], random, repeated}) {
                expected.writeBytes(part);
                encode(encoder, part, 257, 7, kind, encoded);
                boundary(encoder, false, 1, kind, encoded);
                byte[] bytes = encoded.toByteArray();
                reference.setInput(bytes, submitted, bytes.length - submitted);
                inflateAvailable(reference, decoded, expected.size());
                assertFalse(reference.finished());
                assertEquals(0, reference.getRemaining());
                assertArrayEquals(expected.toByteArray(), decoded.toByteArray());
                submitted = bytes.length;
            }
            boundary(encoder, true, 1, kind, encoded);
            byte[] bytes = encoded.toByteArray();
            reference.setInput(bytes, submitted, bytes.length - submitted);
            inflateAvailable(reference, decoded, expected.size());
            assertTrue(reference.finished());
            assertEquals(0, reference.getRemaining());
            assertArrayEquals(expected.toByteArray(), decoded.toByteArray());
            try (var decoder = codec(raw, level).newDecoder()) {
                assertArrayEquals(expected.toByteArray(), decode(decoder, bytes, 7, 257, kind, expected.size()));
            }
        } finally {
            reference.end();
        }
    }

    /// Encodes a zero-filled region followed by its already-emitted compressed prefix, as in large_fuzzer.c.
    @ParameterizedTest(name = "raw={0}, level={1}, buffers={2}, size={3}")
    @MethodSource("feedbackCases")
    void compressesOwnOutputPrefix(boolean raw, int level, int kind, int size) throws Exception {
        byte[] first = new byte[size];
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var encoder = codec(raw, level).newEncoder()) {
            encode(encoder, first, size, 257, kind, encoded);
            // A sync boundary ensures there is a nonempty prefix even if the encoder buffered all source bytes.
            boundary(encoder, false, 7, kind, encoded);
            byte[] prefix = encoded.toByteArray();
            assertTrue(prefix.length > 0);
            encode(encoder, prefix, 1, 7, kind, encoded);
            boundary(encoder, true, 1, kind, encoded);
            byte[] expected = Arrays.copyOf(first, first.length + prefix.length);
            System.arraycopy(prefix, 0, expected, first.length, prefix.length);
            assertArrayEquals(expected, referenceDecode(encoded.toByteArray(), raw, expected.length));
            try (var decoder = codec(raw, level).newDecoder()) {
                assertArrayEquals(expected, decode(decoder, encoded.toByteArray(), 1, 257, kind, expected.length));
            }
        }
    }

    /// Discards a partly emitted trailer or flush marker before reusing the same encoder.
    @ParameterizedTest(name = "raw={0}, level={1}, buffers={2}")
    @MethodSource("configurations")
    void resetsPendingBoundary(boolean raw, int level, int kind) throws Exception {
        byte[] input = new byte[4097];
        new Random(159).nextBytes(input);
        try (var encoder = codec(raw, level).newEncoder()) {
            for (boolean finish : new boolean[]{false, true}) {
                encode(encoder, input, 257, 7, kind, new ByteArrayOutputStream());
                ByteBuffer target = buffer(1, kind);
                assertEquals(CodecOutcome.NEEDS_OUTPUT, finish ? encoder.finish(target) : encoder.flush(target));
                encoder.reset();
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                encode(encoder, new byte[]{9, 8, 7}, 1, 1, kind, encoded);
                boundary(encoder, true, 1, kind, encoded);
                assertArrayEquals(new byte[]{9, 8, 7}, referenceDecode(encoded.toByteArray(), raw, 3));
                encoder.reset();
            }
        }
    }

    /// Creates a bounded codec with the upstream harness's selected compression level.
    private static CompressionCodec.Flushable<?> codec(boolean raw, int level) {
        return raw ? DeflateCodec.DEFAULT.withCompressionLevel(level).withMaximumOutputSize(1 << 20)
                : ZlibCodec.DEFAULT.withCompressionLevel(level).withMaximumOutputSize(1 << 20);
    }

    /// Feeds fragments and poisons consumed source bytes and drained output before the next operation.
    private static void encode(CompressionEncoder encoder, byte[] bytes, int inputSize, int outputSize,
                               int kind, ByteArrayOutputStream encoded) throws IOException {
        for (int offset = 0; offset < bytes.length; offset += inputSize) {
            int length = Math.min(inputSize, bytes.length - offset);
            ByteBuffer backing = buffer(length, kind);
            backing.put(bytes, offset, length).position(3);
            ByteBuffer source = (kind == 2 ? backing.asReadOnlyBuffer() : backing).order(ByteOrder.LITTLE_ENDIAN);
            int calls = 0;
            while (true) {
                assertTrue(++calls <= 4 * length + 131072, "Encoder failed to yield");
                ByteBuffer target = buffer(outputSize, kind);
                int before = source.position();
                CodecOutcome result = encoder.encode(source, target);
                checkSource(source, backing, before, length);
                int produced = drain(target, encoded);
                if (result == CodecOutcome.NEEDS_INPUT) {
                    assertEquals(source.limit(), source.position());
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
                assertEquals(outputSize, produced);
            }
        }
    }

    /// Completes a boundary with fresh targets and checks empty-target and repeated-finish behavior.
    private static void boundary(CompressionEncoder.Flushable encoder, boolean finish, int outputSize,
                                 int kind, ByteArrayOutputStream encoded) throws IOException {
        for (int calls = 0; ; calls++) {
            assertTrue(calls < 131072, "Boundary failed to terminate");
            ByteBuffer target = buffer(calls == 0 ? 0 : outputSize, kind);
            CodecOutcome result = finish ? encoder.finish(target) : encoder.flush(target);
            int produced = drain(target, encoded);
            if (result == (finish ? CodecOutcome.FINISHED : CodecOutcome.FLUSHED)) break;
            assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
            assertEquals(calls == 0 ? 0 : outputSize, produced);
        }
        if (finish) {
            ByteBuffer target = buffer(1, kind);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(target));
            assertEquals(0, drain(target, encoded));
        }
    }

    /// Checks exact input consumption, finite progress, and output limits while poisoning retired buffers.
    private static byte[] decode(CompressionDecoder decoder, byte[] bytes, int inputSize, int outputSize,
                                 int kind, int expectedSize) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        int calls = 0;
        while (true) {
            int length = Math.min(inputSize, bytes.length - offset);
            ByteBuffer backing = buffer(length, kind);
            backing.put(bytes, offset, length).position(3);
            ByteBuffer source = (kind == 2 ? backing.asReadOnlyBuffer() : backing).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                assertTrue(++calls < 4L * (bytes.length + expectedSize) + 1024, "Decoder failed to terminate");
                ByteBuffer target = buffer(outputSize, kind);
                int before = source.position();
                CodecOutcome result = offset + length == bytes.length
                        ? decoder.finish(source, target) : decoder.decode(source, target);
                checkSource(source, backing, before, length);
                int produced = drain(target, decoded);
                assertTrue(decoded.size() <= expectedSize, "Decoder exceeded the expected output size");
                if (result == CodecOutcome.FINISHED) {
                    assertEquals(bytes.length, offset + source.position() - 3);
                    assertEquals(expectedSize, decoded.size());
                    return decoded.toByteArray();
                }
                if (result == CodecOutcome.NEEDS_INPUT) {
                    assertEquals(source.limit(), source.position());
                    assertTrue(offset + length < bytes.length, "Decoder requested input after the final fragment");
                    offset += length;
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
                assertEquals(outputSize, produced);
            }
        }
    }

    /// Allocates a guarded window with a nonzero position and a deliberately non-default byte order.
    private static ByteBuffer buffer(int size, int kind) {
        ByteBuffer buffer = kind == 1 ? ByteBuffer.allocateDirect(size + 6) : ByteBuffer.allocate(size + 6);
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, GUARD);
        return buffer.position(3).limit(3 + size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Checks source invariants before overwriting only the bytes already reported as consumed.
    private static void checkSource(ByteBuffer source, ByteBuffer backing, int before, int length) {
        assertTrue(source.position() >= before && source.position() <= 3 + length);
        assertEquals(3 + length, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        for (int i = before; i < source.position(); i++) backing.put(i, (byte) 0x6d);
    }

    /// Copies produced bytes, checks untouched guard regions, and overwrites the entire retired target.
    private static int drain(ByteBuffer target, ByteArrayOutputStream output) {
        assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
        assertEquals(target.capacity() - 3, target.limit());
        int length = target.position() - 3;
        assertTrue(length >= 0);
        ByteBuffer all = target.duplicate().clear();
        for (int i = 0; i < 3; i++) {
            assertEquals(GUARD, all.get(i));
            assertEquals(GUARD, all.get(all.capacity() - 1 - i));
        }
        for (int i = 3; i < target.position(); i++) output.write(target.get(i));
        for (int i = 0; i < all.capacity(); i++) all.put(i, (byte) 0x6d);
        return length;
    }

    /// Generates the independently encoded stream for the decoder half of the small-buffer matrix.
    private static byte[] referenceEncode(byte[] bytes, boolean raw, int level) {
        Deflater encoder = new Deflater(level, raw);
        try {
            encoder.setInput(bytes);
            encoder.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[257];
            while (!encoder.finished()) {
                int count = encoder.deflate(buffer);
                assertTrue(count > 0 || encoder.finished());
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            encoder.end();
        }
    }

    /// Requires a complete independently decoded stream, not merely the expected number of plaintext bytes.
    private static byte[] referenceDecode(byte[] bytes, boolean raw, int expectedSize) throws DataFormatException {
        Inflater decoder = new Inflater(raw);
        try {
            decoder.setInput(bytes);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            inflateAvailable(decoder, decoded, expectedSize);
            assertTrue(decoder.finished());
            assertEquals(0, decoder.getRemaining());
            assertEquals(expectedSize, decoded.size());
            return decoded.toByteArray();
        } finally {
            decoder.end();
        }
    }

    /// Drains an independently decoded prefix without treating a flush boundary as end-of-stream.
    private static void inflateAvailable(Inflater decoder, ByteArrayOutputStream decoded, int maximum)
            throws DataFormatException {
        byte[] buffer = new byte[257];
        while (true) {
            int count = decoder.inflate(buffer);
            decoded.write(buffer, 0, count);
            assertTrue(decoded.size() <= maximum);
            if (decoder.finished()) return;
            if (count == 0) {
                assertTrue(decoder.needsInput());
                return;
            }
        }
    }

    /// Resolves only the pinned, verified source extraction.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.miniz.testDataDirectory")));
    }
}
