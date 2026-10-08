// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Decodes independently generated Zopfli streams through bounded, incremental buffer operations.
@NotNullByDefault
final class ZopfliInteropTest {
    /// Holds process inputs, outputs, and diagnostics for one test invocation.
    @TempDir
    Path directory;

    /// Combines wrapper formats and optimization iterations with distinct input distributions.
    private static Stream<Arguments> configurations() {
        return Stream.of("deflate", "zlib", "gzip").flatMap(format -> Stream.of(1, 5)
                .flatMap(iterations -> Stream.of("empty", "repetitive", "random", "source", "window", "mixed")
                        .map(sample -> Arguments.of(format, iterations, sample))));
    }

    /// Checks source provenance even when the optional native encoder is absent.
    @Test
    void referenceSourcesArePresent() throws IOException {
        assertTrue(Files.readString(root().resolve("COPYING")).contains("Apache License"));
        assertTrue(Files.readString(root().resolve("UPSTREAM.properties")).contains("version=zopfli-1.0.3"));
        assertTrue(Files.readString(root().resolve("go/zopfli/zopfli_test.go")).contains("TestGzip"));
        assertTrue(Files.size(root().resolve("src/zopfli/deflate.c")) > 0);
    }

    /// Checks content, input ownership, frame boundaries, truncation, checksum rejection, and decoder reuse.
    @ParameterizedTest(name = "{0}, iterations={1}, input={2}")
    @MethodSource("configurations")
    void decodesReferenceOutput(String format, int iterations, String sample) throws Exception {
        String executable = executable();
        byte[] expected = content(sample);
        byte[] compressed = compress(executable, format, iterations, expected);
        assertArrayEquals(expected, decodeWithJdk(format, compressed, expected.length));
        CompressionCodec<?> codec = codec(format, expected.length);
        try (var decoder = codec.newDecoder()) {
            for (int shape = 0; shape < 3; shape++) {
                assertArrayEquals(expected, decode(decoder, compressed, expected.length, shape));
                decoder.reset();
            }
            if (!format.equals("deflate")) {
                byte[] damaged = compressed.clone();
                // Gzip ends with CRC32 followed by ISIZE; zlib ends with Adler-32.
                damaged[damaged.length - (format.equals("gzip") ? 8 : 4)] ^= 1;
                assertThrows(IOException.class, () -> decode(decoder, damaged, expected.length, 1));
                decoder.reset();
                assertArrayEquals(expected, decode(decoder, compressed, expected.length, 2));
            }
        }
        // A concatenated-frame stream may contain zero members; truncate inside a member instead.
        for (int length : new int[]{1, compressed.length / 2, compressed.length - 1}) {
            byte[] truncated = Arrays.copyOf(compressed, length);
            assertThrows(IOException.class, () -> {
                try (var input = codec.newInputStream(new ByteArrayInputStream(truncated))) {
                    input.readAllBytes();
                }
            }, "Truncation at " + length);
        }
    }

    /// Creates bounded inputs including a 32 KiB match distance and changing symbol distributions.
    private static byte[] content(String sample) throws IOException {
        return switch (sample) {
            case "empty" -> new byte[0];
            case "repetitive" -> ("compressthis" + "_foobar".repeat(1000) + "$")
                    .getBytes(StandardCharsets.US_ASCII);
            case "source" -> Files.readAllBytes(root().resolve("src/zopfli/deflate.c"));
            case "random" -> {
                byte[] result = new byte[3000];
                new Random(1).nextBytes(result);
                yield result;
            }
            case "window" -> {
                byte[] result = new byte[65537];
                new Random(0x5a4f_5046L).nextBytes(result);
                System.arraycopy(result, 0, result, 32768, 32768);
                result[65536] = result[0];
                yield result;
            }
            case "mixed" -> {
                byte[] result = new byte[65537];
                new Random(0x424c_4f43L).nextBytes(result);
                Arrays.fill(result, 0, 16384, (byte) 'A');
                for (int i = 32768; i < 49152; i++) result[i] = (byte) (i & 7);
                yield result;
            }
            default -> throw new IllegalArgumentException(sample);
        };
    }

    /// Selects a wrapper with an output budget equal to the known original size.
    private static CompressionCodec<?> codec(String format, int size) {
        return switch (format) {
            case "deflate" -> DeflateCodec.DEFAULT.withMaximumOutputSize(size);
            case "zlib" -> ZlibCodec.DEFAULT.withMaximumOutputSize(size);
            case "gzip" -> GzipCodec.DEFAULT.withMaximumOutputSize(size);
            default -> throw new IllegalArgumentException(format);
        };
    }

