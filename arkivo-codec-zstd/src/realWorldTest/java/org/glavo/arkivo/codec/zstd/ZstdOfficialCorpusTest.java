// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo against the pinned official Zstandard 1.5.7 golden corpus.
@NotNullByDefault
public final class ZstdOfficialCorpusTest {
    /// The system property containing the extracted official corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.zstd.testDataDirectory";

    /// Verifies an official valid frame against output length and SHA-256 values produced by the official 1.5.7 CLI.
    @ParameterizedTest
    @MethodSource("validGoldenFrames")
    public void decompressesOfficialGoldenFrame(GoldenFrame frame) throws IOException {
        byte[] decoded = decompress(corpusPath("tests/golden-decompression").resolve(frame.name()));

        assertEquals(frame.size(), decoded.length, frame.name());
        assertEquals(frame.sha256(), sha256(decoded), frame.name());
    }

    /// Verifies every official malformed golden frame is rejected through the checked channel API contract.
    @ParameterizedTest
    @MethodSource("invalidGoldenFrameNames")
    public void rejectsOfficialMalformedGoldenFrame(String name) {
        Path frame = corpusPath("tests/golden-decompression-errors").resolve(name);

        assertThrows(IOException.class, () -> decompress(frame), name);
    }

    /// Verifies official compression regression inputs round-trip with default and historically sensitive parameters.
    @ParameterizedTest
    @MethodSource("compressionRegressionInputNames")
    public void roundTripsOfficialCompressionRegressionInput(String name) throws IOException {
        byte[] input = Files.readAllBytes(corpusPath("tests/golden-compression").resolve(name));
        ZstdCodec codec = new ZstdCodec();

        byte[] defaultCompressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, defaultCompressed), name + " default");
        assertArrayEquals(input, Zstd.decompress(defaultCompressed, input.length), name + " native default");

        ZstdCodec sensitiveCodec = ZstdCodec.builder()
                .compressionLevel(19L)
                .minimumMatch(7L)
                .build();
        byte[] sensitiveCompressed = compress(sensitiveCodec, input);
        assertArrayEquals(input, decompress(codec, sensitiveCompressed), name + " level 19");
        assertArrayEquals(input, Zstd.decompress(sensitiveCompressed, input.length), name + " native level 19");
    }

    /// Verifies the official dictionary missing literal symbols can encode and decode its matching HTTP sample.
    @Test
    public void roundTripsOfficialMissingSymbolsDictionary() throws IOException {
        byte[] dictionaryBytes = Files.readAllBytes(
                corpusPath("tests/golden-dictionaries/http-dict-missing-symbols")
        );
        byte[] input = Files.readAllBytes(corpusPath("tests/golden-compression/http"));
        ZstdDictionary dictionary = ZstdDictionary.of(dictionaryBytes);
        ZstdCodec codec = new ZstdCodec().withDictionary(dictionary);
        byte[] compressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, compressed));
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionaryBytes);
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }
    }

    /// Verifies the official zero-weight dictionary with self data and a symbol carrying a nonzero weight.
    @Test
    public void roundTripsOfficialZeroWeightDictionary() throws IOException {
        byte[] dictionary = Files.readAllBytes(corpusPath("tests/dict-files/zero-weight-dict"));

        verifyDictionaryInteroperability(dictionary, dictionary);
        verifyDictionaryInteroperability(
                dictionary,
                "0000000000000000000000000\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /// Trains a pure Java dictionary from official regression inputs and verifies native interoperability.
    @Test
    public void trainsFromOfficialCompressionRegressionInputs() throws IOException {
        List<Path> samplePaths = compressionRegressionInputNames()
                .map(name -> corpusPath("tests/golden-compression").resolve(name))
                .toList();
        long sampleCapacity = 0L;
        for (Path samplePath : samplePaths) {
            sampleCapacity = Math.addExact(sampleCapacity, Files.size(samplePath));
        }

        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(sampleCapacity, 8_192L, 9L);
        for (Path samplePath : samplePaths) {
            try (var source = Files.newByteChannel(samplePath)) {
                trainer.addSample(source, Files.size(samplePath));
            }
        }
        ZstdDictionary dictionary = trainer.train();
        byte[] input = Files.readAllBytes(corpusPath("tests/golden-compression/http"));
        ZstdCodec codec = new ZstdCodec().withDictionary(dictionary);

        byte[] compressed = compress(codec, input);
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionary.bytes());
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }

        byte[] nativeCompressed;
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            context.loadDict(dictionary.bytes());
            nativeCompressed = context.compress(input);
        }
        assertArrayEquals(input, decompress(codec, nativeCompressed));
    }

    /// Verifies extraction retains the upstream license and the exact download lock manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("LICENSE")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Returns official valid golden frame expectations.
    private static Stream<GoldenFrame> validGoldenFrames() {
        return Stream.of(
                new GoldenFrame(
                        "block-128k.zst",
                        131_068L,
                        "672003418993584239ec5c79232de911699178ad820cd3ca9779abbfe7f7ba7d"
                ),
                new GoldenFrame(
                        "empty-block.zst",
                        0L,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                ),
                new GoldenFrame(
                        "rle-first-block.zst",
                        1_048_576L,
                        "30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58"
                ),
                new GoldenFrame(
                        "zeroSeq_2B.zst",
                        13L,
                        "03ba204e50d126e4674c005e04d82e84c21366780af1f43bd54a37816b6ab340"
                )
        );
    }

    /// Returns official malformed golden frame file names.
    private static Stream<String> invalidGoldenFrameNames() {
        return Stream.of(
                "off0.bin.zst",
                "truncated_huff_state.zst",
                "zeroSeq_extraneous.zst"
        );
    }

    /// Returns official compression regression input file names.
    private static Stream<String> compressionRegressionInputNames() {
        return Stream.of(
                "PR-3517-block-splitter-corruption-test",
                "http",
                "huffman-compressed-larger",
                "large-literal-and-match-lengths"
        );
    }

    /// Returns a path below the configured extracted corpus directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(configured).resolve(relativePath);
    }

    /// Decompresses one complete Zstandard frame through the Arkivo channel API.
    private static byte[] decompress(Path frame) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (var source = Files.newByteChannel(frame);
             var target = Channels.newChannel(decoded)) {
            new ZstdCodec().decompress(source, target);
        }
        return decoded.toByteArray();
    }

    /// Verifies pure Java and native compression in both directions with one dictionary.
    private static void verifyDictionaryInteroperability(
            byte[] dictionaryBytes,
            byte[] input
    ) throws IOException {
        ZstdCodec codec = new ZstdCodec().withDictionary(
                ZstdDictionary.of(dictionaryBytes)
        );

        byte[] pureJavaCompressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, pureJavaCompressed));
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionaryBytes);
            assertArrayEquals(input, context.decompress(pureJavaCompressed, input.length));
        }

        byte[] nativeCompressed;
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            context.loadDict(dictionaryBytes);
            nativeCompressed = context.compress(input);
        }
        assertArrayEquals(input, decompress(codec, nativeCompressed));
    }

    /// Compresses bytes through the Arkivo channel API.
    private static byte[] compress(ZstdCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressed)
        );
        return compressed.toByteArray();
    }

    /// Decompresses bytes through the Arkivo channel API.
    private static byte[] decompress(ZstdCodec codec, byte[] compressed) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Returns the lowercase SHA-256 representation of bytes.
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    /// Describes one official valid frame and its independently verified output.
    ///
    /// @param name the official frame file name
    /// @param size the expected decompressed size
    /// @param sha256 the expected lowercase SHA-256 of the decompressed bytes
    @NotNullByDefault
    public record GoldenFrame(String name, long size, String sha256) {
    }
}
