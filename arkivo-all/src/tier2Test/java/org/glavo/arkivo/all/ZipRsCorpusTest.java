// Copyright (c) 2026 Glavo
// Fixture expectations adapted from zip-rs; see LICENSES/ZipRs-MIT.txt.
// SPDX-License-Identifier: MPL-2.0 AND MIT

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks zip-rs reference files and bounded parser behavior on its finite minimized fuzz corpus.
@NotNullByDefault
final class ZipRsCorpusTest {
    /// Archives whose physical entries have ordinary paths and independently decodable bodies.
    private static final @Unmodifiable List<String> ORDINARY = List.of(
            "chinese.zip", "data_descriptor.zip", "extended_timestamp.zip", "files_and_dirs.zip",
            "linux-7z.zip", "mimetype.zip", "non_utf8.zip", "ntfs.zip", "windows-7zip.zip", "xz.zip");

    /// Upstream offset, allocation, decompression, and false encryption regressions.
    private static final @Unmodifiable List<String> MALFORMED = List.of(
            "invalid_offset.zip", "invalid_offset2.zip",
            "invalid_cde_number_of_files_allocation_greater_offset.zip",
            "invalid_cde_number_of_files_allocation_smaller_offset.zip",
            "raw_deflate64_index_out_of_bounds.zip", "deflate64_issue_25.zip", "ignore_encryption_flag.zip");

    /// Budgets applied before interpreting untrusted minimized fuzz seeds.
    private static final ZipArchiveOptions.Read BOUNDED = new ZipArchiveOptions.Read(
            ArchiveReadOptions.DEFAULT.withLimits(ArchiveReadLimits.builder()
                    .maximumEntryCount(64).maximumEntrySize(1L << 20).maximumTotalEntrySize(1L << 20)
                    .maximumMetadataSize(1L << 20).maximumCompressionWindowSize(1L << 20)
                    .maximumDecoderMemorySize(1L << 20).build()));

