// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Cross-checks raw Deflate, zlib, and gzip against an explicitly configured pure JavaScript reference.
@NotNullByDefault
final class FflateInteropTest {
    /// Runs synchronous fflate APIs without npm installation, worker threads, or a native compression backend.
    private static final String DRIVER = """
            const fs = require('node:fs');
            const [modulePath, action, format, level, chunk, inputPath, outputPath, dictionaryPath] = process.argv.slice(1);
            const f = require(modulePath);
            const input = new Uint8Array(fs.readFileSync(inputPath));
            const options = { level: Number(level), mtime: 0 };
            if (dictionaryPath !== '-') options.dictionary = new Uint8Array(fs.readFileSync(dictionaryPath));
            const api = {
                raw: ['deflateSync', 'inflateSync', 'Deflate', 'Inflate'],
                zlib: ['zlibSync', 'unzlibSync', 'Zlib', 'Unzlib'],
                gzip: ['gzipSync', 'gunzipSync', 'Gzip', 'Gunzip']
            }[format];
            if (!api || !['encode', 'decode'].includes(action)) throw new Error('Invalid reference operation');
            const decoding = action === 'decode';
            let output;
            if (Number(chunk) === 0) {
                output = f[api[decoding ? 1 : 0]](input, options);
            } else {
                const parts = [];
                const chunkSize = Math.abs(Number(chunk));
                const stream = new f[api[decoding ? 3 : 2]](options, (part) => parts.push(Buffer.from(part)));
                for (let offset = 0; offset < input.length; offset += chunkSize) {
                    stream.push(input.subarray(offset, offset + chunkSize), false);
                    if (!decoding) stream.flush(Number(chunk) < 0);
                }
                stream.push(new Uint8Array(0), true);
                output = Buffer.concat(parts);
            }
            fs.writeFileSync(outputPath, output);
            """;

    /// Isolates inputs, outputs, and process diagnostics for each invocation.
    @TempDir
    private Path directory;

    /// Crosses wrapper, compression-level, window-boundary, and flush configurations.
    private static Stream<Arguments> configurations() {
        return Stream.of("raw", "zlib", "gzip").flatMap(format -> Stream.of(0, 6, 9)
                .flatMap(level -> Stream.of(0, 32769, 65537).flatMap(size -> Stream.of(false, true)
                        .map(streaming -> Arguments.of(format, level, size, streaming)))));
    }

    /// Covers dictionary truncation to the last 32 KiB in both supported dictionary wrappers.
    private static Stream<Arguments> dictionaries() {
        return Stream.of("raw", "zlib").flatMap(format -> Stream.of(1, 32768, 65537)
                .flatMap(size -> Stream.of(false, true).map(streaming -> Arguments.of(format, size, streaming))));
    }

    /// Checks downloaded provenance even when the optional Node.js runtime is not configured.
    @Test
    void referencePackageIsPresent() throws IOException {
        assertTrue(Files.size(root().resolve("LICENSE")) > 0);
        assertTrue(Files.size(root().resolve("UPSTREAM.properties")) > 0);
        assertTrue(Files.size(root().resolve("lib/browser.cjs")) > 0);
        assertTrue(Files.readString(root().resolve("package.json")).contains("\"version\": \"0.8.3\""));
    }

    /// Rejects malformed stored-block lengths emitted by the pinned reference's synchronous flush path.
    @Test
    void rejectsMalformedSynchronousFlushOutput() throws Exception {
        String executable = executable();
        byte[] content = new byte[32769];
        new Random(0x4646_4c41L).nextBytes(content);
        content[32768] = content[0];
        byte[] malformed = run(executable, "encode", "raw", 6, -1021, content, null);
        assertThrows(DataFormatException.class, () -> decodeWithJdk("raw", malformed, null, content.length));
        byte[] valid = run(executable, "encode", "raw", 6, 0, content, null);
        assertArrayEquals(content, decodeWithJdk("raw", valid, null, content.length));
        try (var decoder = DeflateCodec.DEFAULT.withMaximumOutputSize(content.length).newDecoder()) {
            for (int kind = 0; kind < 3; kind++) {
                int shape = kind;
                IOException failure = assertThrows(IOException.class,
                        () -> decode(decoder, malformed, content.length, shape));
                assertTrue(failure.getMessage().contains("stored block length complement does not match"));
                decoder.reset();
                assertArrayEquals(content, decode(decoder, valid, content.length, shape));
                decoder.reset();
            }
        }
    }

