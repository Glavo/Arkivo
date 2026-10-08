// Copyright (c) 2026 Glavo
// Scenarios adapted from .NET's ZIP tests; see LICENSES/DotNet-MIT.txt.
// SPDX-License-Identifier: MPL-2.0 AND MIT

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/// Checks .NET's ZIP compatibility files and original before/after update trees.
@NotNullByDefault
final class DotNetZipCorpusTest {
    /// Archives with independently decodable entries and no deliberate namespace or structural disagreement.
    private static final @Unmodifiable List<String> ORDINARY = List.of(
            "refzipfiles/empty.zip", "refzipfiles/emptydir.zip", "refzipfiles/explicitdir1.zip",
            "refzipfiles/explicitdir2.zip", "refzipfiles/fake64.zip", "refzipfiles/large.zip",
            "refzipfiles/noexplicitdir.zip", "refzipfiles/normal.zip", "refzipfiles/prepended.zip",
            "refzipfiles/small.zip", "refzipfiles/unicode.zip", "badzipfiles/invaliddate.zip",
            "compat/NullCharFileName_FromUnix.zip", "compat/NullCharFileName_FromWindows.zip",
            "compat/7zip.zip", "compat/InvalidWindowsFileNameChars.zip", "compat/Linux_RWXRW_R__.zip",
            "compat/Linux_RW_RW_R__.zip", "compat/OSX_RWXRW_R__.zip", "compat/WindowsInvalid_FromUnix.zip",
            "compat/WindowsInvalid_FromWindows.zip", "compat/deflate64.zip", "compat/dotnetzipstreaming.zip",
            "compat/excel.xlsx", "compat/net45_normal.zip", "compat/net45_unicode.zip", "compat/net46_normal.zip",
            "compat/net46_unicode.zip", "compat/packaging.package", "compat/powerpoint.pptx", "compat/sharpziplib.zip",
            "compat/silverlight.xap", "compat/windows.zip", "compat/word.docx", "compat/xceedstreaming.zip",
            "StrangeZipFiles/dataDescriptor.zip", "StrangeZipFiles/normalVerySmall.zip",
            "StrangeZipFiles/largetrailingwhitespacedeflation.zip", "StrangeZipFiles/extradata/emptyWith64KBComment.zip",
            "StrangeZipFiles/extradata/extraDataLHandCDentryAndArchiveComments.zip",
            "StrangeZipFiles/extradata/extraDataThenZip64.zip", "StrangeZipFiles/extradata/zip64ThenExtraData.zip");

    /// Archives rejected by Arkivo's full indexed validation, including stricter namespace and local-header rules.
    private static final @Unmodifiable List<String> REJECTED = List.of(
            "badzipfiles/CDoffsetInBoundsWrong.zip", "badzipfiles/CDoffsetOutOfBounds.zip", "badzipfiles/EOCDmissing.zip",
            "badzipfiles/HuffmanTreeException.zip", "badzipfiles/compressedSizeOutOfBounds.zip",
            "badzipfiles/invalidDeflate.zip", "badzipfiles/localFileHeaderSignatureWrong.zip",
            "badzipfiles/localFileOffsetOutOfBounds.zip", "badzipfiles/nameDuplicates.zip", "badzipfiles/nameEmpty.zip",
            "badzipfiles/nameRooted.zip", "badzipfiles/numberOfEntriesDifferent.zip", "badzipfiles/NORMAL.zip",
            "badzipfiles/tttt.zip",
            "StrangeZipFiles/filenameTimeAndSizesDifferentInLH.zip", "refzipfiles/appended.zip");

    /// Other fixtures receive explicit checks instead of being silently omitted from the inventory.
    private static final @Unmodifiable List<String> SPECIAL = List.of(
            "badzipfiles/LZMA.zip", "badzipfiles/nameSlashedAndNonzero.zip",
            "compat/backslashes_FromUnix.zip", "compat/backslashes_FromWindows.zip",
            "refzipfiles/encrypted_entries_aes256.zip", "refzipfiles/encrypted_entries_mixed.zip",
            "refzipfiles/encrypted_entries_weak.zip", "StrangeZipFiles/fileLengthGreaterIntLessUInt.zip",
            "StrangeZipFiles/veryLarge.zip");