    /// Validates producer output independently, including complete consumption for raw and zlib streams.
    private static byte[] decodeWithJdk(String format, byte @Unmodifiable [] compressed, int size) throws Exception {
        if (format.equals("gzip")) {
            try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] result = input.readNBytes(size + 1);
                assertEquals(size, result.length);
                assertEquals(-1, input.read());
                return result;
            }
        }
        Inflater inflater = new Inflater(format.equals("deflate"));
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                output.write(buffer, 0, count);
                assertTrue(output.size() <= size);
                assertTrue(count > 0 || inflater.finished(), "JDK did not finish the reference stream");
            }
            assertEquals(0, inflater.getRemaining());
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Exercises nonzero buffer positions, consumed-input poisoning, and unconsumed trailing bytes.
    private static byte[] decode(CompressionDecoder decoder, byte @Unmodifiable [] compressed,
                                 int size, int shape) throws IOException {
        int inputChunk = shape == 0 ? 1 : shape == 1 ? 7 : 8192;
        int outputChunk = shape == 0 ? 17 : shape == 1 ? 1 : 4093;
        ByteBuffer storage = shape == 1 ? ByteBuffer.allocateDirect(compressed.length + 4)
                : ByteBuffer.allocate(compressed.length + 4);
        storage.put((byte) 0x41).put(compressed).put(new byte[]{11, 23, 47}).position(1);
        ByteBuffer input = shape == 2 ? storage.asReadOnlyBuffer() : storage.duplicate();
        input.limit(Math.min(input.capacity(), 1 + inputChunk)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = shape == 1 ? ByteBuffer.allocateDirect(outputChunk + 2)
                : ByteBuffer.allocate(outputChunk + 2);
        target.order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int call = 0; call < 3 * (compressed.length + size + 16); call++) {
            target.clear().put((byte) 0x42);
            target.put(target.capacity() - 1, (byte) 0x43);
            target.limit(call == 0 ? 1 : outputChunk + 1);
            int start = input.position();
            int inputLimit = input.limit();
            int outputLimit = target.limit();
            CodecOutcome result = decoder.decode(input, target);
            assertEquals(inputLimit, input.limit());
            assertEquals(outputLimit, target.limit());
            assertTrue(input.position() >= start);
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
            assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
            for (int i = start; i < input.position(); i++) storage.put(i, (byte) 0xa5);
            int written = target.position() - 1;
            target.clear();
            assertEquals((byte) 0x42, target.get(0));
            assertEquals((byte) 0x43, target.get(target.capacity() - 1));
            for (int i = 0; i < written; i++) output.write(target.get(i + 1));
            assertTrue(output.size() <= size);
            if (result == CodecOutcome.FINISHED) {
                assertEquals(compressed.length + 1, input.position());
                input.limit(input.capacity());
                byte[] tail = new byte[3];
                input.get(tail);
                assertArrayEquals(new byte[]{11, 23, 47}, tail);
                return output.toByteArray();
            }
            if (result == CodecOutcome.NEEDS_INPUT) {
                assertTrue(input.limit() < input.capacity(), "Complete reference input did not finish");
                input.limit(Math.min(input.capacity(), input.limit() + inputChunk));
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
            }
        }
        return fail("Decoder exceeded the progress bound");
    }

    /// Runs the native encoder with file output and a bounded execution time.
    private byte[] compress(String executable, String format, int iterations,
                            byte @Unmodifiable [] content) throws Exception {
        Path source = directory.resolve("input.bin");
        Path output = directory.resolve("input.bin." + (format.equals("gzip") ? "gz" : format));
        Path log = directory.resolve("zopfli.log");
        Files.write(source, content);
        Files.deleteIfExists(output);
        Process process = new ProcessBuilder(executable, "--" + format, "--i" + iterations, source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Zopfli timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.isRegularFile(output), "Zopfli did not create its output file");
            return Files.readAllBytes(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Zopfli did not terminate");
            }
        }
    }

    /// Requires an explicit native executable; strict verification turns an absent tool into a failure.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_ZOPFLI_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZOPFLI"))) {
            assertTrue(available, "Set ARKIVO_ZOPFLI_EXECUTABLE to the required Zopfli encoder");
        }
        assumeTrue(available, "ARKIVO_ZOPFLI_EXECUTABLE is not configured");
        return Objects.requireNonNull(configured);
    }

    /// Returns the verified reference source directory supplied by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.zopfli.testDataDirectory")));
    }
}
