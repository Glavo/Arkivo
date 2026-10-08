// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Classifies every minizip-ng ZIP seed and checks contents without extracting entries onto the host file system.
@NotNullByDefault
final class MinizipCorpusTest {
    /// Seeds readable by Commons Compress without encryption or ZIP LZMA/XZ support.
    private static final @Unmodifiable List<String> ORDINARY = List.of(
            "as.zip", "bzip2.zip", "comments.zip", "corpus.zip", "gh.zip", "large_cd_comment.zip",
            "permissions.zip", "signed.zip", "storeonly.zip", "tiny.zip", "unsupported_permissions.zip", "zip64.zip");

    /// The non-secret password specified by the upstream unzip fuzzer.
    private static final String PASSWORD = "test123";

    /// Returns ordinary archives with short and bulk transport reads.
    private static Stream<Arguments> ordinaryCases() {
        return ORDINARY.stream().flatMap(name -> Stream.of(1, 127, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Accounts for all seeds, including malformed records and a deliberately unsupported compression method.
    @Test
    void accountsForEverySeed() throws IOException {
        Set<String> classified = new HashSet<>(ORDINARY);
        classified.addAll(Set.of("lzma.zip", "xz.zip", "zstd.zip", "license_zstd.zip", "encrypted_pkcrypt.zip",
                "encrypted_wzaes.zip", "infozip_symlinks.zip", "dot_dot_backslash_name.zip", "gh_739.zip",
                "gh_740.zip", "incorrect_number_entries.zip", "ppmd.zip"));
        assertEquals(24, classified.size());
        try (var files = Files.list(fixture(""))) {
            assertEquals(classified, files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        for (String name : List.of("LICENSE", "UPSTREAM.properties", "test/fuzz/unzip_fuzzer.c")) {
            assertTrue(Files.size(root().resolve(name)) > 0, name);
        }
    }

    /// Compares complete bodies, entry kinds, and ordering with independently decoded archive contents.
    @ParameterizedTest(name = "{0}, input={1}")
    @MethodSource("ordinaryCases")
    void readsOrdinarySeeds(String name, int chunk) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(fixture(name), chunk))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
            assertFalse(reader.next());
        }
    }

    /// Forces unchanged local records to survive an update while preserving their independent content digests.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void copiesOriginalRecordsDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve(name);
        Files.copy(fixture(name), copy);
        var original = ArchiveCorpusAssertions.readZipReference(copy);
        byte[] added = "additional entry\n".getBytes(StandardCharsets.US_ASCII);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.txt"), added);
        }
        var rewritten = ArchiveCorpusAssertions.readZipReference(copy);
        ArchiveCorpusAssertions.assertEquivalentEntries(
                rewritten.stream().filter(entry -> !entry.path().equals("added.txt")).toList(), original);
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            assertArrayEquals(added, Files.readAllBytes(fileSystem.getPath("/added.txt")));
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), rewritten);
        }
    }

    /// Supplies each ordinary archive once to the update regression.
    private static Stream<String> ordinaryArchives() {
        return ORDINARY.stream();
    }

    /// Supplies independent CPython and libarchive digests for methods absent from the Commons ZIP decoder.
    private static Stream<Arguments> compressedSeeds() {
        return Stream.of(
                Arguments.of("lzma.zip", "lenna.jpg", 14, 29329,
                        "03ee102a086f1ff5e34b38941c6d9c0e290e276bf17b3ccf055e4a24b10b5250"),
                Arguments.of("xz.zip", "alice29.txt", 95, 152089,
                        "7467306ee0feed4971260f3c87421154a05be571d944e9cb021a5713700c38f0"),
                Arguments.of("zstd.zip", "lorem.txt", 93, 453,
                        "1d24a6f85991d06ba5ac548f44af158cff334200e511007928863614f7f9f4c4"),
                // CPython's Zstandard decoder reads the unchanged body using the modern method identifier.
                Arguments.of("license_zstd.zip", "LICENSE", 20, 894,
                        "eb37439365f27aa153f9fd45422c931149f957ad656ab34df6063ce6b8e49d42"));
    }

    /// Validates complete decompression, method identifiers, and descriptor handling against reference digests.
    @ParameterizedTest(name = "{0}")
    @MethodSource("compressedSeeds")
    void readsOptionalCompression(String name, String entry, int method, int size, String sha256) throws Exception {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            Path path = fileSystem.getPath("/" + entry);
            assertEquals(method, Files.readAttributes(path, ZipArkivoEntryAttributes.class).compressionMethodId());
            verifyContent(Files.readAllBytes(path), size, sha256);
        }
        for (int chunk : new int[]{1, 127, 8192}) {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(fixture(name), chunk))) {
                assertTrue(reader.next());
                assertEquals(entry, reader.readAttributes().path());
                assertEquals(method, ((ZipArkivoEntryAttributes) reader.readAttributes()).compressionMethodId());
                try (var input = reader.openInputStream()) {
                    verifyContent(input.readNBytes(size + 1), size, sha256);
                    assertEquals(-1, input.read());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Checks both encrypted formats with an independent decoder and rejects wrong passwords through both APIs.
    @ParameterizedTest
    @ValueSource(strings = {"encrypted_pkcrypt.zip", "encrypted_wzaes.zip"})
    void decryptsSeedAndRejectsWrongPassword(String name) throws IOException {
        byte[] expected = "Hello, World!\n".getBytes(StandardCharsets.US_ASCII);
        try (var reference = new net.lingala.zip4j.ZipFile(fixture(name).toFile(), PASSWORD.toCharArray());
             var input = reference.getInputStream(reference.getFileHeader("foo"))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
        var options = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
                ArkivoPasswordProvider.fixed(PASSWORD.getBytes(StandardCharsets.US_ASCII)));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), options)) {
            Path path = fileSystem.getPath("/foo");
            assertEquals(name.equals("encrypted_pkcrypt.zip") ? ZipEncryption.ZIP_CRYPTO : ZipEncryption.WINZIP_AES_256,
                    Files.readAttributes(path, ZipArkivoEntryAttributes.class).encryption());
            assertArrayEquals(expected, Files.readAllBytes(path));
        }
        for (int chunk : new int[]{1, 127, 8192}) {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(fixture(name), chunk), options)) {
                assertTrue(reader.next());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(expected, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        }
        var wrong = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
                ArkivoPasswordProvider.fixed("wrong".getBytes(StandardCharsets.US_ASCII)));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), wrong)) {
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/foo")));
        }
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(fixture(name), wrong)) {
                assertTrue(reader.next());
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
    }

    /// Rejects a changed ciphertext or authentication byte even when the correct password is supplied.
    @ParameterizedTest
    @ValueSource(strings = {"encrypted_pkcrypt.zip", "encrypted_wzaes.zip"})
    void rejectsDamagedEncryptedBody(String name, @TempDir Path directory) throws IOException {
        byte[] bytes = Files.readAllBytes(fixture(name));
        try (var reference = new net.lingala.zip4j.ZipFile(fixture(name).toFile())) {
            var header = reference.getFileHeader("foo");
            int local = Math.toIntExact(header.getOffsetLocalHeader());
            int data = local + 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, local + 26))
                    + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, local + 28));
            bytes[data + Math.toIntExact(header.getCompressedSize()) - 1] ^= 1;
        }
        Path damaged = directory.resolve(name);
        Files.write(damaged, bytes);
        try (var reference = new net.lingala.zip4j.ZipFile(damaged.toFile(), PASSWORD.toCharArray())) {
            assertThrows(IOException.class, () -> {
                try (var input = reference.getInputStream(reference.getFileHeader("foo"))) {
                    input.readAllBytes();
                }
            });
        }
        var options = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
                ArkivoPasswordProvider.fixed(PASSWORD.getBytes(StandardCharsets.US_ASCII)));
        try (var fileSystem = ZipArkivoFileSystem.open(damaged, options)) {
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/foo")));
        }
        for (int chunk : new int[]{1, 127, 8192}) {
            assertThrows(IOException.class, () -> {
                try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(damaged, chunk), options)) {
                    assertTrue(reader.next());
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                }
            });
        }
    }

    /// Copies encrypted records without converting their encryption or losing descriptor boundaries.
    @ParameterizedTest
    @ValueSource(strings = {"encrypted_pkcrypt.zip", "encrypted_wzaes.zip"})
    void copiesEncryptedRecordDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve(name);
        Files.copy(fixture(name), copy);
        var password = ArkivoPasswordProvider.fixed(PASSWORD.getBytes(StandardCharsets.US_ASCII));
        try (var fileSystem = ZipArkivoFileSystem.update(copy,
                ZipArchiveOptions.UPDATE_DEFAULTS.withPasswordProvider(password))) {
            Files.write(fileSystem.getPath("/added.txt"), new byte[]{1, 2, 3});
        }
        try (var reference = new net.lingala.zip4j.ZipFile(copy.toFile(), PASSWORD.toCharArray())) {
            assertEquals(2, reference.getFileHeaders().size());
            var header = reference.getFileHeader("foo");
            assertTrue(header.isEncrypted());
            assertEquals(name.equals("encrypted_pkcrypt.zip")
                            ? net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
                            : net.lingala.zip4j.model.enums.EncryptionMethod.AES,
                    header.getEncryptionMethod());
            try (var input = reference.getInputStream(header)) {
                assertArrayEquals("Hello, World!\n".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
            }
            try (var input = reference.getInputStream(reference.getFileHeader("added.txt"))) {
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(copy, 1),
                ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password))) {
            assertTrue(reader.next());
            assertEquals("foo", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("Hello, World!\n".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
            }
            assertTrue(reader.next());
            assertEquals("added.txt", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Preserves Unix permissions and the entry comment when unchanged records are copied into an updated ZIP.
    @ParameterizedTest
    @ValueSource(strings = {"permissions.zip", "comments.zip"})
    void retainsMetadataDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve(name);
        Files.copy(fixture(name), copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added"), new byte[0]);
        }
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            if (name.equals("comments.zip")) {
                assertEquals("This is a file comment.",
                        Files.readAttributes(fileSystem.getPath("/foo"), ZipArkivoEntryAttributes.class).comment());
            } else {
                for (String mode : List.of("600", "604", "777")) {
                    String permissions = switch (mode) {
                        case "600" -> "rw-------";
                        case "604" -> "rw----r--";
                        default -> "rwxrwxrwx";
                    };
                    assertEquals(PosixFilePermissions.fromString(permissions),
                            Files.getPosixFilePermissions(fileSystem.getPath("/permission_" + mode + ".txt")));
                }
            }
        }
    }

    /// Resolves the archive-local link without treating the streaming local header as Unix metadata.
    @Test
    void readsSymlinkTargetAndBody() throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("infozip_symlinks.zip"))) {
            Path link = fileSystem.getPath("/symlink");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("textfile", Files.readSymbolicLink(link).toString());
            assertArrayEquals("sample text\n".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(link));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(fixture("infozip_symlinks.zip"), 1))) {
            List<String> names = new ArrayList<>();
            while (reader.next()) {
                String name = reader.readAttributes().path();
                names.add(name);
                assertFalse(reader.readAttributes().isSymbolicLink());
                try (var input = reader.openInputStream()) {
                    String expected = name.equals("textfile") ? "sample text\n" : "textfile";
                    assertEquals(expected, new String(input.readAllBytes(), StandardCharsets.US_ASCII));
                }
            }
            assertEquals(List.of("textfile", "symlink"), names);
        }
    }

    /// Rejects malformed records and mixed-separator traversal without enabling upstream recovery mode.
    @ParameterizedTest
    @ValueSource(strings = {"dot_dot_backslash_name.zip", "gh_739.zip", "gh_740.zip"})
    void rejectsUnsafeOrDamagedSeed(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInputStream(fixture(name), 1))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Keeps central-directory count validation separate from a streaming reader's valid local entry.
    @Test
    void rejectsIncorrectIndexCount() throws IOException {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture("incorrect_number_entries.zip"))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        try (var reader = ZipArkivoStreamingReader.open(fixture("incorrect_number_entries.zip"))) {
            assertTrue(reader.next());
            assertEquals("hello", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Retains PPMd ZIP metadata but rejects its unsupported byte stream with a method-specific error.
    @Test
    void rejectsZipPpmd() throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("ppmd.zip"))) {
            Path path = fileSystem.getPath("/lorem.txt");
            var attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            assertEquals(98, attributes.compressionMethodId());
            assertNull(attributes.compressionMethod());
            IOException failure = assertThrows(IOException.class, () -> Files.readAllBytes(path));
            assertEquals("Unsupported ZIP compression method: 98", failure.getMessage());
        }
        IOException failure = assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(fixture("ppmd.zip"))) {
                assertTrue(reader.next());
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
        assertEquals("Unsupported ZIP compression method: 98", failure.getMessage());
    }

    /// Checks an independently recorded length and SHA-256 rather than trusting only the archive's CRC.
    private static void verifyContent(byte[] bytes, int size, String sha256) throws Exception {
        assertEquals(size, bytes.length);
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    /// Resolves one unchanged seed from the verified source release.
    private static Path fixture(String name) {
        return root().resolve("test/fuzz/unzip_fuzzer_seed_corpus").resolve(name);
    }

    /// Returns the Gradle-prepared source corpus directory.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.minizip.testDataDirectory")));
    }

    /// Restricts bulk reads without adding seek support to the source stream.
    @NotNullByDefault
    private static final class ChunkedInputStream extends FilterInputStream {
        /// Maximum bytes returned by one bulk read.
        private final int chunk;

        /// Opens the immutable fixture for fragmented transport reads.
        private ChunkedInputStream(Path path, int chunk) throws IOException {
            super(Files.newInputStream(path));
            this.chunk = chunk;
        }

        /// Reads at most the configured chunk while preserving ordinary zero-length read semantics.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return in.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