    /// Finite budgets for corrupt files and intentionally oversized entries.
    private static final ZipArchiveOptions.Read BOUNDED = new ZipArchiveOptions.Read(
            ArchiveReadOptions.DEFAULT.withLimits(ArchiveReadLimits.builder().maximumEntryCount(256)
                    .maximumEntrySize(8L << 20).maximumTotalEntrySize(16L << 20)
                    .maximumMetadataSize(1L << 20).maximumCompressionWindowSize(8L << 20)
                    .maximumDecoderMemorySize(16L << 20).build()));

    /// Lists all ordinary fixture paths.
    private static Stream<String> ordinaryArchives() {
        return ORDINARY.stream();
    }

    /// Uses one-byte reads for small fixtures and bounded chunks for megabyte-scale binary samples.
    private static Stream<Arguments> streamingCases() {
        return ORDINARY.stream().flatMap(name -> {
            try {
                int shortRead = Files.size(fixture(name)) > 128 * 1024 ? 127 : 1;
                return Stream.of(shortRead, 8192).flatMap(chunk -> Stream.of(0, 1, 2)
                        .map(mode -> Arguments.of(name, chunk, mode)));
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
    }

    /// Includes every downloaded ZIP or ZIP-based document in exactly one tested category.
    @Test
    void accountsForEveryArchive() throws IOException {
        Set<String> expected = new HashSet<>(ORDINARY);
        assertEquals(ORDINARY.size(), expected.size());
        for (String name : Stream.concat(REJECTED.stream(), SPECIAL.stream()).toList()) assertTrue(expected.add(name), name);
        try (var files = Files.walk(fixture(""))) {
            Set<String> actual = files.filter(Files::isRegularFile)
                    .map(path -> fixture("").relativize(path).toString().replace('\\', '/'))
                    .filter(name -> !name.startsWith("modified/") && !name.startsWith("refzipfolders/"))
                    .collect(Collectors.toSet());
            assertEquals(expected, actual);
        }
        Path sources = Path.of(Objects.requireNonNull(System.getProperty("arkivo.dotnet-runtime.testDataDirectory")));
        assertTrue(Files.size(sources.resolve("src/libraries/System.IO.Compression/tests/ZipArchive/zip_UpdateTests.cs")) > 0);
    }

    /// Compares full indexed contents, then copies every original entry through an update session.
    @ParameterizedTest
    @MethodSource("ordinaryArchives")
    void readsAndRewritesArchive(String name, @TempDir Path directory) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture(name), copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{1, 3, 7});
        }
        var actual = ArchiveCorpusAssertions.readZipReference(copy);
        assertEquals(expected.size() + 1, actual.size());
        ArchiveCorpusAssertions.assertEquivalentEntries(actual.stream().filter(entry -> !entry.path().equals("added.bin"))
                .toList(), expected);
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            assertArrayEquals(new byte[]{1, 3, 7}, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), actual);
        }
    }

    /// Advances after unopened, partially read, or fully consumed entries through a fragmented transport.
    @ParameterizedTest(name = "{0}, chunk={1}, mode={2}")
    @MethodSource("streamingCases")
    void readsStreamingArchive(String name, int chunk, int mode) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var raw = new ChunkedInput(fixture(name), chunk); var reader = ZipArkivoStreamingReader.open(raw)) {
            if (mode == 2) {
                assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
            } else {
                int index = 0;
                while (reader.next()) {
                    assertTrue(index < expected.size());
                    var attributes = reader.readAttributes();
                    assertEquals(expected.get(index).path(), attributes.path().replaceAll("/$", ""));
                    assertEquals(expected.get(index).directory(), attributes.isDirectory());
                    if (mode == 1 && attributes.isRegularFile()) {
                        try (var input = reader.openInputStream()) {
                            assertEquals(expected.get(index).size() == 0, input.read() < 0);
                        }
                    }
                    index++;
                }
                assertEquals(expected.size(), index);
            }
            assertFalse(reader.next());
        }
    }

    /// Supplies all structural and namespace rejections without swallowing implementation failures.
    private static Stream<String> rejectedArchives() {
        return REJECTED.stream();
    }

    /// Forces lazy local-header and body validation before accepting a malformed archive.
    @ParameterizedTest
    @MethodSource("rejectedArchives")
    void rejectsInvalidIndexedArchive(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), BOUNDED)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Refuses encrypted bodies when no password provider is configured.
    @ParameterizedTest
    @ValueSource(strings = {"aes256", "mixed", "weak"})
    void requiresPassword(String kind) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture("refzipfiles/encrypted_entries_" + kind + ".zip"), BOUNDED)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Accepts ZIP LZMA, which .NET 10 categorizes as unsupported rather than structurally corrupt.
    @Test
    void readsLzmaArchive() throws IOException {
        byte[] expected = Files.readString(fixture("refzipfolders/small/text.txt"))
                .replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("badzipfiles/LZMA.zip"));
             var raw = new ChunkedInput(fixture("badzipfiles/LZMA.zip"), 1);
             var reader = ZipArkivoStreamingReader.open(raw)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/text.txt")));
            assertTrue(reader.next());
            assertEquals("text.txt", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Normalizes legacy backslashes to archive-local separators independently of the producing host.
    @ParameterizedTest
    @ValueSource(strings = {"Unix", "Windows"})
    void readsBackslashNames(String host) throws IOException {
        Path archive = fixture("compat/backslashes_From" + host + ".zip");
        try (var reference = new java.util.zip.ZipFile(archive.toFile());
             var fileSystem = ZipArkivoFileSystem.open(archive)) {
            var entry = reference.entries().nextElement();
            try (var input = reference.getInputStream(entry)) {
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(fileSystem.getPath("/" + entry.getName().replace('\\', '/'))));
            }
        }
    }

    /// Exposes a directory's logical size as zero while correctly skipping its nonempty stored wire body.
    @Test
    void skipsNonemptyDirectoryBody(@TempDir Path directory) throws IOException {
        Path archive = fixture("badzipfiles/nameSlashedAndNonzero.zip");
        try (var reference = new java.util.zip.ZipFile(archive.toFile());
             var fileSystem = ZipArkivoFileSystem.open(archive);
             var raw = new ChunkedInput(archive, 1); var reader = ZipArkivoStreamingReader.open(raw)) {
            var entry = reference.entries().nextElement();
            assertTrue(entry.isDirectory());
            try (var body = reference.getInputStream(entry)) {
                assertArrayEquals("dirka file dirka".getBytes(StandardCharsets.US_ASCII), body.readAllBytes());
            }
            assertTrue(Files.isDirectory(fileSystem.getPath("/dirkaatx")));
            assertEquals(0, Files.size(fileSystem.getPath("/dirkaatx")));
            assertTrue(reader.next());
            assertTrue(reader.readAttributes().isDirectory());
            assertEquals(0, reader.readAttributes().size());
            assertFalse(reader.next());
        }
        Path copy = directory.resolve("updated.zip");
        Files.copy(archive, copy);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Files.write(fileSystem.getPath("/dirkaatx/added.bin"), new byte[]{1, 3, 7});
        }
        try (var fileSystem = ZipArkivoFileSystem.open(copy)) {
            assertArrayEquals(new byte[]{1, 3, 7}, Files.readAllBytes(fileSystem.getPath("/dirkaatx/added.bin")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/dirkaatx")));
        }
    }

    /// Rejects declared multi-gigabyte entries before allocating their expanded contents.
    @ParameterizedTest
    @CsvSource({"fileLengthGreaterIntLessUInt.zip,large.bin,3600000000", "veryLarge.zip,bigFile.bin,6442450944"})
    void enforcesLargeEntryLimits(String name, String entry, long size) throws IOException {
        Path archive = fixture("StrangeZipFiles/" + name);
        try (var fileSystem = ZipArkivoFileSystem.open(archive); var reference = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals(size, reference.getEntry(entry).getSize());
            assertEquals(size, Files.size(fileSystem.getPath("/" + entry)));
        }
        var exception = assertThrows(ArkivoReadLimitException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(archive, BOUNDED)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertEquals(ArkivoReadLimitKind.ENTRY_SIZE, exception.kind());
        assertEquals(8L << 20, exception.maximum());
        assertEquals(size, exception.actual());
    }

    /// Applies upstream update operations and compares every regular file against the corresponding plaintext tree.
    @ParameterizedTest
    @ValueSource(strings = {"append", "overwrite", "addFile", "deletemove"})
    void matchesUpdatedReferenceTree(String operation, @TempDir Path directory) throws IOException {
        Path copy = directory.resolve("updated.zip");
        Files.copy(fixture("refzipfiles/normal.zip"), copy);
        Path expectedRoot = fixture("modified/" + operation);
        try (var fileSystem = ZipArkivoFileSystem.update(copy)) {
            Path first = fileSystem.getPath("/first.txt");
            switch (operation) {
                case "append" -> {
                    byte[] original = Files.readAllBytes(first);
                    byte[] expected = Files.readAllBytes(expectedRoot.resolve("first.txt"));
                    assertArrayEquals(original, Arrays.copyOf(expected, original.length));
                    try (var channel = Files.newByteChannel(first, StandardOpenOption.APPEND)) {
                        ByteBuffer suffix = ByteBuffer.wrap(expected, original.length, expected.length - original.length);
                        while (suffix.hasRemaining()) assertTrue(channel.write(suffix) > 0);
                    }
                }
                case "overwrite" -> Files.write(first, Files.readAllBytes(expectedRoot.resolve("first.txt")));
                case "addFile" -> Files.write(fileSystem.getPath("/added.txt"), Files.readAllBytes(expectedRoot.resolve("added.txt")));
                case "deletemove" -> {
                    Files.delete(fileSystem.getPath("/binary.wmv"));
                    Files.move(fileSystem.getPath("/notempty/second.txt"), fileSystem.getPath("/notempty/secondnewname.txt"));
                }
                default -> throw new AssertionError(operation);
            }
        }
        try (var fileSystem = ZipArkivoFileSystem.open(copy); var files = Files.walk(expectedRoot)) {
            List<Path> expectedFiles = files.filter(Files::isRegularFile).toList();
            for (Path expected : expectedFiles) {
                String name = expectedRoot.relativize(expected).toString().replace('\\', '/');
                assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(fileSystem.getPath("/" + name)), name);
            }
            var actual = ArchiveCorpusAssertions.readFileSystem(fileSystem);
            assertEquals(expectedFiles.size(), actual.stream().filter(entry -> !entry.directory()).count());
            ArchiveCorpusAssertions.assertEquivalentEntries(actual, ArchiveCorpusAssertions.readZipReference(copy));
        }
    }

    /// Resolves a resource from the pinned official assets checkout.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.dotnet-assets.testDataDirectory")))
                .resolve("src/System.IO.Compression.TestData/ZipTestData").resolve(name);
    }

    /// Limits transport reads without changing the archive or reporting misleading availability.
    @NotNullByDefault
    private static final class ChunkedInput extends FilterInputStream {
        /// Maximum bulk read length.
        private final int chunk;

        /// Opens the original fixture.
        private ChunkedInput(Path path, int chunk) throws IOException {
            super(Files.newInputStream(path));
            this.chunk = chunk;
        }

        /// Reads at most one configured chunk.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return in.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