    /// The non-secret password from the upstream AES tests.
    private static final ZipArchiveOptions.Read AES_OPTIONS = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
            ArkivoPasswordProvider.fixed("helloworld".getBytes(StandardCharsets.US_ASCII)));

    /// Returns ordinary archives for indexed and rewrite comparisons.
    private static Stream<String> ordinaryArchives() {
        return ORDINARY.stream();
    }

    /// Varies short reads without changing source bytes or reference expectations.
    private static Stream<Arguments> fragmentedArchives() {
        return ORDINARY.stream().flatMap(name -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Returns regressions which must fail when indexed contents are consumed.
    private static Stream<String> malformedArchives() {
        return MALFORMED.stream();
    }

    /// Lists every seed deterministically and closes the host directory before JUnit consumes the arguments.
    private static Stream<String> fuzzSeeds() throws IOException {
        try (var files = Files.list(root().resolve("fuzz/read/in"))) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().toList().stream();
        }
    }

    /// Accounts for the selected archive set, reference plaintext, separate asset licenses, and all 951 seeds.
    @Test
    void accountsForExtractedFixtures() throws IOException {
        Set<String> expected = new HashSet<>(ORDINARY);
        expected.addAll(MALFORMED);
        expected.addAll(List.of("aes_archive.zip", "deflate64.zip", "lzma.zip", "extended_timestamp_bad.zip",
                "comment_garbage.zip", "misaligned_comment.zip", "ppmd.zip", "symlink.zip", "zip64_demo.zip",
                "legacy/shrink.zip", "legacy/reduce.zip", "legacy/implode.zip"));
        for (int index = 1; index <= 5; index++) expected.add("zip64_magic_in_filename_" + index + ".zip");
        try (var files = Files.walk(fixture(""))) {
            assertEquals(expected, files.filter(path -> path.toString().endsWith(".zip"))
                    .map(path -> fixture("").relativize(path).toString().replace('\\', '/')).collect(Collectors.toSet()));
        }
        assertEquals(951, fuzzSeeds().count());
        for (String name : List.of("LICENSE", "UPSTREAM.properties", "tests/aes_encryption.rs", "src/read/zip_archive.rs",
                "tests/data/LICENSE.deflate64.zip.txt", "tests/data/folder/LICENSE.binary.wmv.txt")) {
            assertTrue(Files.size(root().resolve(name)) > 0, name);
        }
    }

    /// Compares all visible bodies with an independent decoder, including implicit directories.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void readsIndexedArchives(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
    }

    /// Compares physical entry order and content through a fragmented non-seekable source.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("fragmentedArchives")
    void readsFragmentedArchives(String name, int chunk) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
            assertFalse(reader.next());
        }
    }

    /// Preserves original files while rewriting the central directory and adding a new entry.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void preservesEntriesDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture(name), copy);
        var expected = ArchiveCorpusAssertions.readZipReference(copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{1, 3, 7});
        }
        var actual = ArchiveCorpusAssertions.readZipReference(copy);
        assertEquals(expected.size() + 1, actual.size());
        assertEquals(expected, actual.stream().filter(entry -> !entry.path().equals("added.bin")).toList());
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), actual);
        }
    }

    /// Verifies megabyte-scale binary Deflate64 and LZMA content against the separately licensed original file.
    @ParameterizedTest
    @ValueSource(strings = {"deflate64.zip", "lzma.zip"})
    void readsBinaryReference(String name) throws IOException, NoSuchAlgorithmException {
        byte[] expected = Files.readAllBytes(fixture("folder/binary.wmv"));
        assertEquals(2703788, expected.length);
        CRC32 crc = new CRC32();
        crc.update(expected);
        // Commons Compress cannot decode ZIP LZMA; the original body provides an independent oracle.
        var entries = name.equals("lzma.zip")
                ? List.of(new ArchiveCorpusAssertions.EntryDigest("binary.wmv", false, false, expected.length,
                crc.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected))))
                : ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/binary.wmv")));
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), entries);
        }
        for (int chunk : new int[]{7, 8192, 65536}) {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk))) {
                assertEquals(entries, ArchiveCorpusAssertions.readStreaming(reader));
                assertFalse(reader.next());
            }
        }
    }

    /// Reads and updates the original prefixed ZIP64 file; only the independent reference copy loses its preamble.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void readsAndUpdatesPrefixedZip64(int chunk, @TempDir Path directory) throws IOException {
        byte[] bytes = Files.readAllBytes(fixture("zip64_demo.zip"));
        byte[] prefix = "Leading junk.\n".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(prefix, Arrays.copyOf(bytes, prefix.length));
        Path reference = directory.resolve("reference.zip");
        Files.write(reference, Arrays.copyOfRange(bytes, prefix.length, bytes.length));
        var expected = ArchiveCorpusAssertions.readZipReference(reference);
        assertEquals(1, expected.size());
        assertEquals("-", expected.get(0).path());
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("zip64_demo.zip"));
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture("zip64_demo.zip"), chunk))) {
            assertArrayEquals("Hello, world!\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/-")));
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
        }
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture("zip64_demo.zip"), copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{1, 3, 7});
        }
        var actual = ArchiveCorpusAssertions.readZipReference(copy);
        assertEquals(2, actual.size());
        assertEquals(expected, actual.stream().filter(entry -> !entry.path().equals("added.bin")).toList());
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            assertArrayEquals(new byte[]{1, 3, 7}, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), actual);
        }
    }

    /// Reads all AES strengths and the stored encrypted entry against upstream plaintext and Zip4j.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void readsAesArchive(int chunk) throws IOException {
        byte[] expected = "Lorem ipsum dolor sit amet".getBytes(StandardCharsets.US_ASCII);
        try (var reference = new net.lingala.zip4j.ZipFile(fixture("aes_archive.zip").toFile(), "helloworld".toCharArray());
             var fileSystem = ZipArkivoFileSystem.open(fixture("aes_archive.zip"), AES_OPTIONS);
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture("aes_archive.zip"), chunk), AES_OPTIONS)) {
            assertEquals(4, reference.getFileHeaders().size());
            Set<String> observed = new HashSet<>();
            while (reader.next()) {
                var attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                String name = attributes.path();
                assertTrue(observed.add(name));
                ZipEncryption encryption = name.contains("128") ? ZipEncryption.WINZIP_AES_128
                        : name.contains("192") ? ZipEncryption.WINZIP_AES_192 : ZipEncryption.WINZIP_AES_256;
                assertEquals(encryption, attributes.encryption());
                assertEquals(0, attributes.compressionMethodId());
                assertEquals(0, reference.getFileHeader(name).getAesExtraDataRecord().getCompressionMethod().getCode());
                try (var input = reader.openInputStream();
                     var oracle = reference.getInputStream(reference.getFileHeader(name))) {
                    assertArrayEquals(expected, oracle.readAllBytes());
                    assertArrayEquals(expected, input.readAllBytes());
                }
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/" + name)));
            }
            assertEquals(4, observed.size());
        }
    }

    /// Resolves a link within the archive without creating or following a host symbolic link.
    @Test
    void readsSymbolicLink() throws IOException {
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(fixture("symlink.zip")).get();
             var fileSystem = ZipArkivoFileSystem.open(fixture("symlink.zip"));
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture("symlink.zip"), 1))) {
            var entry = reference.getEntries().nextElement();
            assertTrue(entry.isUnixSymlink());
            String target = reference.getUnixSymlink(entry);
            assertEquals("foo", target);
            Path link = fileSystem.getPath("/" + entry.getName());
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(target, Files.readSymbolicLink(link).toString());
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(target.getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Preserves indexed NTFS precision while streaming exposes only timestamps present in local records.
    @ParameterizedTest
    @CsvSource({"extended_timestamp.zip,2024-05-02T07:30:25Z", "ntfs.zip,2025-01-14T11:21:54.416939Z"})
    void readsExactTimestamps(String name, String instant) throws IOException {
        Instant expected = Instant.parse(instant);
        // The NTFS fixture stores its extra timestamps only in the central directory.
        Instant localExpected = name.equals("ntfs.zip")
                ? LocalDateTime.of(2025, 1, 14, 11, 21, 56).atZone(ZoneId.systemDefault()).toInstant() : expected;
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name));
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 1))) {
            assertEquals(expected, Files.getLastModifiedTime(fileSystem.getPath("/test.txt")).toInstant());
            boolean found = false;
            while (reader.next()) {
                var attributes = reader.readAttributes();
                if (attributes.path().equals("test.txt")) {
                    assertFalse(found);
                    found = true;
                    assertEquals(localExpected, attributes.lastModifiedTime().toInstant());
                }
            }
            assertTrue(found);
        }
    }

    /// Does not silently decode a ZIP method for which this library has no registered implementation.
    @ParameterizedTest
    @CsvSource({"legacy/shrink.zip,FIRST.TXT,1", "legacy/reduce.zip,first.txt,5",
            "legacy/implode.zip,first.txt,6", "ppmd.zip,ipsum.txt,98"})
    void rejectsUnsupportedCompression(String name, String entry, int method) throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name));
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 7))) {
            var attributes = Files.readAttributes(fileSystem.getPath("/" + entry), ZipArkivoEntryAttributes.class);
            assertEquals(method, attributes.compressionMethodId());
            assertNull(attributes.compressionMethod());
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/" + entry)));
            assertTrue(reader.next());
            assertEquals(method, reader.readAttributes(ZipArkivoEntryAttributes.class).compressionMethodId());
            assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
        }
    }

    /// Rejects malformed offsets and compressed bodies instead of returning a partially validated success.
    @ParameterizedTest
    @MethodSource("malformedArchives")
    void rejectsMalformedArchives(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), BOUNDED)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Keeps Arkivo's exact end-record boundary rule for stale bytes left after a shortened comment.
    @ParameterizedTest
    @ValueSource(strings = {"comment_garbage.zip", "misaligned_comment.zip"})
    void rejectsTrailingCommentGarbage(String name) throws IOException {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        try (var input = new ChunkedInput(fixture(name), 7); var reader = ZipArkivoStreamingReader.open(input)) {
            assertFalse(reader.next());
            assertTrue(input.available() > 0);
        }
    }

    /// Rejects malformed timestamp metadata before publishing indexed attributes.
    @Test
    void rejectsMalformedTimestamp() {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture("extended_timestamp_bad.zip"))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Rejects archives whose signature-like filenames also contain NUL bytes or an empty file name.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void rejectsSignatureLikeInvalidNames(int index) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture("zip64_magic_in_filename_" + index + ".zip"), BOUNDED)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Replays a fixed minimized input; only checked parse or read-limit failures are acceptable.
    @ParameterizedTest
    @MethodSource("fuzzSeeds")
    @Timeout(10)
    void replaysMinimizedFuzzSeed(String name) throws IOException {
        Path path = root().resolve("fuzz/read/in").resolve(name);
        assertTrue(Files.size(path) <= 256);
        exerciseBounded(path);
    }

    /// Checks both entry points under explicit budgets; runtime failures and errors are deliberately not swallowed.
    private static void exerciseBounded(Path path) throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(path, BOUNDED)) {
            var entries = ArchiveCorpusAssertions.readFileSystem(fileSystem);
            assertTrue(entries.stream().filter(entry -> !entry.directory()).mapToLong(ArchiveCorpusAssertions.EntryDigest::size)
                    .sum() <= 1L << 20);
        } catch (IOException expected) {
            // A minimized input may be structurally invalid, unsupported, or over the configured budget.
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(path, 3), BOUNDED)) {
            var entries = ArchiveCorpusAssertions.readStreaming(reader);
            assertTrue(entries.size() <= 64);
            assertTrue(entries.stream().mapToLong(ArchiveCorpusAssertions.EntryDigest::size).sum() <= 1L << 20);
        } catch (IOException expected) {
            // Local records need not have the same acceptance outcome as a complete central directory.
        }
    }

    /// Resolves a selected upstream archive or reference plaintext.
    private static Path fixture(String name) {
        return root().resolve("tests/data").resolve(name);
    }

    /// Returns the verified source release extracted by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.ziprs.testDataDirectory")));
    }

    /// Supplies short source reads without modifying fixture bytes.
    @NotNullByDefault
    private static final class ChunkedInput extends FilterInputStream {
        /// Maximum bytes returned by a bulk read.
        private final int chunk;

        /// Opens the selected file with a bounded read size.
        private ChunkedInput(Path path, int chunk) throws IOException {
            super(Files.newInputStream(path));
            this.chunk = chunk;
        }

        /// Reads no more than the configured source chunk size.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return in.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
