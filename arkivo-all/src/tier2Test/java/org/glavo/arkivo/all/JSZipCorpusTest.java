// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
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
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads unchanged JSZip reference archives produced by desktop and command-line ZIP tools.
@NotNullByDefault
final class JSZipCorpusTest {
    /// Unencrypted archives, including document containers and a prefixed copy of the decoded ZIP64 archive.
    private static final @Unmodifiable List<String> ORDINARY = List.of(
            "all-stream.zip", "all.7zip.zip", "all.windows.zip", "all.zip", "all_prepended_bytes.zip",
            "archive_comment.zip", "backslash.zip", "data_descriptor.zip", "deflate-stream.zip", "deflate.zip",
            "empty.zip", "extra_attributes.zip", "folder.zip", "image.zip", "nested.zip",
            "nested_data_descriptor.zip", "nested_zip64.zip", "pile_of_poo.zip", "pollution.zip",
            "store-stream.zip", "store.zip", "subfolder.zip", "text.zip",
            "utf8.zip", "utf8_in_name.zip", "winrar_utf8_in_name.zip", "zip64_prepended_bytes.zip", "zip64.zip",
            "permissions/linux_7z.zip", "permissions/linux_ark.zip", "permissions/linux_file_roller-ubuntu.zip",
            "permissions/linux_file_roller-xubuntu.zip", "permissions/linux_zip.zip", "permissions/mac_finder.zip",
            "permissions/windows_7z.zip", "permissions/windows_compressed_folders.zip",
            "permissions/windows_izarc.zip", "permissions/windows_winrar.zip",
            "complex_files/AntarcticaTemps.ods", "complex_files/AntarcticaTemps.xlsx",
            "complex_files/Franz Kafka - The Metamorphosis.epub", "complex_files/Outlook2007_Calendar.xps",
            "extra_filed_non_standard.zip");

    /// Damaged fixtures whose complete indexed contents cannot be read successfully.
    private static final @Unmodifiable List<String> MALFORMED = List.of(
            "all_missing_bytes.zip", "zip64_missing_bytes.zip", "invalid/bad_offset.zip",
            "invalid/bad_decompressed_size.zip", "invalid/compression.zip", "invalid/crc32.zip");

