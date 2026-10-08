// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.compress.UnixCompressCodec;
import org.glavo.arkivo.codec.compress.UnixCompressFormat;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads GNU gzip's original reference bytes and crash reproducers without executing upstream scripts.
@NotNullByDefault
final class GnuGzipCorpusTest {
    /// The bounded decoder used for adversarial inputs; a limit failure does not count as format rejection.
    private static final GzipCodec BOUNDED = GzipCodec.DEFAULT.withMaximumOutputSize(1 << 20);

    /// One unchanged upstream compressed vector and its independently specified plaintext.
    ///
    /// @param name the upstream label
    /// @param bytes the compressed input
    /// @param plain the expected output
    @NotNullByDefault
    private record Vector(String name, byte @Unmodifiable [] bytes, byte @Unmodifiable [] plain) {
        /// Identifies the fixture in parameterized test reports.
        @Override
        public String toString() {
            return name;
        }
    }

    /// An upstream input whose complete gzip-stream interpretation must fail.
    ///
    /// @param name the upstream reproducer label
    /// @param bytes the original invalid or unsupported input
    @NotNullByDefault
    private record FailureCase(String name, byte @Unmodifiable [] bytes) {
        /// Identifies the reproducer in parameterized test reports.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Extracts only the six literal hexadecimal vectors from the downloaded reference table.
    private static List<Vector> references() throws IOException {
        var matcher = Pattern.compile("(?m)^([a-z]*): ([0-9a-f ]+)$").matcher(script("reference"));
        List<Vector> result = new ArrayList<>();
        while (matcher.find()) {
            String plain = matcher.group(1);
            result.add(new Vector(plain.isEmpty() ? "empty" : plain,
                    HexFormat.of().parseHex(matcher.group(2).replace(" ", "")), plain.getBytes(StandardCharsets.US_ASCII)));
        }
        assertEquals(6, result.size());
        return result;
    }

    /// Supplies reference vectors with small input and output windows in three buffer layouts.
    private static Stream<Arguments> bufferCases() throws IOException {
        return references().stream().flatMap(vector -> Stream.of(1, 2, 7, 8192).flatMap(input ->
                Stream.of(1, 7).flatMap(output -> Stream.of(0, 1, 2)
                        .map(kind -> Arguments.of(vector, input, output, kind)))));
    }

    /// Checks each reference's exact output, reset behavior, and unconsumed bytes after the member.
    @ParameterizedTest(name = "{0}, input={1}, output={2}, buffers={3}")
    @MethodSource("bufferCases")
    void readsOriginalReference(Vector vector, int input, int output, int kind) throws IOException {
        try (var reference = new GZIPInputStream(new ByteArrayInputStream(vector.bytes()))) {
            assertArrayEquals(vector.plain(), reference.readAllBytes());
        }
        try (var decoder = BOUNDED.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                assertArrayEquals(vector.plain(), decode(decoder, vector.bytes(), input, output, kind, true));
                decoder.reset();
            }
        }
    }