    /// Verifies both producer directions without assuming byte-identical compressed representations.
    @ParameterizedTest(name = "{0}, level={1}, size={2}, streaming={3}")
    @MethodSource("configurations")
    void interoperates(String format, int level, int size, boolean streaming) throws Exception {
        String executable = executable();
        byte[] content = new byte[size];
        new Random(0x4646_4c41L).nextBytes(content);
        if (size > 32768) System.arraycopy(content, 0, content, 32768, Math.min(32768, size - 32768));
        checkBothDirections(executable, format, level, content, null, streaming);
    }

    /// Requires dictionary references to reproduce the same bytes across independent implementations.
    @ParameterizedTest(name = "{0}, dictionary={1}, streaming={2}")
    @MethodSource("dictionaries")
    void interoperatesWithPresetDictionary(String format, int size, boolean streaming) throws Exception {
        String executable = executable();
        byte[] dictionary = new byte[size];
        new Random(0x4449_4354L).nextBytes(dictionary);
        byte[] content = new byte[32769];
        int retained = Math.min(size, 32768);
        for (int i = 0; i < content.length; i++) content[i] = dictionary[size - retained + i % retained];
        checkBothDirections(executable, format, 9, content, dictionary, streaming);
    }

    /// Compares one-shot or flushed streaming output, then reuses one decoder with three buffer layouts.
    private void checkBothDirections(String executable, String format, int level, byte @Unmodifiable [] content,
                                     byte @Nullable @Unmodifiable [] dictionary, boolean streaming) throws Exception {
        CompressionCodec<?> codec = codec(format, level, content.length, dictionary);
        byte[] reference = run(executable, "encode", format, level, streaming ? 1021 : 0, content, dictionary);
        assertArrayEquals(content, decodeWithJdk(format, reference, dictionary, content.length), "JDK reference validation");
        try (var decoder = codec.newDecoder()) {
            for (int kind = 0; kind < 3; kind++) {
                try {
                    assertArrayEquals(content, decode(decoder, reference, content.length, kind), "buffer kind " + kind);
                } catch (IOException exception) {
                    throw new IOException("Buffer kind " + kind, exception);
                }
                decoder.reset();
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var output = codec.newOutputStream(encoded)) {
            if (streaming) {
                for (int offset = 0; offset < content.length; offset += 1021) {
                    output.write(content, offset, Math.min(1021, content.length - offset));
                    output.flush();
                }
            } else {
                output.write(content);
            }
        }
        assertArrayEquals(content, run(executable, "decode", format, level, streaming ? 7 : 0,
                encoded.toByteArray(), dictionary));
    }

    /// Validates reference output independently before assigning a disagreement to Arkivo.
    private static byte[] decodeWithJdk(String format, byte[] compressed, byte @Nullable [] dictionary,
                                        int maximumOutput) throws Exception {
        if (format.equals("gzip")) {
            try (var input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
                byte[] output = input.readNBytes(maximumOutput + 1);
                assertTrue(output.length <= maximumOutput, "fflate output exceeds the original length in JDK");
                assertEquals(-1, input.read());
                return output;
            }
        }
        var inflater = new Inflater(format.equals("raw"));
        try {
            if (dictionary != null && format.equals("raw")) inflater.setDictionary(dictionary);
            inflater.setInput(compressed);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                output.write(buffer, 0, count);
                assertTrue(output.size() <= maximumOutput, "fflate output exceeds the original length in JDK");
                if (inflater.needsDictionary()) inflater.setDictionary(Objects.requireNonNull(dictionary));
                else assertTrue(count != 0 || inflater.finished(), "JDK cannot finish the fflate reference stream");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Constructs a codec with an exact output budget and the wrapper-specific dictionary representation.
    private static CompressionCodec<?> codec(String format, int level, int maximumOutput,
                                               byte @Nullable @Unmodifiable [] dictionary) {
        return switch (format) {
            case "raw" -> {
                var codec = DeflateCodec.DEFAULT.withCompressionLevel(level).withMaximumOutputSize(maximumOutput);
                yield dictionary == null ? codec : codec.withDictionary(RawCompressionDictionary.of(dictionary));
            }
            case "zlib" -> {
                var codec = ZlibCodec.DEFAULT.withCompressionLevel(level).withMaximumOutputSize(maximumOutput);
                yield dictionary == null ? codec : codec.withDictionary(ZlibDictionary.of(dictionary));
            }
            case "gzip" -> GzipCodec.DEFAULT.withCompressionLevel(level).withMaximumOutputSize(maximumOutput);
            default -> throw new IllegalArgumentException(format);
        };
    }

    /// Checks input fragmentation, output guards, byte order, empty output, and unconsumed frame suffixes.
    private static byte[] decode(CompressionDecoder decoder, byte @Unmodifiable [] compressed,
                                 int maximumOutput, int kind) throws IOException {
        int inputChunk = kind == 0 ? 1 : kind == 1 ? 7 : 8192;
        int outputChunk = kind == 0 ? 17 : kind == 1 ? 127 : 8192;
        ByteBuffer input = kind == 1 ? ByteBuffer.allocateDirect(compressed.length + 4)
                : ByteBuffer.allocate(compressed.length + 4);
        input.put((byte) 0x41).put(compressed).put(new byte[]{11, 23, 47}).position(1);
        if (kind == 2) input = input.asReadOnlyBuffer();
        input.limit(Math.min(input.capacity(), 1 + inputChunk));
        input.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = kind == 1 ? ByteBuffer.allocateDirect(outputChunk + 2) : ByteBuffer.allocate(outputChunk + 2);
        target.order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int call = 0; call < 3 * (compressed.length + maximumOutput + 16); call++) {
            target.clear().put((byte) 0x42);
            target.put(target.capacity() - 1, (byte) 0x43);
            target.limit(call == 0 ? 1 : outputChunk + 1);
            int sourceLimit = input.limit();
            int targetLimit = target.limit();
            CodecOutcome result = decoder.decode(input, target);
            assertEquals(sourceLimit, input.limit());
            assertEquals(targetLimit, target.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
            assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
            int written = target.position() - 1;
            target.clear();
            assertEquals((byte) 0x42, target.get(0));
            assertEquals((byte) 0x43, target.get(target.capacity() - 1));
            for (int i = 0; i < written; i++) output.write(target.get(i + 1));
            assertTrue(output.size() <= maximumOutput);
            if (result == CodecOutcome.FINISHED) {
                assertEquals(compressed.length + 1, input.position());
                input.limit(input.capacity());
                byte[] tail = new byte[3];
                input.get(tail);
                assertArrayEquals(new byte[]{11, 23, 47}, tail);
                return output.toByteArray();
            }
            assertNotEquals(CodecOutcome.NEEDS_DICTIONARY, result);
            if (result == CodecOutcome.NEEDS_INPUT) {
                assertTrue(input.limit() < input.capacity(), "Complete reference stream did not finish");
                input.limit(Math.min(input.capacity(), input.limit() + inputChunk));
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
            }
        }
        return fail("Decoder exceeded the progress bound");
    }

    /// Runs a bounded reference process; negative chunk sizes request synchronous rather than buffered flushes.
    private byte[] run(String executable, String action, String format, int level, int chunk,
                       byte @Unmodifiable [] content, byte @Nullable @Unmodifiable [] dictionary) throws Exception {
        Path input = directory.resolve("input.bin");
        Path output = directory.resolve("output.bin");
        Path dictionaryFile = directory.resolve("dictionary.bin");
        Path log = directory.resolve("reference.log");
        Files.write(input, content);
        if (dictionary != null) Files.write(dictionaryFile, dictionary);
        Files.deleteIfExists(output);
        Process process = new ProcessBuilder(executable, "-e", DRIVER, root().resolve("lib/browser.cjs").toString(),
                action, format, Integer.toString(level), Integer.toString(chunk), input.toString(), output.toString(),
                dictionary == null ? "-" : dictionaryFile.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fflate reference timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); }
                catch (IOException e) { return e.toString(); }
            });
            return Files.readAllBytes(output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /// Returns the explicitly selected Node.js runtime, with an opt-in failure instead of an optional skip.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_NODE_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_NODE"))) {
            assertTrue(available, "Set ARKIVO_NODE_EXECUTABLE to the required Node.js runtime");
        }
        assumeTrue(available, "ARKIVO_NODE_EXECUTABLE is not configured");
        return Objects.requireNonNull(configured);
    }

    /// Returns the verified package directory populated by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.fflate.testDataDirectory")));
    }
}
