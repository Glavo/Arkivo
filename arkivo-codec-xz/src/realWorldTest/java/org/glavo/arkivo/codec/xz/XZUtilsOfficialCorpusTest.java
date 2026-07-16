// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo against the official XZ Utils 5.8.3 decoder corpus.
@NotNullByDefault
public final class XZUtilsOfficialCorpusTest {
    /// The system property containing the extracted official corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.xz.testDataDirectory";

    /// The maximum output accepted from one official corpus file.
    private static final long MAXIMUM_OUTPUT_SIZE = 2L * 1024L * 1024L;

    /// The SHA-256 of an empty output.
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /// The SHA-256 of the common 13-byte XZ Utils test output.
    private static final String SMALL_SHA256 =
            "8e5935e7e13368cd9688fe8f48a0955293676a021562582c7e848dafe13fb046";

    /// The SHA-256 of the common 457-byte LZMA2 test output.
    private static final String LZMA2_SHA256 =
            "a643326fcc1346e96252c48931c32085ac7e3304adebd2e6e25390108c0b649e";

    /// Verifies every official valid XZ stream against output metadata produced by the official 5.8.3 CLI.
    @ParameterizedTest(name = "{0}")
    @MethodSource("validXZFiles")
    public void decompressesOfficialValidXZFile(GoldenFile file) throws IOException {
        byte[] decoded = decompress(new XZCodec(), corpusFile(file.name()));

        assertEquals(file.size(), decoded.length, file.name());
        assertEquals(file.sha256(), sha256(decoded), file.name());
    }

    /// Verifies every official malformed XZ stream is rejected through the checked channel API contract.
    @ParameterizedTest(name = "{0}")
    @MethodSource("badXZFileNames")
    public void rejectsOfficialMalformedXZFile(String name) {
        assertThrows(IOException.class, () -> decompress(new XZCodec(), corpusFile(name)), name);
    }

    /// Verifies every official stream using unsupported XZ fields or filter chains is rejected.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedXZFileNames")
    public void rejectsOfficialUnsupportedXZFile(String name) {
        assertThrows(IOException.class, () -> decompress(new XZCodec(), corpusFile(name)), name);
    }

    /// Verifies every official valid LZMA_Alone stream against the independent official CLI output.
    @ParameterizedTest(name = "{0}")
    @MethodSource("validLZMAFiles")
    public void decompressesOfficialValidLZMAFile(GoldenFile file) throws IOException {
        byte[] decoded = decompress(new LZMACodec(), corpusFile(file.name()));

        assertEquals(file.size(), decoded.length, file.name());
        assertEquals(file.sha256(), sha256(decoded), file.name());
    }

    /// Verifies every official malformed LZMA_Alone stream is rejected.
    @ParameterizedTest(name = "{0}")
    @MethodSource("badLZMAFileNames")
    public void rejectsOfficialMalformedLZMAFile(String name) {
        assertThrows(IOException.class, () -> decompress(new LZMACodec(), corpusFile(name)), name);
    }

    /// Verifies extraction retains the upstream licensing and exact download lock manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("COPYING")));
        assertTrue(Files.isRegularFile(corpusPath("tests/files/README")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Returns all official valid XZ streams and their independently generated output metadata.
    private static Stream<GoldenFile> validXZFiles() {
        Stream.Builder<GoldenFile> files = Stream.builder();
        addGoldenFiles(files, 0L, EMPTY_SHA256,
                "good-0-empty.xz",
                "good-0cat-empty.xz",
                "good-0catpad-empty.xz",
                "good-0pad-empty.xz",
                "good-1-empty-bcj-lzma2.xz",
                "good-1-lzma2-5.xz");
        addGoldenFiles(files, 457L, LZMA2_SHA256,
                "good-1-3delta-lzma2.xz",
                "good-1-lzma2-1.xz",
                "good-1-lzma2-2.xz",
                "good-1-lzma2-3.xz",
                "good-1-lzma2-4.xz");
        addGoldenFiles(files, 8_576L,
                "022bfaefea07d1647ac4ffdebd3a3e511202664207a3cd67f5af5b32e87ef524",
                "good-1-arm64-lzma2-1.xz",
                "good-1-arm64-lzma2-2.xz");
        addGoldenFiles(files, 13L, SMALL_SHA256,
                "good-1-block_header-1.xz",
                "good-1-block_header-2.xz",
                "good-1-block_header-3.xz",
                "good-1-check-crc32.xz",
                "good-1-check-crc64.xz",
                "good-1-check-none.xz",
                "good-1-check-sha256.xz",
                "good-2-lzma2.xz");
        addGoldenFiles(files, 929_138L,
                "ecfff4f7718dc4fcf0c5dba8813c29a2648053f9efbbc17d7946d2c37092885a",
                "good-1-delta-lzma2.tiff.xz");
        return files.build();
    }

    /// Returns all official valid LZMA_Alone streams and their independently generated output metadata.
    private static Stream<GoldenFile> validLZMAFiles() {
        return Stream.of(
                new GoldenFile("good-known_size-with_eopm.lzma", 13L, SMALL_SHA256),
                new GoldenFile("good-known_size-without_eopm.lzma", 13L, SMALL_SHA256),
                new GoldenFile("good-unknown_size-with_eopm.lzma", 13L, SMALL_SHA256)
        );
    }

    /// Returns the names of all official malformed XZ streams.
    private static List<String> badXZFileNames() throws IOException {
        return corpusFileNames("bad-", ".xz");
    }

    /// Returns the names of all official unsupported XZ streams.
    private static List<String> unsupportedXZFileNames() throws IOException {
        return corpusFileNames("unsupported-", ".xz");
    }

    /// Returns the names of all official malformed LZMA_Alone streams.
    private static List<String> badLZMAFileNames() throws IOException {
        return corpusFileNames("bad-", ".lzma");
    }

    /// Adds names sharing one expected output to a golden-file stream builder.
    private static void addGoldenFiles(
            Stream.Builder<GoldenFile> files,
            long size,
            String sha256,
            String... names
    ) {
        for (String name : names) {
            files.add(new GoldenFile(name, size, sha256));
        }
    }

    /// Returns sorted corpus file names matching the given prefix and suffix.
    private static List<String> corpusFileNames(String prefix, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(corpusPath("tests/files"))) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    /// Returns one file in the official decoder corpus.
    private static Path corpusFile(String name) {
        return corpusPath("tests/files").resolve(name);
    }

    /// Returns a path below the configured extracted corpus directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(configured).resolve(relativePath);
    }

    /// Decompresses one complete file through the given Arkivo channel codec with a bounded output.
    private static byte[] decompress(CompressionCodec<?> codec, Path sourcePath) throws IOException {

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (var source = Files.newByteChannel(sourcePath);
             var target = Channels.newChannel(decoded)) {
            codec.decompress(source, target, DecompressionLimits.ofMaximumOutputSize(MAXIMUM_OUTPUT_SIZE));
        }
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

    /// Describes one official valid stream and its independently verified output.
    ///
    /// @param name the official corpus file name
    /// @param size the expected decompressed size
    /// @param sha256 the expected lowercase SHA-256 of the decompressed bytes
    @NotNullByDefault
    private record GoldenFile(String name, long size, String sha256) {
        /// Returns the corpus file name used as the parameterized-test display value.
        @Override
        public String toString() {
            return name;
        }
    }
}