    /// Fragments ordinary ZIP streams without treating the APK signing block as a ZIP record.
    private static Stream<Arguments> readCases() {
        return ORDINARY.stream().filter(name -> !name.equals("extra_filed_non_standard.zip"))
                .flatMap(name -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Supplies archives once each for complete-rewrite checks.
    private static Stream<String> ordinaryArchives() {
        return ORDINARY.stream();
    }

    /// Supplies the upstream invalid-input regressions.
    private static Stream<String> malformedArchives() {
        return MALFORMED.stream();
    }

    /// Accounts for every extracted fixture and keeps the original assertions and license available for review.
    @Test
    void accountsForExtractedFixtures() throws IOException {
        Set<String> expected = new HashSet<>(ORDINARY);
        expected.addAll(MALFORMED);
        expected.addAll(Set.of("all_appended_bytes.zip", "zip64_appended_bytes.zip", "encrypted.zip",
                "local_encoding_in_name.zip", "slashes_and_izarc.zip"));
        assertEquals(54, expected.size());
        try (var files = Files.walk(fixture(""))) {
            assertEquals(expected, files.filter(Files::isRegularFile)
                    .map(path -> fixture("").relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toSet()));
        }
        for (String name : List.of("LICENSE.markdown", "UPSTREAM.properties", "test/asserts/load.js",
                "test/asserts/permissions.js", "test/asserts/unicode.js")) {
            assertTrue(Files.size(root().resolve(name)) > 0, name);
        }
    }

    /// Compares all indexed file bodies, explicit directories, and metadata with an independent decoder.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void readsOriginalIndexedArchives(String name) throws IOException {
        var expected = referenceEntries(referenceFixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
            assertIndexedMetadata(referenceFixture(name), fileSystem);
        }
    }

    /// Compares streamed bodies, directory entries, and physical entry ordering with an independent decoder.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("readCases")
    void readsOriginalArchives(String name, int chunk) throws IOException {
        var expected = referenceEntries(referenceFixture(name));
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), chunk))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
        }
    }

    /// Reads every APK payload before rejecting the non-ZIP signing block rather than silently accepting a suffix.
    @ParameterizedTest
    @ValueSource(ints = {8192, 65536})
    void readsApkEntriesBeforeUnsupportedSigningBlock(int chunk) throws IOException {
        Path source = fixture("extra_filed_non_standard.zip");
        try (var file = new RandomAccessFile(source.toFile(), "r")) {
            byte[] end = new byte[22];
            file.seek(file.length() - end.length);
            file.readFully(end);
            assertEquals(0x06054b50, ByteArrayAccess.readIntLittleEndian(end, 0));
            assertEquals(0, ByteArrayAccess.readShortLittleEndian(end, 20));
            long centralOffset = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(end, 16));
            byte[] footer = new byte[24];
            file.seek(centralOffset - footer.length);
            file.readFully(footer);
            assertArrayEquals("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII),
                    Arrays.copyOfRange(footer, 8, footer.length));
            long blockSize = ByteArrayAccess.readLongLittleEndian(footer, 0);
            assertEquals(4088, blockSize);
            file.seek(centralOffset - blockSize - Long.BYTES);
            byte[] header = new byte[Long.BYTES];
            file.readFully(header);
            assertEquals(blockSize, ByteArrayAccess.readLongLittleEndian(header, 0));
        }
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(source).get();
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(source, chunk))) {
            var entries = reference.getEntriesInPhysicalOrder();
            int count = 0;
            while (entries.hasMoreElements()) {
                var expected = entries.nextElement();
                assertTrue(reader.next(), expected.getName());
                assertEquals(expected.getName(), reader.readAttributes().path());
                try (var input = reader.openInputStream(); var referenceInput = reference.getInputStream(expected)) {
                    assertArrayEquals(referenceInput.readAllBytes(), input.readAllBytes(), expected.getName());
                }
                count++;
            }
            assertEquals(1435, count);
            IOException failure = assertThrows(IOException.class, reader::next);
            assertEquals("Unexpected ZIP stream record signature: ff8", failure.getMessage());
        }
    }

    /// Preserves document parts, implicit directories, and the first physical record through an update.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "AntarcticaTemps.ods|20|27|META-INF/manifest.xml|application/vnd.oasis.opendocument.spreadsheet",
            "AntarcticaTemps.xlsx|17|27|[Content_Types].xml|application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "Franz Kafka - The Metamorphosis.epub|26|26|mimetype|application/epub+zip",
            "Outlook2007_Calendar.xps|15|23|[Content_Types].xml|application/vnd.ms-package.xps-fixeddocument+xml"
    })
    void preservesDocumentContainerStructure(String name, int entryCount, int pathCount, String part,
                                             String contentType, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve(name);
        Files.copy(fixture("complex_files/" + name), copy);
        String firstName;
        int firstMethod;
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(copy).get()) {
            var first = reference.getEntriesInPhysicalOrder().nextElement();
            firstName = first.getName();
            firstMethod = first.getMethod();
        }
        for (int pass = 0; pass < 2; pass++) {
            try (var fileSystem = ZipArkivoFileSystem.open(copy);
                 var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(copy).get()) {
                var entries = reference.getEntriesInPhysicalOrder();
                var first = entries.nextElement();
                assertEquals(firstName, first.getName());
                assertEquals(firstMethod, first.getMethod());
                int count = 1;
                while (entries.hasMoreElements()) {
                    entries.nextElement();
                    count++;
                }
                assertEquals(entryCount + pass, count);
                try (var paths = Files.walk(fileSystem.getPath("/"))) {
                    assertEquals(pathCount + pass + 1L, paths.count());
                }
                assertTrue(Files.readString(fileSystem.getPath("/" + part), StandardCharsets.UTF_8).contains(contentType));
            }
            if (pass == 0) {
                try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
                    Files.write(fileSystem.getPath("/added.bin"), new byte[]{1});
                }
            }
        }
    }

    /// Preserves original records and external attributes while adding an entry through an update session.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void preservesOriginalEntriesDuringUpdate(String name, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture(name), copy);
        var original = referenceEntries(referenceFixture(name));
        byte[] added = {11, 23, 47};
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.bin"), added);
        }
        var rewritten = referenceEntries(copy);
        ArchiveCorpusAssertions.assertEquivalentEntries(
                rewritten.stream().filter(entry -> !entry.path().equals("added.bin")).toList(), original);
        try (var fileSystem = ZipArkivoFileSystem.open(copy);
             var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setPath(referenceFixture(name)).setCharset("IBM437").get()) {
            assertArrayEquals(added, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            var entries = reference.getEntries();
            while (entries.hasMoreElements()) {
                var expected = entries.nextElement();
                var actual = Files.readAttributes(fileSystem.getPath("/" + expected.getName().replace('\\', '/')),
                        ZipArkivoEntryAttributes.class);
                assertEquals(expected.getExternalAttributes(), actual.externalAttributes(), expected.getName());
                assertEquals(expected.getInternalAttributes(), actual.internalAttributes(), expected.getName());
                assertArrayEquals(expected.getRawName(), actual.rawPath(), expected.getName());
                assertEquals(Objects.requireNonNullElse(expected.getComment(), ""),
                        Objects.requireNonNullElse(actual.comment(), ""), expected.getName());
            }
        }
    }

    /// Checks the upstream comment example before and after a rewrite, including the archive-level comment.
    @Test
    void preservesArchiveAndEntryComments(@TempDir Path directory) throws IOException {
        Path copy = directory.resolve("comment.zip");
        Files.copy(fixture("archive_comment.zip"), copy);
        for (int pass = 0; pass < 2; pass++) {
            try (var reference = new java.util.zip.ZipFile(copy.toFile());
                 var fileSystem = ZipArkivoFileSystem.open(copy)) {
                assertEquals("file comment", reference.getComment());
                assertEquals("entry comment", reference.getEntry("Hello.txt").getComment());
                assertEquals("entry comment", Files.readAttributes(fileSystem.getPath("/Hello.txt"),
                        ZipArkivoEntryAttributes.class).comment());
            }
            if (pass == 0) {
                try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
                    Files.write(fileSystem.getPath("/added.bin"), new byte[]{1});
                }
            }
        }
    }

    /// Compares encoded names and central metadata independently of body decoding.
    private static void assertIndexedMetadata(Path source, ZipArkivoFileSystem fileSystem) throws IOException {
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setPath(source).setCharset("IBM437").get()) {
            var entries = reference.getEntries();
            while (entries.hasMoreElements()) {
                var expected = entries.nextElement();
                var actual = Files.readAttributes(fileSystem.getPath("/" + expected.getName().replace('\\', '/')),
                        ZipArkivoEntryAttributes.class);
                assertArrayEquals(expected.getRawName(), actual.rawPath(), expected.getName());
                assertEquals(expected.getMethod(), actual.compressionMethodId(), expected.getName());
                assertEquals(expected.getRawFlag(), actual.generalPurposeFlags(), expected.getName());
                assertEquals(expected.getVersionMadeBy(), actual.versionMadeBy(), expected.getName());
                assertEquals(expected.getVersionRequired(), actual.versionNeededToExtract(), expected.getName());
                assertEquals(expected.getExternalAttributes(), actual.externalAttributes(), expected.getName());
                assertEquals(expected.getInternalAttributes(), actual.internalAttributes(), expected.getName());
                assertEquals(expected.getCompressedSize(), actual.compressedSize(), expected.getName());
                assertEquals(expected.getCrc(), actual.crc32(), expected.getName());
                assertEquals(Objects.requireNonNullElse(expected.getComment(), ""),
                        Objects.requireNonNullElse(actual.comment(), ""), expected.getName());
            }
        }
    }

    /// Retains strict local/central name agreement even for an archive accepted by JSZip.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void rejectsInconsistentIzarcNamesButReadsLocalStream(int chunk, @TempDir Path directory) throws IOException {
        Path source = fixture("slashes_and_izarc.zip");
        try (var fileSystem = ZipArkivoFileSystem.open(source)) {
            IOException failure = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/test/Hello.txt")));
            assertEquals("ZIP local header name does not match central directory", failure.getMessage());
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(source, chunk))) {
            assertEquals(referenceEntries(source), ArchiveCorpusAssertions.readStreaming(reader));
        }
        Path copy = directory.resolve("inconsistent.zip");
        Files.copy(source, copy);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
                Files.write(fileSystem.getPath("/added.bin"), new byte[]{1});
            }
        });
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(copy));
    }

    /// Rejects damaged indexed records without treating metadata-only enumeration as complete validation.
    @ParameterizedTest
    @MethodSource("malformedArchives")
    void rejectsDamagedArchives(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Distinguishes strict indexed archive extents from the forward-only reader's logical ZIP boundary.
    @ParameterizedTest
    @ValueSource(strings = {"all_appended_bytes.zip", "zip64_appended_bytes.zip"})
    void handlesAppendedCallerBytes(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture(name), 7))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
        }
    }

    /// Requires a password for the encrypted fixture rather than silently returning encrypted bytes.
    @Test
    void rejectsEncryptedContentWithoutPassword() {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture("encrypted.zip"))) {
                Files.readAllBytes(fileSystem.getPath("/Hello.txt"));
            }
        });
    }

    /// Uses the upstream CP866 filename encoding without relying on host defaults.
    @Test
    void readsLegacyRussianNames() throws IOException {
        Charset charset = Charset.forName("IBM866");
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyCharsetDetector(
                ArchiveMetadataCharsetDetector.fixed(charset));
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setPath(fixture("local_encoding_in_name.zip")).setCharset(charset).get();
             var fileSystem = ZipArkivoFileSystem.open(fixture("local_encoding_in_name.zip"), options);
             var reader = ZipArkivoStreamingReader.open(new ChunkedInput(fixture("local_encoding_in_name.zip"), 1), options)) {
            var entries = reference.getEntriesInPhysicalOrder();
            int count = 0;
            while (entries.hasMoreElements()) {
                var expected = entries.nextElement();
                assertTrue(reader.next());
                String path = expected.getName().replaceAll("/$", "");
                assertEquals(expected.getName(), reader.readAttributes().path());
                assertEquals(expected.isDirectory(), Files.isDirectory(fileSystem.getPath("/" + path)));
                if (!expected.isDirectory()) {
                    byte[] bytes;
                    try (var input = reference.getInputStream(expected)) {
                        bytes = input.readAllBytes();
                    }
                    assertArrayEquals(bytes, Files.readAllBytes(fileSystem.getPath("/" + path)));
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(bytes, input.readAllBytes());
                    }
                }
                count++;
            }
            assertEquals(2, count);
            assertFalse(reader.next());
        }
    }

    /// Decodes independently and applies Arkivo's documented separator normalization to logical paths.
    private static @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> referenceEntries(Path path) throws IOException {
        return ArchiveCorpusAssertions.readZipReference(path).stream()
                .map(entry -> new ArchiveCorpusAssertions.EntryDigest(entry.path().replace('\\', '/'),
                        entry.directory(), entry.symbolicLink(), entry.size(), entry.crc32(), entry.sha256()))
                .toList();
    }

    /// Verifies and removes only the known prepended bytes for the reference decoder's ZIP64 limitation.
    private static Path referenceFixture(String name) throws IOException {
        if (name.equals("zip64_prepended_bytes.zip")) {
            byte[] prefixed = Files.readAllBytes(fixture(name));
            byte[] prefix = "Hello World\n".getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(prefix, Arrays.copyOf(prefixed, prefix.length));
            Path original = fixture("zip64.zip");
            assertArrayEquals(Files.readAllBytes(original), Arrays.copyOfRange(prefixed, prefix.length, prefixed.length));
            return original;
        }
        return fixture(name);
    }

    /// Resolves an unchanged fixture extracted by Gradle.
    private static Path fixture(String name) {
        return root().resolve("test/ref").resolve(name);
    }

    /// Returns the verified source release directory.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.jszip.testDataDirectory")));
    }

    /// Fragments a file-backed source without buffering or seeking.
    @NotNullByDefault
    private static final class ChunkedInput extends FilterInputStream {
        /// Maximum bytes returned by a bulk read.
        private final int chunk;

        /// Opens a fixture with the selected read granularity.
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
