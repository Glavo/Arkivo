// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Scenarios adapted from zlib-ng 2.3.2; see NOTICE and LICENSES/Zlib.txt.
// This Java adaptation differs from the upstream C and CMake tests.

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Adler32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies zlib-ng regression datasets using independent output checks and Arkivo's supported configurations.
///
/// The dataset matrix does not emulate zlib-ng-only tuning controls, fixed-code strategy, or live level changes.
@NotNullByDefault
final class ZlibNgCorpusTest {
    /// Upstream malformed gzip streams whose native regressions concern Huffman parsing.
    private static final @Unmodifiable List<String> MALFORMED = List.of(
            "CVE-2002-0059/test.gz", "CVE-2004-0797/test.gz",
            "CVE-2005-1849/test.gz", "CVE-2005-2096/test.gz"
    );

    /// Uncompressed issue reproductions and representative data files used in the interoperability matrix.
    private static final @Unmodifiable List<String> PAYLOADS = List.of(
            "CVE-2018-25032/default.txt", "CVE-2018-25032/fixed.txt",
            "GH-361/test.txt", "GH-364/test.bin", "GH-382/defneg3.dat", "GH-751/test.txt",
            "data/fireworks.jpg", "data/lcet10.txt", "data/paper-100k.pdf"
    );

    /// The independent SHA-256 of the original pigz tar archive decoded by the JDK.
    private static final String PIGZ_SHA256 = "9fb1161f0fc4fbc5d0e4de163432236d312512c59d0df0159a852fb0b19c1002";

    /// Limits output from malformed external input before it can exhaust the test process.
    private static final int MAXIMUM_OUTPUT_SIZE = 2 * 1024 * 1024;

    /// Accounts for all binary and text datasets in the selected upstream fixture directories.
    @Test
    void classifiesEveryDownloadedDataset() throws IOException {
        Set<String> expected = Stream.concat(Stream.concat(MALFORMED.stream(), PAYLOADS.stream()),
                Stream.of("GH-1600/packobj.gz", "GH-979/pigz-2.6.tar.gz")).collect(Collectors.toSet());
        try (Stream<Path> files = Files.walk(corpusDirectory().resolve("test"))) {
            Set<String> actual = files.filter(Files::isRegularFile)
                    .map(path -> corpusDirectory().resolve("test").relativize(path).toString().replace('\\', '/'))
                    .filter(name -> name.startsWith("CVE-") || name.startsWith("GH-") || name.startsWith("data/"))
                    .collect(Collectors.toSet());
            assertEquals(expected, actual);
        }
        assertEquals(15, expected.size());
        assertTrue(Files.size(corpusDirectory().resolve("LICENSE.md")) > 0);
        assertTrue(Files.readString(corpusDirectory().resolve("UPSTREAM.properties")).contains("version=2.3.2"));
    }

