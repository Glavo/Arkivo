// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo against the pinned official Zstandard 1.5.7 golden corpus.
@NotNullByDefault
public final class ZstdOfficialCorpusTest {
    /// The system property containing the extracted official corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.zstd.testDataDirectory";

    /// Every extracted official corpus file classified by this test suite.
    private static final @Unmodifiable Set<String> CLASSIFIED_CORPUS_FILES = Set.of(
            "LICENSE",
            "UPSTREAM.properties",
            "tests/dict-files/zero-weight-dict",
            "tests/golden-compression/PR-3517-block-splitter-corruption-test",
            "tests/golden-compression/http",
            "tests/golden-compression/huffman-compressed-larger",
            "tests/golden-compression/large-literal-and-match-lengths",
            "tests/golden-decompression-errors/off0.bin.zst",
            "tests/golden-decompression-errors/truncated_huff_state.zst",
            "tests/golden-decompression-errors/zeroSeq_extraneous.zst",
            "tests/golden-decompression/block-128k.zst",
            "tests/golden-decompression/empty-block.zst",
            "tests/golden-decompression/rle-first-block.zst",
            "tests/golden-decompression/zeroSeq_2B.zst",
            "tests/golden-dictionaries/http-dict-missing-symbols"
    );

    /// Verifies an official valid frame against output length and SHA-256 values produced by the official 1.5.7 CLI.
    @ParameterizedTest
    @MethodSource("validGoldenFrames")
    public void decompressesOfficialGoldenFrame(GoldenFrame frame) throws IOException {
        byte @Unmodifiable [] decoded =
                decompress(corpusPath("tests/golden-decompression").resolve(frame.name()));

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

    /// Rejects the official invalid repeat-offset dictionary when creating or supplying engine state.
    @ParameterizedTest(name = "invalid repeat offsets, buffer shape={0}")
    @ValueSource(ints = {0, 1, 2})
    void rejectsOfficialInvalidDictionary(int shape) throws IOException {
        Path root = Path.of(java.util.Objects.requireNonNull(System.getProperty("arkivo.zstd.referenceDirectory")));
        String source = Files.readString(root.resolve("tests/invalidDictionaries.c"));
        var declaration = Pattern.compile("static const char invalidRepCode\\[\\] = \\{([^}]+)};",
                Pattern.DOTALL).matcher(source);
        assertTrue(declaration.find());
        String body = declaration.group(1);
        assertTrue(body.matches("(?:\\s*0x[0-9a-fA-F]{2}\\s*,?)+\\s*"));
        byte[] bytes = HexFormat.of().parseHex(body.replace("0x", "").replaceAll("[\\s,]", ""));
        assertEquals(160, bytes.length);
        assertEquals("20124d802410a3dc8f2aa79e9233849bae5b596a2f3040dbd2baa9e88bd3d5fd", sha256(bytes));
        ByteBuffer storage = shape == 1 ? ByteBuffer.allocateDirect(bytes.length + 2)
                : ByteBuffer.allocate(bytes.length + 2);
        storage.position(1).put(bytes).flip().position(1);
        ByteBuffer view = shape == 2 ? storage.asReadOnlyBuffer() : storage;
        for (boolean automatic : new boolean[]{false, true}) {
            ZstdDictionary dictionary = automatic ? ZstdDictionary.of(view) : ZstdDictionary.fullDictionary(view);
            assertEquals(42, dictionary.dictionaryId());
            assertEquals(1, view.position());
            assertEquals(bytes.length + 1, view.limit());
            ZstdCodec codec = new ZstdCodec().withDictionary(dictionary);
            assertThrows(IOException.class, () -> {
                try (var encoder = codec.newEncoder()) {
                    encoder.finish(ByteBuffer.allocate(128));
                }
            });
            assertThrows(IOException.class, () -> {
                try (var decoder = codec.newDecoder()) {
                    decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0));
                }
            });
            // The dictionary envelope is valid; its repeat offsets are invalid when entropy state is loaded.
            byte[] emptyFrame = new byte[16];
            ByteArrayAccess.writeIntLittleEndian(emptyFrame, 0, 0xfd2fb528);
            emptyFrame[4] = (byte) 0xa3;
            ByteArrayAccess.writeIntLittleEndian(emptyFrame, 5, 42);
            emptyFrame[13] = 1;
            try (var decoder = new ZstdCodec().newDecoder()) {
                ByteBuffer frame = ByteBuffer.wrap(emptyFrame);
                ByteBuffer output = ByteBuffer.allocate(1);
                assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(frame, output));
                assertThrows(IOException.class, () -> decoder.provideDictionary(dictionary));
                assertEquals(0, output.position());
                decoder.reset();
                byte[] plain = {1, 2, 3};
                ByteBuffer decoded = ByteBuffer.allocate(plain.length);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(Zstd.compress(plain)), decoded));
                assertArrayEquals(plain, decoded.array());
            }
        }
        byte[] unchanged = new byte[bytes.length];
        view.duplicate().get(unchanged);
        assertArrayEquals(bytes, unchanged);
    }

    /// Verifies official compression regression inputs round-trip with default and historically sensitive parameters.
    @ParameterizedTest
    @MethodSource("compressionRegressionInputNames")
    public void roundTripsOfficialCompressionRegressionInput(String name) throws IOException {
        byte @Unmodifiable [] input = Files.readAllBytes(corpusPath("tests/golden-compression").resolve(name));
        ZstdCodec codec = new ZstdCodec();

        byte @Unmodifiable [] defaultCompressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, defaultCompressed), name + " default");
        assertArrayEquals(input, Zstd.decompress(defaultCompressed, input.length), name + " native default");

        ZstdCodec sensitiveCodec = ZstdCodec.builder()
                .compressionLevel(19L)
                .minimumMatch(7L)
                .build();
        byte @Unmodifiable [] sensitiveCompressed = compress(sensitiveCodec, input);
        assertArrayEquals(input, decompress(codec, sensitiveCompressed), name + " level 19");
        assertArrayEquals(input, Zstd.decompress(sensitiveCompressed, input.length), name + " native level 19");
    }

    /// Ports roundTripCrash.c's unsigned hash-selected level and its two-worker parameter variant.
    @ParameterizedTest
    @MethodSource("compressionRegressionInputNames")
    void roundTripCrashParameters(String name) throws IOException {
        byte[] input = Files.readAllBytes(corpusPath("tests/golden-compression").resolve(name));
        int level = (int) (XXHash32.DEFAULT.computeLong(input, 0, Math.min(128, input.length)) % 19);
        for (int workers : new int[]{0, 2}) {
            ZstdCodec codec = ZstdCodec.builder().compressionLevel(level).workerCount(workers).overlapLog(5).build();
            ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
            byte[] frame = new byte[compressed.remaining()];
            compressed.get(frame);
            assertArrayEquals(input, Zstd.decompress(frame, input.length), name + ", workers=" + workers);
            assertArrayEquals(input, decompress(codec, frame));
            try (var nativeEncoder = new ZstdCompressCtx()) {
                byte[] nativeFrame = nativeEncoder.setLevel(level).setWorkers(workers).setOverlapLog(5).compress(input);
                assertArrayEquals(input, decompress(codec, nativeFrame));
            }
        }
    }

    /// Verifies the official dictionary missing literal symbols can encode and decode its matching HTTP sample.
    @Test
    public void roundTripsOfficialMissingSymbolsDictionary() throws IOException {
        byte @Unmodifiable [] dictionaryBytes = Files.readAllBytes(
                corpusPath("tests/golden-dictionaries/http-dict-missing-symbols")
        );
        byte @Unmodifiable [] input = Files.readAllBytes(corpusPath("tests/golden-compression/http"));
        ZstdDictionary dictionary = ZstdDictionary.of(dictionaryBytes);
        ZstdCodec codec = new ZstdCodec().withDictionary(dictionary);
        byte @Unmodifiable [] compressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, compressed));
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionaryBytes);
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }
    }

    /// Verifies the official zero-weight dictionary with self data and a symbol carrying a nonzero weight.
    @Test
    public void roundTripsOfficialZeroWeightDictionary() throws IOException {
        byte @Unmodifiable [] dictionary =
                Files.readAllBytes(corpusPath("tests/dict-files/zero-weight-dict"));

        verifyDictionaryInteroperability(dictionary, dictionary);
        verifyDictionaryInteroperability(
                dictionary,
                "0000000000000000000000000\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /// Trains a pure Java dictionary from official regression inputs and verifies native interoperability.
    @Test
    public void trainsFromOfficialCompressionRegressionInputs() throws IOException {
        @Unmodifiable List<Path> samplePaths = compressionRegressionInputNames()
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
        byte @Unmodifiable [] input = Files.readAllBytes(corpusPath("tests/golden-compression/http"));
        ZstdCodec codec = new ZstdCodec().withDictionary(dictionary);

        byte @Unmodifiable [] compressed = compress(codec, input);
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionary.bytes());
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }

        byte @Unmodifiable [] nativeCompressed;
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            context.loadDict(dictionary.bytes());
            nativeCompressed = context.compress(input);
        }
        assertArrayEquals(input, decompress(codec, nativeCompressed));
    }

    /// Verifies every extracted file remains classified, including upstream provenance.
    @Test
    public void classifiesCompleteOfficialCorpusAndRetainsProvenance() throws IOException {
        Path root = corpusPath("");
        @Unmodifiable Set<String> actual;
        try (Stream<Path> files = Files.walk(root)) {
            actual = files
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toUnmodifiableSet());
        }

        assertEquals(CLASSIFIED_CORPUS_FILES, actual);
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
    private static byte @Unmodifiable [] decompress(Path frame) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (var source = Files.newByteChannel(frame);
             var target = Channels.newChannel(decoded)) {
            new ZstdCodec().decompress(source, target);
        }
        return decoded.toByteArray();
    }

    /// Verifies pure Java and native compression in both directions with one dictionary.
    private static void verifyDictionaryInteroperability(
            byte @Unmodifiable [] dictionaryBytes,
            byte @Unmodifiable [] input
    ) throws IOException {
        ZstdCodec codec = new ZstdCodec().withDictionary(
                ZstdDictionary.of(dictionaryBytes)
        );

        byte @Unmodifiable [] pureJavaCompressed = compress(codec, input);
        assertArrayEquals(input, decompress(codec, pureJavaCompressed));
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionaryBytes);
            assertArrayEquals(input, context.decompress(pureJavaCompressed, input.length));
        }

        byte @Unmodifiable [] nativeCompressed;
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            context.loadDict(dictionaryBytes);
            nativeCompressed = context.compress(input);
        }
        assertArrayEquals(input, decompress(codec, nativeCompressed));
    }

    /// Compresses bytes through the Arkivo channel API.
    private static byte @Unmodifiable [] compress(
            ZstdCodec codec,
            byte @Unmodifiable [] input
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressed)
        );
        return compressed.toByteArray();
    }

    /// Decompresses bytes through the Arkivo channel API.
    private static byte @Unmodifiable [] decompress(
            ZstdCodec codec,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Returns the lowercase SHA-256 representation of bytes.
    private static String sha256(byte @Unmodifiable [] bytes) {
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
