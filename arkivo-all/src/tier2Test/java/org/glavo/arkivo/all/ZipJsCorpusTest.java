// Copyright (c) 2026 Glavo
// Deflate64 boundary expectations adapted from zip.js; see LICENSES/ZipJs-BSD-3-Clause.txt.
// SPDX-License-Identifier: MPL-2.0 AND BSD-3-Clause

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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks selected zip.js regression files against independent decoders and upstream plaintext.
@NotNullByDefault
final class ZipJsCorpusTest {
    /// Unencrypted files supported by the Commons Compress reference decoder.
    private static final @Unmodifiable List<String> ORDINARY = List.of(
            "lorem.zip", "lorem-204.zip", "lorem-204-fast.zip", "lorem-204-max.zip", "lorem-204-superfast.zip",
            "lorem-204-attributes.zip", "lorem-204-comments.zip",
            "lorem-bzip2.zip", "lorem-big-bzip2.zip", "lorem-deflate64.zip", "long-match-deflate64.zip",
            "boundary-match-deflate64.zip", "lorem-securezip-deflate64.zip", "lorem-store.zip",
            "lorem-winzip-store.zip", "lorem-macos.zip", "lorem-non-ascii.zip", "lorem-utf8.zip",
            "lorem-zip64.zip", "lorem-zip64-data-descriptor.zip", "lorem-zstd.zip");

    /// Structural and payload corruptions which must not yield a successfully read indexed archive.
    private static final @Unmodifiable List<String> MALFORMED = List.of(
            "lorem-invalid-crc.zip", "lorem-invalid-uncompressed-size.zip", "malformed-aes-method.zip",
            "malformed-aes-strength.zip", "malformed-cd-offset-negative.zip", "malformed-cd-offset-past-eof.zip",
            "malformed-cd-truncated.zip", "malformed-eocd-too-short.zip", "malformed-local-header.zip",
            "malformed-multi-disk.zip", "malformed-split-signature.zip", "malformed-uncompressed-size-under.zip");

    /// Enumerates normal files without multiplying whole-archive update operations by input chunk size.
    private static Stream<String> ordinaryArchives() {
        return ORDINARY.stream();
    }

    /// Exercises local-header, compressed-body, and descriptor reads across short source reads.
    private static Stream<Arguments> fragmentedArchives() {
        return ORDINARY.stream().flatMap(name -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Supplies malformed files once each for full indexed reads.
    private static Stream<String> malformedArchives() {
        return MALFORMED.stream();
    }

    /// Supplies damage visible from local records without requiring the streaming reader to index central metadata.
    private static Stream<Arguments> malformedStreams() {
        return Stream.of("lorem-invalid-crc.zip", "lorem-invalid-uncompressed-size.zip", "malformed-aes-strength.zip")
                .flatMap(name -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Supplies the published fixture passwords, compression methods, and encryption formats.
    private static Stream<Arguments> encryptedArchives() {
        return Stream.of(
                Arguments.of("lorem-7z-zipcrypto.zip", "password", 8, ZipEncryption.ZIP_CRYPTO),
                Arguments.of("lorem-encrypted.zip", "password", 8, ZipEncryption.WINZIP_AES_256),
                Arguments.of("lorem-winzip-encrypted.zip", "lorem.txt", 8, ZipEncryption.WINZIP_AES_256),
                Arguments.of("lorem-secure-traditional.zip", "lorem.txt", 8, ZipEncryption.ZIP_CRYPTO),
                Arguments.of("lorem-bzip2-aes.zip", "lorem.txt", 12, ZipEncryption.WINZIP_AES_256),
                Arguments.of("lorem-lzma-aes.zip", "lorem.txt", 14, ZipEncryption.WINZIP_AES_256));
    }

    /// Accounts for the selected subset rather than claiming coverage of every zip.js fixture or API test.
    @Test
    void accountsForSelectedFixtures() throws IOException {
        Set<String> expected = new HashSet<>(ORDINARY);
        expected.addAll(MALFORMED);
        encryptedArchives().forEach(arguments -> expected.add((String) arguments.get()[0]));
        expected.addAll(List.of("lorem.txt", "lorem-204-label.zip", "lorem-204-sfx.exe", "lorem-204-sfx-junior.exe",
                "lorem-lzma.zip", "lorem-lzma-eos.zip", "lorem-split.zip",
                "random-204-span.z01", "random-204-span.zip"));
        for (int disk = 1; disk <= 7; disk++) expected.add("lorem-split.z0" + disk);
        try (var files = Files.list(fixture(""))) {
            assertEquals(expected, files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        for (String name : List.of("LICENSE", "UPSTREAM.properties", "tests/all/test-pkzip204-archives.js",
                "tests/all/test-deflate64-length-code-285.js", "tests/all/test-deflate64-buffer-boundary.js")) {
            assertTrue(Files.size(root().resolve(name)) > 0, name);
        }
    }

    /// Compares complete decoded bodies and central-directory metadata with Commons Compress.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void readsIndexedArchives(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name));
             var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setPath(fixture(name)).setCharset("IBM437").get()) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
            var entries = reference.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var actual = Files.readAttributes(fileSystem.getPath("/" + entry.getName()), ZipArkivoEntryAttributes.class);
                assertEquals(entry.getSize(), actual.size());
                assertEquals(entry.getCompressedSize(), actual.compressedSize());
                assertEquals(entry.getCrc(), actual.crc32());
                assertEquals(entry.getMethod(), actual.compressionMethodId());
                assertEquals(entry.getExternalAttributes(), actual.externalAttributes());
                assertArrayEquals(entry.getRawName(), actual.rawPath());
            }
        }
    }

    /// Verifies every physical entry through a non-seekable source, including the final end-of-archive transition.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("fragmentedArchives")
    void readsFragmentedArchives(String name, int chunk) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
            assertFalse(reader.next());
        }
    }