    /// Reads all six original members, including an empty member, as one logical stream through short reads.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 8192})
    void readsConcatenatedReferences(int chunk) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (Vector vector : references()) {
            encoded.writeBytes(vector.bytes());
            expected.writeBytes(vector.plain());
        }
        try (var source = new ShortInput(encoded.toByteArray(), chunk); var input = BOUNDED.newInputStream(source)) {
            assertArrayEquals(expected.toByteArray(), input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read());
        }
    }

    /// Extracts the literal crash input from a printf statement, not shell commands or substitutions.
    private static byte[] printfInput(String scriptName, String target) throws IOException {
        var matcher = Pattern.compile("(?m)^printf '([^']*)' > " + Pattern.quote(target) + "(?: |\\R)")
                .matcher(script(scriptName));
        assertTrue(matcher.find(), "Missing upstream input: " + target);
        byte[] bytes = printfBytes(matcher.group(1));
        assertFalse(matcher.find());
        return bytes;
    }

    /// Combines the binary Huffman reproducer, bug 33501, and all three unpack-invalid inputs.
    private static List<FailureCase> invalidInputs() throws IOException {
        List<FailureCase> vectors = new ArrayList<>();
        vectors.add(new FailureCase("hufts-segv", Files.readAllBytes(root().resolve("tests/hufts-segv.gz"))));
        vectors.add(new FailureCase("bug33501", printfInput("hufts", "bug33501")));
        var matcher = Pattern.compile("(?m)^  '([^']*)'").matcher(script("unpack-invalid"));
        while (matcher.find()) {
            vectors.add(new FailureCase("unpack-invalid-" + (vectors.size() - 2), printfBytes(matcher.group(1))));
        }
        assertEquals(5, vectors.size());
        return vectors;
    }

    /// Supplies invalid input both at stream start and following a complete valid gzip member.
    private static Stream<Arguments> invalidCases() throws IOException {
        return invalidInputs().stream().flatMap(vector -> Stream.of(1, 7, 8192).flatMap(chunk ->
                Stream.of(false, true).map(prefixed -> Arguments.of(vector, chunk, prefixed))));
    }

    /// Rejects invalid data or the unsupported pack format without masking implementation or limit failures.
    @ParameterizedTest(name = "{0}, input={1}, after member={2}")
    @MethodSource("invalidCases")
    void rejectsOriginalCrashInputs(FailureCase vector, int chunk, boolean prefixed) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        Vector first = references().get(1);
        if (prefixed) encoded.writeBytes(first.bytes());
        encoded.writeBytes(vector.bytes());
        try (var source = new ShortInput(encoded.toByteArray(), chunk); var input = BOUNDED.newInputStream(source)) {
            if (prefixed) assertArrayEquals(first.plain(), input.readNBytes(first.plain().length));
            IOException failure = assertThrows(IOException.class, input::readAllBytes);
            assertFalse(failure instanceof DecompressionLimitException, "Expected a format failure, not a safety budget");
        }
    }

    /// Keeps the original Huffman crash checks at engine level and verifies recovery through explicit reset.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void resetsAfterMalformedHuffmanTrees(int kind) throws IOException {
        try (var decoder = BOUNDED.newDecoder()) {
            for (FailureCase vector : invalidInputs().subList(0, 2)) {
                IOException failure = assertThrows(IOException.class, () -> decode(decoder, vector.bytes(), 1, 1, kind, false));
                assertFalse(failure instanceof DecompressionLimitException);
                decoder.reset();
                Vector valid = references().get(5);
                assertArrayEquals(valid.plain(), decode(decoder, valid.bytes(), 1, 1, kind, true));
                decoder.reset();
            }
        }
    }

    /// Reads Aki Helin's short Unix compress reproducer as .Z, rather than dispatching by its misleading .gz filename.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void readsHelinUnixCompressInput(int kind) throws IOException {
        byte[] bytes = printfInput("helin-segv", "helin.gz");
        assertEquals(UnixCompressFormat.NAME, Objects.requireNonNull(CompressionFormats.detect(ByteBuffer.wrap(bytes))).name());
        byte[] expected = printfInput("helin-segv", "exp");
        try (var reference = new ZCompressorInputStream(new ByteArrayInputStream(bytes))) {
            assertArrayEquals(expected, reference.readAllBytes());
        }
        try (var decoder = UnixCompressCodec.DEFAULT.withMaximumOutputSize(16).newDecoder()) {
            assertArrayEquals(expected, decode(decoder, bytes, 1, 1, kind, false));
            decoder.reset();
            assertArrayEquals(expected, decode(decoder, bytes, 7, 7, kind, false));
        }
    }

    /// Generates the overlapping-copy regression's specified wxy prefix followed by 32,767 ASCII zero bytes.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void readsOverlappingWindowCopies(int chunk) throws IOException {
        assertTrue(script("memcpy-abuse").contains("printf wxy%032767d 0"));
        byte[] expected = new byte[32770];
        Arrays.fill(expected, (byte) '0');
        expected[0] = 'w';
        expected[1] = 'x';
        expected[2] = 'y';
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var reference = new GZIPOutputStream(encoded)) {
            reference.write(expected);
        }
        try (var source = new ShortInput(encoded.toByteArray(), chunk); var input = BOUNDED.newInputStream(source)) {
            assertArrayEquals(expected, input.readAllBytes());
        }
        try (var decoder = BOUNDED.newDecoder()) {
            assertArrayEquals(expected, decode(decoder, encoded.toByteArray(), chunk, 7, 1, true));
        }
    }

    /// Distinguishes GNU's permissive NUL padding from Arkivo's strict complete-stream and member-engine contracts.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4096})
    void leavesPaddingToTheCaller(int zeros) throws IOException {
        assertTrue(script("trailing-nul").contains("gzip accepts trailing NUL bytes"));
        Vector vector = references().get(4);
        byte[] bytes = Arrays.copyOf(vector.bytes(), vector.bytes().length + zeros);
        try (var input = BOUNDED.newInputStream(new ShortInput(bytes, 1))) {
            assertArrayEquals(vector.plain(), input.readNBytes(vector.plain().length));
            assertThrows(IOException.class, input::readAllBytes);
        }
        try (var decoder = BOUNDED.newDecoder()) {
            ByteBuffer source = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
            ByteBuffer target = ByteBuffer.allocate(32);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
            assertEquals(vector.bytes().length, source.position());
            assertEquals(zeros, source.remaining());
            assertArrayEquals(vector.plain(), Arrays.copyOf(target.array(), target.position()));
        }
    }

    /// Checks the retained license and sample digests independently obtained with the upstream printf semantics.
    @Test
    void retainsProvenance() throws Exception {
        assertTrue(Files.size(root().resolve("COPYING")) > 0);
        assertTrue(Files.size(root().resolve("UPSTREAM.properties")) > 0);
        assertEquals(425, Files.size(root().resolve("tests/hufts-segv.gz")));
        assertEquals(6, references().size());
        List<FailureCase> invalid = invalidInputs();
        @Unmodifiable List<String> digests = List.of(
                "f17d507a54c6816698890022e7684ac8800a417959e1a243ce296a29659a291d",
                "99dac214ce0a938cfb670cfa829de1227129d04b29821c0c7247d3efe581ea95",
                "32d56b6a6a5735143cad3e1f4867a400678e922f3dea01e8af6dfa45e2703e60",
                "0ba3fc5fbc2a6d198bfd7bfcd57fbea4ac4629bbb4a3de3fbab28b35648895d7",
                "271a53bf585610d94d64b1c5b69e519aaf6a14fed88ebd27b667211a349086a6");
        assertEquals(digests.size(), invalid.size());
        for (int i = 0; i < invalid.size(); i++) {
            assertEquals(digests.get(i), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(invalid.get(i).bytes())), invalid.get(i).name());
        }
    }

    /// Decodes in finite steps with nonzero buffer positions and an optional caller-owned trailer.
    private static byte[] decode(CompressionDecoder decoder, byte[] bytes, int inputSize, int outputSize,
                                 int kind, boolean trailer) throws IOException {
        int end = 3 + bytes.length;
        ByteBuffer storage = kind == 1 ? ByteBuffer.allocateDirect(end + 3) : ByteBuffer.allocate(end + 3);
        storage.position(3).put(bytes).put(new byte[]{17, 29, 43}).position(3).limit(3);
        ByteBuffer source = (kind == 2 ? storage.asReadOnlyBuffer() : storage).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int calls = 0; ; calls++) {
            assertTrue(calls < 2 * (bytes.length + (1 << 20)), "Decoder failed to terminate");
            if (!source.hasRemaining() && source.limit() < end) {
                source.limit(Math.min(end, source.limit() + inputSize));
                if (trailer && source.limit() == end) source.limit(end + 3);
            }
            int limit = source.limit();
            ByteBuffer target = kind == 1 ? ByteBuffer.allocateDirect(outputSize + 6) : ByteBuffer.allocate(outputSize + 6);
            target.position(3).limit(3 + outputSize).order(ByteOrder.LITTLE_ENDIAN);
            CodecOutcome result = source.limit() >= end ? decoder.finish(source, target) : decoder.decode(source, target);
            assertEquals(limit, source.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
            assertEquals(outputSize + 3, target.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
            target.flip().position(3);
            byte[] part = new byte[target.remaining()];
            target.get(part);
            output.writeBytes(part);
            assertTrue(output.size() <= 1 << 20);
            if (result == CodecOutcome.FINISHED) {
                assertEquals(end, source.position());
                if (trailer) {
                    assertEquals(3, source.remaining());
                    byte[] tail = new byte[3];
                    source.get(tail);
                    assertArrayEquals(new byte[]{17, 29, 43}, tail);
                }
                return output.toByteArray();
            }
            if (result == CodecOutcome.NEEDS_OUTPUT) assertEquals(outputSize, part.length);
            else {
                assertEquals(CodecOutcome.NEEDS_INPUT, result);
                assertFalse(source.hasRemaining());
                assertTrue(source.limit() < end, "Requested input after finish");
            }
        }
    }

    /// Decodes only literal ASCII, octal escapes, common character escapes, and escaped percent signs.
    private static byte[] printfBytes(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%') {
                assertTrue(++i < text.length() && text.charAt(i) == '%', "Unsupported printf directive");
                bytes.write('%');
            } else if (ch != '\\') {
                assertTrue(ch < 128);
                bytes.write(ch);
            } else {
                assertTrue(++i < text.length());
                ch = text.charAt(i);
                if (ch >= '0' && ch <= '7') {
                    int value = ch - '0';
                    for (int digits = 1; digits < 3 && i + 1 < text.length(); digits++) {
                        char next = text.charAt(i + 1);
                        if (next < '0' || next > '7') break;
                        value = value * 8 + next - '0';
                        i++;
                    }
                    assertTrue(value <= 255);
                    bytes.write(value);
                } else {
                    bytes.write(switch (ch) {
                        case '\\' -> '\\';
                        case 'b' -> '\b';
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case 'v' -> 11;
                        case 'f' -> '\f';
                        case 'r' -> '\r';
                        default -> throw new AssertionError("Unsupported printf escape: " + ch);
                    });
                }
            }
        }
        return bytes.toByteArray();
    }

    /// Reads an unchanged upstream script as data, never as executable code.
    private static String script(String name) throws IOException {
        return Files.readString(root().resolve("tests/" + name));
    }

    /// Resolves the verified GNU gzip source extraction.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.gnu-gzip.testDataDirectory")));
    }

    /// Supplies short reads with no availability estimate.
    @NotNullByDefault
    private static final class ShortInput extends ByteArrayInputStream {
        /// Maximum bytes returned from a bulk read.
        private final int chunk;

        /// Wraps a complete original input with the requested fragmentation.
        private ShortInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        /// Restricts reads without treating available() as an EOF indication.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(chunk, length));
        }

        /// Reports no immediately available data even when more can be read.
        @Override
        public synchronized int available() {
            return 0;
        }
    }
}
