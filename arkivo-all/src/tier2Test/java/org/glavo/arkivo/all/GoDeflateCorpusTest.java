// Copyright (c) 2026 Glavo
// Test expectations adapted from Go's compress tests; see LICENSES/Go-BSD-3-Clause.txt.
// SPDX-License-Identifier: MPL-2.0 AND BSD-3-Clause

package org.glavo.arkivo.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays Go's Huffman block output and malformed gzip fixture through bounded incremental decoders.
@NotNullByDefault
final class GoDeflateCorpusTest {
    /// Distinct caller-owned bytes that must remain unread after a final Deflate block.
    private static final byte @Unmodifiable [] TRAILER = {(byte) 0xa5, 0x5a, 0x12, 0x34};

    /// Bounds malformed-input expansion independently of any decoder allocation policy.
    private static final int OUTPUT_LIMIT = 1 << 20;

    /// Enumerates every upstream block representation with tiny, intermediate, and bulk input slices.
    private static Stream<Arguments> blocks() throws IOException {
        try (var files = Files.list(resource("flate/testdata"))) {
            @Unmodifiable List<Path> blocks = files.filter(path -> !path.getFileName().toString().endsWith(".in"))
                    .sorted().toList();
            assertEquals(43, blocks.size());
            return blocks.stream().flatMap(path -> Stream.of(1, 31, 4096)
                    .map(chunk -> Arguments.of(path.getFileName().toString(), chunk)));
        }
    }

    /// Distinguishes nonfinal blocks from complete streams and repeats complete decoding after reset.
    @ParameterizedTest(name = "{0}, input={1}")
    @MethodSource("blocks")
    void decodesEveryBlockAndPreservesBoundaries(String name, int inputSize) throws IOException, DataFormatException {
        byte[] nonfinal = Files.readAllBytes(resource("flate/testdata/" + name));
        assertEquals(0, nonfinal[0] & 1, "upstream writers produce one nonfinal block");
        String stem = name.substring(0, name.indexOf('.'));
        byte[] expected = stem.equals("null-long-match") ? new byte[3 * 65_535]
                : Files.readAllBytes(resource("flate/testdata/" + stem + ".in"));
        byte[] complete = nonfinal.clone();
        complete[0] |= 1;
        assertArrayEquals(expected, inflate(complete));
        int outputSize = inputSize == 1 ? 7 : inputSize == 31 ? 257 : 32_768;
        try (var decoder = DeflateCodec.DEFAULT.withMaximumOutputSize(expected.length).newDecoder()) {
            assertArrayEquals(expected, decode(decoder, nonfinal, inputSize, outputSize, false));
            assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1)));
            for (int repeat = 0; repeat < 2; repeat++) {
                decoder.reset();
                assertArrayEquals(expected, decode(decoder, complete, inputSize, outputSize, true));
            }
        }
    }

    /// Rejects the Go issue 6550 fixture instead of hanging or expanding without a caller-enforced bound.
    @ParameterizedTest
    @ValueSource(ints = {1, 31, 4096})
    void rejectsMalformedGzipFixture(int inputSize) throws IOException {
        byte[] compressed = Base64.getMimeDecoder().decode(
                Files.readAllBytes(resource("gzip/testdata/issue6550.gz.base64")));
        try (var reference = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertThrows(IOException.class, () -> reference.readNBytes(OUTPUT_LIMIT + 1));
        }
        try (var decoder = GzipCodec.DEFAULT.withMaximumOutputSize(OUTPUT_LIMIT).newDecoder()) {
            IOException failure = assertThrows(IOException.class,
                    () -> decode(decoder, compressed, inputSize, 257, true));
            assertFalse(failure instanceof DecompressionLimitException,
                    "Malformed input must fail validation, not merely exceed the safety bound");
        }
    }

    /// Checks that the pinned corpus includes all nine plaintexts and the preserved upstream licensing information.
    @Test
    void accountsForSourceCorpus() throws IOException {
        try (var files = Files.list(resource("flate/testdata"))) {
            assertEquals(52, files.count());
        }
        Path root = resource("").getParent().getParent();
        for (String name : List.of("LICENSE", "UPSTREAM.properties")) {
            assertTrue(Files.size(root.resolve(name)) > 0, name);
        }
        assertTrue(Files.size(resource("flate/huffman_bit_writer_test.go")) > 0);
    }

    /// Supplies fresh read-only buffers and checks output bounds, finality, and trailing source bytes.
    private static byte[] decode(CompressionDecoder decoder, byte[] compressed, int inputSize, int outputSize,
                                 boolean complete) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        @UnmodifiableView ByteBuffer source = ByteBuffer.allocate(0).asReadOnlyBuffer();
        boolean direct = inputSize != 1;
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputSize + 2) : ByteBuffer.allocate(outputSize + 2);
        int supplied = 0;
        int consumed = 0;
        // Even a decoder that reports progress forever must not hang a deterministic regression run.
        int calls = 0;
        while (true) {
            assertTrue(++calls <= compressed.length + OUTPUT_LIMIT + 16, "decoder did not terminate");
            if (!source.hasRemaining() && supplied < compressed.length) {
                int length = Math.min(inputSize, compressed.length - supplied);
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(length + 7) : ByteBuffer.allocate(length + 7);
                storage.position(3).put(compressed, supplied, length);
                supplied += length;
                if (complete && supplied == compressed.length) storage.put(TRAILER);
                source = storage.flip().position(3).asReadOnlyBuffer();
            }
            target.clear().put(0, (byte) 0x6d).put(outputSize + 1, (byte) 0x6d);
            target.position(1).limit(outputSize + 1);
            int before = source.position();
            int limit = source.limit();
            CodecOutcome outcome = complete && supplied == compressed.length
                    ? decoder.finish(source, target) : decoder.decode(source, target);
            consumed += source.position() - before;
            assertEquals(limit, source.limit());
            assertEquals(outputSize + 1, target.limit());
            int count = target.position() - 1;
            byte[] decoded = new byte[count];
            target.flip().position(1).get(decoded);
            output.writeBytes(decoded);
            assertTrue(output.size() <= OUTPUT_LIMIT, "unexpected expansion");
            target.clear();
            assertEquals((byte) 0x6d, target.get(0));
            assertEquals((byte) 0x6d, target.get(outputSize + 1));
            if (outcome == CodecOutcome.FINISHED) {
                assertTrue(complete, "nonfinal block reported completion");
                assertEquals(compressed.length, consumed);
                byte[] trailing = new byte[source.remaining()];
                source.get(trailing);
                assertArrayEquals(TRAILER, trailing);
                return output.toByteArray();
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(0, source.remaining());
                if (supplied == compressed.length) {
                    assertFalse(complete, "complete stream requested additional input");
                    return output.toByteArray();
                }
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(outputSize, count);
            }
        }
    }

    /// Decodes a complete raw block with JDK zlib while checking both output size and source consumption.
    private static byte[] inflate(byte[] compressed) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            byte[] decoded = new byte[OUTPUT_LIMIT + 1];
            int size = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(decoded, size, decoded.length - size);
                size += count;
                assertTrue(size <= OUTPUT_LIMIT);
                assertTrue(count > 0 || inflater.finished(), "reference inflater made no progress");
            }
            assertEquals(0, inflater.getRemaining());
            return Arrays.copyOf(decoded, size);
        } finally {
            inflater.end();
        }
    }

    /// Resolves compression fixtures from the existing verified Go source download.
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.go.testDataDirectory")))
                .resolve("src/compress").resolve(name);
    }
}