    /// Checks preservation through raw-record copying with an independent decoder after the rewrite.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void preservesOriginalEntriesDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture(name), copy);
        var expected = ArchiveCorpusAssertions.readZipReference(copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{17, 31, 63});
        }
        var rewritten = ArchiveCorpusAssertions.readZipReference(copy);
        assertEquals(expected.size() + 1, rewritten.size());
        assertEquals(expected, rewritten.stream().filter(entry -> !entry.path().equals("added.bin")).toList());
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), rewritten);
            assertArrayEquals(new byte[]{17, 31, 63}, Files.readAllBytes(fileSystem.getPath("/added.bin")));
        }
    }

    /// Reads the original PKZIP executable containers as data without executing their prefixed programs.
    @ParameterizedTest
    @ValueSource(strings = {"lorem-204-sfx.exe", "lorem-204-sfx-junior.exe"})
    void readsSelfExtractingArchives(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        assertEquals(1, expected.size());
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
    }

    /// Retains strict indexed flag matching for PKZIP's reserved local bit while reading its independent local records.
    @Test
    void distinguishesPkzipVolumeLabelFlagMismatch(@TempDir Path directory) throws IOException {
        Path source = fixture("lorem-204-label.zip");
        var expected = ArchiveCorpusAssertions.readZipReference(source);
        assertEquals(2, expected.size());
        byte[] bytes = Files.readAllBytes(source);
        assertEquals(0x8000, Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 6)));
        try (var fileSystem = ZipArkivoFileSystem.open(source)) {
            IOException failure = assertThrows(IOException.class, () ->
                    Files.readAttributes(fileSystem.getPath("/LOREM.TXT"), ZipArkivoEntryAttributes.class));
            assertEquals("ZIP local header flags do not match central directory", failure.getMessage());
        }
        Path copy = directory.resolve("label.zip");
        Files.copy(source, copy);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertArrayEquals(bytes, Files.readAllBytes(copy));
        for (int chunk : new int[]{1, 7, 8192}) {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(source, chunk))) {
                assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
            }
        }
    }

    /// Resolves volume-local offsets for both modern split ZIPs and PKZIP 2.04's stored spanning file.
    @ParameterizedTest
    @CsvSource({"lorem-split.zip,4", "random-204-span.zip,1"})
    void readsSplitArchives(String name, int count) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        assertEquals(count, expected.size());
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name));
             var reader = ZipArkivoStreamingReader.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
        }
    }

    /// Retains PKZIP's compression-level bits rather than confusing them with encryption or descriptor flags.
    @ParameterizedTest
    @CsvSource({"lorem-204-max.zip,2", "lorem-204-fast.zip,4", "lorem-204-superfast.zip,6"})
    void retainsCompressionLevelFlags(String name, int flags) throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name));
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 1))) {
            var indexed = Files.readAttributes(fileSystem.getPath("/LOREM.TXT"), ZipArkivoEntryAttributes.class);
            assertEquals(flags, indexed.generalPurposeFlags());
            assertEquals(8, indexed.compressionMethodId());
            assertTrue(reader.next());
            assertEquals(flags, reader.readAttributes(ZipArkivoEntryAttributes.class).generalPurposeFlags());
        }
    }

    /// Decodes the original CP437 comment and preserves distinct DOS attribute combinations.
    @Test
    void retainsPkzipCommentsAndDosAttributes() throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("lorem-204-comments.zip"))) {
            var attributes = Files.readAttributes(fileSystem.getPath("/LOREM.TXT"), ZipArkivoEntryAttributes.class);
            assertEquals("Comentario: Café ñoño ▓▒░", attributes.comment());
            assertEquals(25, Objects.requireNonNull(attributes.rawComment()).length);
        }
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("lorem-204-attributes.zip"))) {
            for (var entry : Map.of("HIDDEN.TXT", 0x22L, "SYSTEM.TXT", 0x24L,
                    "RDONLY.TXT", 0x21L, "PLAIN.TXT", 0x20L).entrySet()) {
                var attributes = Files.readAttributes(fileSystem.getPath("/" + entry.getKey()), ZipArkivoEntryAttributes.class);
                assertEquals(entry.getValue().longValue(), attributes.externalAttributes());
            }
        }
    }

    /// Checks maximal Deflate64 matches independently of the reference decoder, including an exact 64 KiB boundary.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192, 65536})
    void retainsLongMatchesAcrossOutputBoundaries(int chunk) throws IOException {
        for (String name : List.of("long-match-deflate64.zip", "boundary-match-deflate64.zip")) {
            byte[] expected = (name.startsWith("long") ? "a".repeat(1 + 65538 + 1000)
                    : "A".repeat(1 + 65535 + 65538) + "B".repeat(1000)).getBytes(StandardCharsets.US_ASCII);
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 7))) {
                assertTrue(reader.next());
                assertEquals(9, reader.readAttributes(ZipArkivoEntryAttributes.class).compressionMethodId());
                try (var input = reader.openInputStream()) {
                    int offset = 0;
                    byte[] buffer = new byte[chunk];
                    while (offset < expected.length) {
                        int length = input.read(buffer, 0, Math.min(buffer.length, expected.length - offset));
                        assertTrue(length > 0);
                        assertEquals(-1, java.util.Arrays.mismatch(expected, offset, offset + length, buffer, 0, length));
                        offset += length;
                    }
                    assertEquals(-1, input.read());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Uses upstream plaintext to verify ZIP LZMA with and without its end-marker flag.
    @ParameterizedTest
    @ValueSource(strings = {"lorem-lzma.zip", "lorem-lzma-eos.zip"})
    void readsLzmaPlaintext(String name) throws IOException {
        assertPlaintext(name, ZipArchiveOptions.READ_DEFAULTS, 14, ZipEncryption.NONE);
    }

    /// Rejects a wrong password and then reads the unchanged fixture with the documented password.
    @ParameterizedTest
    @MethodSource("encryptedArchives")
    void readsEncryptedPlaintext(String name, String password, int method, ZipEncryption encryption) throws IOException {
        var wrong = passwordOptions("incorrect-password");
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), wrong)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 1), wrong)) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
        assertPlaintext(name, passwordOptions(password), method, encryption);
    }

    /// Requires damaged metadata or payloads to fail even when indexing itself is lazy.
    @ParameterizedTest
    @MethodSource("malformedArchives")
    void rejectsMalformedIndexedArchives(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), passwordOptions("p"))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Rejects bad payload checksums, local sizes, and AES strengths with fragmented non-seekable input.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("malformedStreams")
    void rejectsMalformedStreamingArchives(String name, int chunk) {
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk), passwordOptions("p"))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Reads intact local data without treating an unread central size as a streaming metadata source.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void distinguishesLocalAndCentralSizes(int chunk) throws IOException {
        Path source = fixture("malformed-uncompressed-size-under.zip");
        byte[] bytes = Files.readAllBytes(source);
        assertEquals(1162, ByteArrayAccess.readIntLittleEndian(bytes, 22));
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(source).get()) {
            assertEquals(2162, reference.getEntries().nextElement().getSize());
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(source, chunk))) {
            assertTrue(reader.next());
            assertEquals(1162, reader.readAttributes().size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(Files.readAllBytes(fixture("lorem.txt")), input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Compares a single-file archive with its uncompressed source through indexed and fragmented streaming reads.
    private static void assertPlaintext(String name, ZipArchiveOptions.Read options, int method,
                                        ZipEncryption encryption) throws IOException {
        byte[] expected = Files.readAllBytes(fixture("lorem.txt"));
        assertEquals(1162, expected.length);
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), options)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/lorem.txt")));
            var attributes = Files.readAttributes(fileSystem.getPath("/lorem.txt"), ZipArkivoEntryAttributes.class);
            assertEquals(method, attributes.compressionMethodId());
            assertEquals(encryption, attributes.encryption());
        }
        for (int chunk : new int[]{1, 7, 8192}) {
            try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk), options)) {
                assertTrue(reader.next());
                assertEquals("lorem.txt", reader.readAttributes().path());
                assertEquals(method, reader.readAttributes(ZipArkivoEntryAttributes.class).compressionMethodId());
                assertEquals(encryption, reader.readAttributes(ZipArkivoEntryAttributes.class).encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(expected, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Supplies the non-secret ASCII password used by an upstream fixture.
    private static ZipArchiveOptions.Read passwordOptions(String password) {
        return ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
                ArkivoPasswordProvider.fixed(password.getBytes(StandardCharsets.US_ASCII)));
    }

    /// Resolves an unchanged file from the verified, selected corpus.
    private static Path fixture(String name) {
        return root().resolve("tests/data").resolve(name);
    }

    /// Returns the source directory prepared by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.zipjs.testDataDirectory")));
    }

    /// Limits source reads without changing the underlying ZIP bytes or buffering past entry boundaries.
    @NotNullByDefault
    private static final class ChunkedInput extends FilterInputStream {
        /// Maximum bytes returned by one bulk read.
        private final int chunk;

        /// Opens an upstream fixture with a bounded read size.
        private ChunkedInput(Path path, int chunk) throws IOException {
            super(Files.newInputStream(path));
            this.chunk = chunk;
        }

        /// Reads at most the configured number of bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return in.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