    /// Rejects the malformed CVE streams as format errors rather than accepting or merely truncating them.
    @ParameterizedTest(name = "{0}; input={1}, output={2}, direct={3}")
    @MethodSource("malformedCases")
    void rejectsMalformedHuffmanStreams(String name, int inputSize, int outputSize, boolean direct) throws IOException {
        byte[] compressed = fixture(name);
        assertThrows(IOException.class, () -> gunzip(compressed));
        try (CompressionDecoder decoder = GzipCodec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                IOException failure = assertThrows(IOException.class,
                        () -> decode(decoder, compressed, inputSize, outputSize, direct));
                assertFalse(failure instanceof EOFException, "Invalid Huffman grammar must be rejected before EOF");
                decoder.reset();
            }
        }
    }

    /// Reads a real pigz-produced archive through both stream and incrementally buffered gzip APIs.
    @ParameterizedTest(name = "input={0}, output={1}, direct={2}")
    @MethodSource("bufferCases")
    void decodesPigzArchive(int inputSize, int outputSize, boolean direct) throws Exception {
        byte[] compressed = fixture("GH-979/pigz-2.6.tar.gz");
        byte[] expected = gunzip(compressed);
        assertEquals(432640, expected.length);
        assertEquals(PIGZ_SHA256, sha256(expected));
        try (CompressionDecoder decoder = GzipCodec.DEFAULT.newDecoder()) {
            Decoded decoded = decode(decoder, compressed, inputSize, outputSize, direct);
            assertEquals(compressed.length, decoded.consumed());
            assertArrayEquals(expected, decoded.bytes());
        }
        try (var input = GzipCodec.DEFAULT.newInputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(expected, input.readNBytes(MAXIMUM_OUTPUT_SIZE));
            assertEquals(-1, input.read());
        }
    }

    /// Preserves the 20-byte suffix of a zlib stream whose upstream filename misleadingly ends in .gz.
    @ParameterizedTest(name = "input={0}, output={1}, direct={2}")
    @MethodSource("bufferCases")
    void preservesPackObjectBoundary(int inputSize, int outputSize, boolean direct) throws IOException {
        byte[] compressed = fixture("GH-1600/packobj.gz");
        byte[] expected = "alone in the dark\n".getBytes(StandardCharsets.US_ASCII);
        try (CompressionDecoder decoder = ZlibCodec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                Decoded decoded = decode(decoder, compressed, inputSize, outputSize, direct);
                assertEquals(26, decoded.consumed());
                assertEquals(20, compressed.length - decoded.consumed());
                assertArrayEquals(expected, decoded.bytes());
                decoder.reset();
            }
        }
    }

    /// Verifies both encoding directions for every raw dataset, level, and supported strategy.
    @ParameterizedTest(name = "{0}; level={1}, strategy={2}")
    @MethodSource("payloadCases")
    void interoperatesOnRegressionPayloads(String name, int level, DeflateStrategy strategy) throws IOException {
        byte[] expected = fixture(name);
        DeflateCodec codec = DeflateCodec.DEFAULT.withCompressionLevel(level).withStrategy(strategy);
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(expected));
        byte[] compressed = new byte[encoded.remaining()];
        encoded.get(compressed);
        assertArrayEquals(expected, inflateRaw(compressed));

        byte[] jdkCompressed = deflateRaw(expected, level, strategy);
        try (CompressionDecoder decoder = codec.newDecoder()) {
            Decoded actual = decode(decoder, jdkCompressed, 97, 259, true);
            assertEquals(jdkCompressed.length, actual.consumed());
            assertArrayEquals(expected, actual.bytes());
        }
    }

    /// Replays the two fuzz-generated encoder inputs with their original small output capacities.
    @ParameterizedTest(name = "{0}")
    @MethodSource("quickEncoderCases")
    void finishesFuzzGeneratedEncoderInputs(String file, int expectedSize, int outputSize, boolean gzip)
            throws IOException {
        byte[] input = sourceArray(file, "next_in");
        assertEquals(expectedSize, input.length);
        CompressionCodec<?> codec = gzip
                ? GzipCodec.DEFAULT.withCompressionLevel(1).withStrategy(DeflateStrategy.FILTERED)
                : DeflateCodec.DEFAULT.withCompressionLevel(1).withStrategy(DeflateStrategy.FILTERED);
        try (CompressionEncoder encoder = codec.newEncoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                ByteBuffer source = ByteBuffer.wrap(input).asReadOnlyBuffer();
                while (source.hasRemaining()) {
                    ByteBuffer target = ByteBuffer.allocate(outputSize);
                    int before = source.position();
                    encoder.encode(source, target);
                    assertTrue(source.position() > before || target.position() > 0, "Encoder made no progress");
                    encoded.write(target.array(), 0, target.position());
                }
                CodecOutcome outcome;
                do {
                    ByteBuffer target = ByteBuffer.allocate(outputSize);
                    outcome = encoder.finish(target);
                    assertTrue(outcome == CodecOutcome.FINISHED || target.position() > 0,
                            "Encoder finalization made no progress");
                    encoded.write(target.array(), 0, target.position());
                    assertTrue(encoded.size() <= MAXIMUM_OUTPUT_SIZE);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                assertEquals(CodecOutcome.FINISHED, outcome);
                byte[] compressed = encoded.toByteArray();
                assertArrayEquals(input, gzip ? gunzip(compressed) : inflateRaw(compressed));
                encoder.reset();
            }
        }
    }

    /// Verifies the upstream short-stream Adler-32 regression using its original bytes and known plaintext.
    @Test
    void verifiesShortZlibStreamChecksum() throws IOException {
        byte[] compressed = sourceArray("test_inflate_adler32.cc", "compressed");
        byte[] expected = "The quick brown fox jumped over the lazy dog".getBytes(StandardCharsets.US_ASCII);
        Adler32 adler = new Adler32();
        adler.update(expected);
        assertEquals(0x6b931030L, adler.getValue());
        for (boolean direct : new boolean[]{false, true}) {
            try (CompressionDecoder decoder = ZlibCodec.DEFAULT.newDecoder()) {
                assertArrayEquals(expected, decode(decoder, compressed, 1, 1, direct).bytes());
                for (int trailerByte = compressed.length - 4; trailerByte < compressed.length; trailerByte++) {
                    decoder.reset();
                    byte[] corrupt = compressed.clone();
                    corrupt[trailerByte] ^= 1;
                    IOException failure = assertThrows(IOException.class,
                            () -> decode(decoder, corrupt, 1, 1, direct));
                    assertTrue(failure.getMessage().contains("checksum mismatch"));
                }
            }
        }
    }

    /// Returns the upstream malformed streams under every fragmentation schedule.
    private static Stream<Arguments> malformedCases() {
        return MALFORMED.stream().flatMap(name -> bufferCases().map(arguments -> {
            Object[] values = arguments.get();
            return Arguments.of(name, values[0], values[1], values[2]);
        }));
    }

    /// Covers tiny buffers, match-length boundaries, and bulk decoding with heap and direct storage.
    private static Stream<Arguments> bufferCases() {
        return Stream.of(new int[]{1, 1}, new int[]{7, 257}, new int[]{8192, 258}, new int[]{65536, 32768})
                .flatMap(sizes -> Stream.of(false, true).map(direct -> Arguments.of(sizes[0], sizes[1], direct)));
    }

    /// Includes uncompressed blocks and each encoder match-search regime supported by the public codec.
    private static Stream<Arguments> payloadCases() {
        return PAYLOADS.stream().flatMap(name -> Stream.of(0, 1, 2, 4, 6, 9)
                .flatMap(level -> Arrays.stream(DeflateStrategy.values())
                        .map(strategy -> Arguments.of(name, level, strategy))));
    }

    /// Supplies the original buffer sizes of the upstream bit-buffer and block-open regressions.
    private static Stream<Arguments> quickEncoderCases() {
        return Stream.of(Arguments.of("test_deflate_quick_bi_valid.cc", 554, 31, true),
                Arguments.of("test_deflate_quick_block_open.cc", 495, 38, false));
    }

    /// Reads an initializer's hexadecimal bytes from a pinned C array, including implicit trailing zeroes.
    private static byte[] sourceArray(String file, String name) throws IOException {
        String source = Files.readString(corpusDirectory().resolve("test").resolve(file));
        Matcher initializer = Pattern.compile("\\b" + Pattern.quote(name)
                + "\\[(\\d*)]\\s*=\\s*(.*?);", Pattern.DOTALL).matcher(source);
        assertTrue(initializer.find(), "Missing C array: " + name);
        String declaredSize = initializer.group(1);
        Matcher hex = Pattern.compile("(?:0x|\\\\x)([0-9a-fA-F]{2})").matcher(initializer.group(2));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (hex.find()) {
            bytes.write(Integer.parseInt(hex.group(1), 16));
        }
        byte[] result = bytes.toByteArray();
        if (!declaredSize.isEmpty()) {
            int size = Integer.parseInt(declaredSize);
            // The string initializer has one implicit terminator; the numeric initializer is fully explicit.
            assertEquals(initializer.group(2).stripLeading().startsWith("\"") ? size - 1 : size, result.length);
            result = Arrays.copyOf(result, size);
        }
        assertTrue(result.length > 0);
        return result;
    }

    /// Drains one stream with bounded storage and preserves the absolute compressed-byte boundary.
    private static Decoded decode(
            CompressionDecoder decoder, byte @Unmodifiable [] compressed,
            int inputSize, int outputSize, boolean direct
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer source = direct ? ByteBuffer.allocateDirect(compressed.length) : ByteBuffer.allocate(compressed.length);
        source.put(compressed).flip();
        source = source.asReadOnlyBuffer();
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputSize) : ByteBuffer.allocate(outputSize);
        byte[] output = new byte[outputSize];
        int available = 0;
        while (true) {
            if (source.position() == available) {
                available += Math.min(inputSize, compressed.length - available);
            }
            source.limit(available);
            int before = source.position();
            CodecOutcome outcome = available == compressed.length
                    ? decoder.finish(source, target) : decoder.decode(source, target);
            int produced = target.position();
            target.flip().get(output, 0, produced);
            target.clear();
            decoded.write(output, 0, produced);
            assertTrue(decoded.size() <= MAXIMUM_OUTPUT_SIZE, "Unexpectedly large external fixture output");
            if (outcome == CodecOutcome.FINISHED) {
                return new Decoded(decoded.toByteArray(), source.position());
            }
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            assertTrue(produced > 0 || source.position() > before, "Decoder made no progress");
        }
    }

    /// Decodes gzip using the independent JDK implementation with a fixed output bound.
    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] result = input.readNBytes(MAXIMUM_OUTPUT_SIZE + 1);
            assertTrue(result.length <= MAXIMUM_OUTPUT_SIZE);
            return result;
        }
    }

    /// Decodes raw Deflate using the independent JDK implementation.
    private static byte[] inflateRaw(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater(true);
        try (var input = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
            byte[] result = input.readNBytes(MAXIMUM_OUTPUT_SIZE + 1);
            assertTrue(result.length <= MAXIMUM_OUTPUT_SIZE);
            assertTrue(inflater.finished());
            assertEquals(compressed.length, inflater.getBytesRead());
            return result;
        } finally {
            inflater.end();
        }
    }

    /// Produces an independent raw stream under the matching JDK level and strategy.
    private static byte[] deflateRaw(byte[] input, int level, DeflateStrategy strategy) {
        Deflater deflater = new Deflater(level, true);
        try {
            deflater.setStrategy(switch (strategy) {
                case DEFAULT -> Deflater.DEFAULT_STRATEGY;
                case FILTERED -> Deflater.FILTERED;
                case HUFFMAN_ONLY -> Deflater.HUFFMAN_ONLY;
            });
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Reads one original upstream dataset.
    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(corpusDirectory().resolve("test").resolve(name));
    }

    /// Returns the source directory prepared by Gradle.
    private static Path corpusDirectory() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.zlibNg.testDataDirectory"),
                "Missing prepared zlib-ng corpus directory"));
    }

    /// Computes a digest used to identify the complete independently decoded tar file.
    private static String sha256(byte[] input) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    /// Output and compressed-byte progress from one complete stream.
    ///
    /// @param bytes complete uncompressed content
    /// @param consumed compressed bytes consumed, excluding any suffix
    @NotNullByDefault
    private record Decoded(byte @Unmodifiable [] bytes, int consumed) {
    }
}
