// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderGraph;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderMethod;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive readers against pinned, independently produced libarchive fixtures.
@NotNullByDefault
public final class LibarchiveArchiveCorpusTest {
    /// The system property containing the prepared libarchive corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.libarchive.testDataDirectory";

    /// The sentinel used when a fixture is verified through a content prefix instead of a CRC-32.
    private static final long UNKNOWN_CRC32 = -1L;

    /// The GNU TAR path whose 200-byte name is carried in an extension record.
    private static final String GNU_LONG_FILE_NAME = "1234567890".repeat(20);

    /// The GNU TAR symbolic-link path whose 200-byte name is carried in an extension record.
    private static final String GNU_LONG_LINK_NAME = "abcdefghij".repeat(20);

    /// The expected content of the canonical RAR4 text entries.
    private static final byte[] RAR4_TEXT = "test text document\r\n".getBytes(StandardCharsets.US_ASCII);

    /// The password used by libarchive's canonical encrypted 7z fixtures, encoded as required by 7z AES.
    private static final ArkivoPasswordProvider SEVEN_ZIP_PASSWORD = ArkivoPasswordProvider.fixed(
            "12345678".toCharArray(),
            StandardCharsets.UTF_16LE
    );

    /// The intentionally incorrect password used by ZIP failure-path assertions.
    private static final ArkivoPasswordProvider WRONG_ZIP_PASSWORD = ArkivoPasswordProvider.fixed(
            "invalid_pass".toCharArray(),
            StandardCharsets.UTF_8
    );

    /// Verifies GNU and BSD AR long-name handling, metadata, and payload boundaries.
    @Test
    public void readsCanonicalArArchive(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = decodeFixture("test_read_format_ar.ar.uu", "canonical.ar", temporaryDirectory);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            assertArEntry(fileSystem, "yyytttsssaaafff.o", 1001L, 8L, "55667788");
            assertArEntry(fileSystem, "gghh.o", 1001L, 4L, "3333");
            assertArEntry(fileSystem, "hhhhjjjjkkkkllll.o", 1001L, 9L, "987654321");
        }
    }

    /// Verifies GNU TAR long names, long link targets, and base-256 numeric fields.
    @Test
    public void readsGnuTarExtensions(@TempDir Path temporaryDirectory) throws IOException {
        Path longNames = decodeFixture("test_compat_gtar_1.tar.uu", "long-names.tar", temporaryDirectory);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(longNames)) {
            Path file = fileSystem.getPath("/" + GNU_LONG_FILE_NAME);
            TarArkivoEntryAttributes attributes = Files.readAttributes(file, TarArkivoEntryAttributes.class);
            assertEquals(1000L, attributes.userId());
            assertEquals(1000L, attributes.groupId());
            assertEquals("tim", attributes.userName());
            assertEquals("tim", attributes.groupName());
            assertEquals(0100644, attributes.mode());
            assertEquals(0L, Files.size(file));

            Path link = fileSystem.getPath("/" + GNU_LONG_LINK_NAME);
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(GNU_LONG_FILE_NAME, Files.readSymbolicLink(link).toString());
        }

        Path largeIds = decodeFixture("test_compat_gtar_2.tar.uu", "large-ids.tar", temporaryDirectory);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(largeIds)) {
            TarArkivoEntryAttributes attributes = Files.readAttributes(
                    fileSystem.getPath("/file_with_big_uid_gid"),
                    TarArkivoEntryAttributes.class
            );
            assertEquals(2_097_152L, attributes.userId());
            assertEquals(2_097_152L, attributes.groupId());
            assertEquals(119L, attributes.size());
        }
    }

    /// Verifies valid ZIP data and rejects an entry with an intentionally corrupted CRC.
    @Test
    public void readsZipAndRejectsBadCrc(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = decodeFixture("test_read_format_zip.zip.uu", "crc.zip", temporaryDirectory);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            Path directory = fileSystem.getPath("/dir");
            ZipArkivoEntryAttributes directoryAttributes = Files.readAttributes(
                    directory,
                    ZipArkivoEntryAttributes.class
            );
            assertEquals("dir/", directoryAttributes.path());
            assertTrue(directoryAttributes.isDirectory());
            assertTrue(Files.isDirectory(directory));
            assertArrayEquals(
                    "hello\nhello\nhello\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/file1"))
            );
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/file2")));
        }
    }

    /// Verifies optional ZIP compression methods through random-access and forward-only readers.
    @ParameterizedTest(name = "{0}")
    @MethodSource("zipOptionalCompressionFixtures")
    public void readsZipOptionalCompressionMethods(
            String fixtureName,
            ZipMethod expectedMethod,
            Map<String, Long> expectedEntries,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        byte[] archiveBytes = decodeFixtureBytes(fixtureName);
        Path archive = temporaryDirectory.resolve(fixtureName.substring(0, fixtureName.length() - 3));
        Files.write(archive, archiveBytes);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            for (Map.Entry<String, Long> expectedEntry : expectedEntries.entrySet()) {
                Path entry = fileSystem.getPath("/" + expectedEntry.getKey());
                ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
                byte[] content = Files.readAllBytes(entry);
                assertEquals(expectedMethod, attributes.method());
                assertEquals(expectedEntry.getValue().longValue(), attributes.crc32());
                assertEquals(expectedEntry.getValue().longValue(), crc32(content));
            }
        }

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        Set<String> streamedEntries = new HashSet<>();
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
            while (reader.next()) {
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                Long expectedCrc32 = expectedEntries.get(attributes.path());
                assertTrue(expectedCrc32 != null, attributes.path());
                assertEquals(expectedMethod, attributes.method());
                assertEquals(expectedCrc32.longValue(), attributes.crc32());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(expectedCrc32.longValue(), crc32(input.readAllBytes()));
                }
                streamedEntries.add(attributes.path());
            }
        }
        assertEquals(expectedEntries.keySet(), streamedEntries);
        assertFalse(source.isOpen());
    }

    /// Verifies malformed optional-codec ZIP entries fail without hanging or leaking runtime exceptions.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedZipOptionalCompressionFixtures")
    @Timeout(10)
    public void rejectsMalformedZipOptionalCompressionStreams(
            String fixtureName,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        byte[] archiveBytes = decodeFixtureBytes(fixtureName);
        Path archive = temporaryDirectory.resolve(fixtureName.substring(0, fixtureName.length() - 3));
        Files.write(archive, archiveBytes);

        assertThrows(IOException.class, () -> {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive);
                 Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
                paths.toList();
            }
        });

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        assertThrows(IOException.class, () -> {
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
                while (reader.next()) {
                    BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                    if (attributes.isRegularFile()) {
                        try (InputStream input = reader.openInputStream()) {
                            input.readAllBytes();
                        }
                    }
                }
            }
        });
        assertFalse(source.isOpen());
    }

    /// Verifies traditional and WinZip AES entries with absent, incorrect, and correct passwords.
    @ParameterizedTest(name = "{0}")
    @MethodSource("encryptedZipFixtures")
    public void readsEncryptedZipArchives(
            String fixtureName,
            String password,
            ZipEncryption expectedEncryption,
            ZipMethod expectedMethod,
            Map<String, Long> expectedEntries,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        byte[] archiveBytes = decodeFixtureBytes(fixtureName);
        Path archive = temporaryDirectory.resolve(fixtureName.substring(0, fixtureName.length() - 3));
        Files.write(archive, archiveBytes);

        for (Map<String, ?> environment : List.<Map<String, ?>>of(
                Map.of(),
                Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), WRONG_ZIP_PASSWORD)
        )) {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive, environment)) {
                for (Map.Entry<String, Long> expectedEntry : expectedEntries.entrySet()) {
                    Path entry = fileSystem.getPath("/" + expectedEntry.getKey());
                    ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
                    assertEquals(expectedEncryption, attributes.encryption());
                    assertEquals(expectedMethod, attributes.method());
                    assertEquals(expectedEntry.getValue().longValue(), attributes.size());
                    assertThrows(IOException.class, () -> Files.readAllBytes(entry));
                }
            }
        }

        Map<String, ?> correctEnvironment = Map.of(
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password.toCharArray(), StandardCharsets.UTF_8)
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive, correctEnvironment)) {
            for (Map.Entry<String, Long> expectedEntry : expectedEntries.entrySet()) {
                Path entry = fileSystem.getPath("/" + expectedEntry.getKey());
                ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
                assertEquals(expectedEncryption, attributes.encryption());
                assertEquals(expectedMethod, attributes.method());
                assertEquals(expectedEntry.getValue().longValue(), Files.readAllBytes(entry).length);
            }
        }

        ReadableByteChannel missingPasswordSource = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        assertThrows(IOException.class, () -> {
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(missingPasswordSource)) {
                while (reader.next()) {
                    BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                    if (attributes.isRegularFile()) {
                        try (InputStream input = reader.openInputStream()) {
                            input.readAllBytes();
                        }
                    }
                }
            }
        });
        assertFalse(missingPasswordSource.isOpen());

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(archiveBytes));
        Set<String> streamedEntries = new HashSet<>();
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source, correctEnvironment)) {
            while (reader.next()) {
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                Long expectedSize = expectedEntries.get(attributes.path());
                assertTrue(expectedSize != null, attributes.path());
                assertEquals(expectedEncryption, attributes.encryption());
                assertEquals(expectedMethod, attributes.method());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(expectedSize.longValue(), input.readAllBytes().length);
                }
                streamedEntries.add(attributes.path());
            }
        }
        assertEquals(expectedEntries.keySet(), streamedEntries);
        assertFalse(source.isOpen());
    }

    /// Verifies representative 7z coder and filter pipelines from independent tools.
    @ParameterizedTest(name = "{0}")
    @MethodSource("sevenZipFixtures")
    public void readsSevenZipCoderPipelines(
            String fixtureName,
            String entryName,
            long expectedSize,
            long expectedCrc32,
            String expectedPrefix,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path archive = decodeFixture(fixtureName, fixtureName.substring(0, fixtureName.length() - 3), temporaryDirectory);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            byte[] content = Files.readAllBytes(fileSystem.getPath("/" + entryName));
            assertEquals(expectedSize, content.length);
            byte[] prefix = expectedPrefix.getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(prefix, Arrays.copyOf(content, prefix.length));
            if (expectedCrc32 != UNKNOWN_CRC32) {
                assertEquals(expectedCrc32, crc32(content));
            }
        }
    }

    /// Verifies independent multi-file folders, mixed LZMA coders, and random access into a later solid substream.
    @Test
    public void readsSevenZipMultiFileFolders(@TempDir Path temporaryDirectory) throws IOException {
        Path copied = decodeFixture("test_read_format_7zip_copy_2.7z.uu", "copy-multiple.7z", temporaryDirectory);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(copied)) {
            assertArrayEquals(
                    "aaaaaaaaaaaa\nbbbbbbbbbbbb\ncccccccccccc\ndddddddddddd\n"
                            .getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/file4"))
            );
            assertTrue(Files.isDirectory(fileSystem.getPath("/dir1")));
        }

        Path mixed = decodeFixture(
                "test_read_format_7zip_lzma1_lzma2.7z.uu",
                "mixed-lzma.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(mixed)) {
            assertArrayEquals(
                    "aaaaaaaaaaaa\nbbbbbbbbbbbb\ncccccccccccc\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/zfile3"))
            );
            assertArrayEquals(
                    "aaaaaaaaaaaa\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/dir1/file1"))
            );
        }

        Path solid = decodeFixture(
                "test_read_format_7zip_extract_second.7z.uu",
                "solid-random-access.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(solid)) {
            assertEquals(65_536L, Files.size(fileSystem.getPath("/first.txt")));
            assertArrayEquals(
                    "This is from second.txt".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/second.txt"))
            );
        }
    }

    /// Verifies ordinary and solid multi-file folders compressed with the 7z Zstandard extension method.
    @Test
    public void readsSevenZipZstandardMultiFileFolders(@TempDir Path temporaryDirectory) throws IOException {
        byte[] expected = "aaaaaaaaaaaa\nbbbbbbbbbbbb\ncccccccccccc\ndddddddddddd\n"
                .getBytes(StandardCharsets.US_ASCII);
        Path ordinary = decodeFixture(
                "test_read_format_7zip_zstd.7z.uu",
                "zstandard.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(ordinary)) {
            Path entry = fileSystem.getPath("/file4");
            assertArrayEquals(expected, Files.readAllBytes(entry));
            SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                    entry,
                    SevenZipArkivoEntryAttributes.class
            );
            assertEquals(SevenZipCoderMethod.ZSTANDARD, coderGraph(attributes).coders().get(0).method());
        }

        Path solid = decodeFixture(
                "test_read_format_7zip_solid_zstd.7z.uu",
                "solid-zstandard.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(solid)) {
            Path first = fileSystem.getPath("/dir1/file1");
            Path fourth = fileSystem.getPath("/file4");
            assertArrayEquals(expected, Files.readAllBytes(fourth));
            SevenZipArkivoEntryAttributes firstAttributes = Files.readAttributes(
                    first,
                    SevenZipArkivoEntryAttributes.class
            );
            SevenZipArkivoEntryAttributes fourthAttributes = Files.readAttributes(
                    fourth,
                    SevenZipArkivoEntryAttributes.class
            );
            assertTrue(firstAttributes.solid());
            assertTrue(fourthAttributes.solid());
            assertEquals(coderGraph(firstAttributes), coderGraph(fourthAttributes));
            assertEquals(SevenZipCoderMethod.ZSTANDARD, coderGraph(firstAttributes).coders().get(0).method());
        }
    }

    /// Verifies official Zstandard coder graphs with x86, ARM, and SPARC filters.
    @ParameterizedTest(name = "{0}")
    @MethodSource("sevenZipZstandardFilterFixtures")
    public void readsSevenZipZstandardFilterPipelines(
            String fixtureName,
            String entryName,
            long expectedSize,
            long expectedCrc32,
            List<SevenZipCoderMethod> expectedMethods,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path archive = decodeFixture(fixtureName, fixtureName.substring(0, fixtureName.length() - 3), temporaryDirectory);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            Path entry = fileSystem.getPath("/" + entryName);
            byte[] content = Files.readAllBytes(entry);
            assertEquals(expectedSize, content.length);
            assertEquals(expectedCrc32, crc32(content));
            SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                    entry,
                    SevenZipArkivoEntryAttributes.class
            );
            assertEquals(
                    expectedMethods,
                    coderGraph(attributes).coders().stream().map(coder -> coder.method()).toList()
            );
        }
    }

    /// Verifies 7z symbolic-link metadata and link-target decoding.
    @Test
    public void readsSevenZipSymbolicLink(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = decodeFixture(
                "test_read_format_7zip_symbolic_name.7z.uu",
                "symbolic-name.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            assertArrayEquals(
                    "hellohellohello\nhellohellohello\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/file1"))
            );
            Path link = fileSystem.getPath("/symlinkfile");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("file1", Files.readSymbolicLink(link).toString());
        }
    }

    /// Verifies data-only, header, and partially encrypted 7z archives with the password-provider API.
    @Test
    public void readsEncryptedSevenZipArchives(@TempDir Path temporaryDirectory) throws IOException {
        Map<String, ?> environment = Map.of(
                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                SEVEN_ZIP_PASSWORD
        );
        byte[] expected = "foo\n".getBytes(StandardCharsets.US_ASCII);

        Path encryptedData = decodeFixture(
                "test_read_format_7zip_encryption.7z.uu",
                "encrypted-data.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(encryptedData, environment)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/bar.txt")));
        }

        Path encryptedHeader = decodeFixture(
                "test_read_format_7zip_encryption_header.7z.uu",
                "encrypted-header.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(encryptedHeader, environment)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/bar.txt")));
        }

        Path partiallyEncrypted = decodeFixture(
                "test_read_format_7zip_encryption_partially.7z.uu",
                "partially-encrypted.7z",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(partiallyEncrypted)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/bar_unencrypted.txt")));
            assertThrows(
                    IOException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/bar_encrypted.txt"))
            );
        }
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(partiallyEncrypted, environment)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/bar_encrypted.txt")));
        }
    }

    /// Verifies automatic random-access format detection over arbitrary in-memory seekable channels.
    @ParameterizedTest(name = "{0}")
    @MethodSource("seekableChannelFixtures")
    public void opensOfficialArchivesFromInMemorySeekableChannels(
            String fixtureName,
            String entryName,
            byte[] expectedContent
    ) throws IOException {
        SeekableInMemoryByteChannel source = new SeekableInMemoryByteChannel(decodeFixtureBytes(fixtureName));
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(source)) {
            assertArrayEquals(expectedContent, Files.readAllBytes(fileSystem.getPath("/" + entryName)));
        }
        assertFalse(source.isOpen());
    }

    /// Verifies automatic streaming format detection and decoding over non-seekable readable channels.
    @ParameterizedTest(name = "{0}")
    @MethodSource("streamingChannelFixtures")
    public void streamsOfficialArchivesFromReadableChannels(String fixtureName, byte[] expectedContent)
            throws IOException {
        ReadableByteChannel source = java.nio.channels.Channels.newChannel(
                new ByteArrayInputStream(decodeFixtureBytes(fixtureName))
        );
        boolean matched = false;
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
            while (reader.next()) {
                BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                try (InputStream input = reader.openInputStream()) {
                    if (Arrays.equals(expectedContent, input.readAllBytes())) {
                        matched = true;
                        break;
                    }
                }
            }
        }
        assertTrue(matched, fixtureName);
        assertFalse(source.isOpen());
    }

    /// Verifies a canonical RAR4 archive including compression, directories, and a symbolic link.
    @Test
    public void readsCanonicalRar4Archive(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = decodeFixture("test_read_format_rar.rar.uu", "canonical.rar", temporaryDirectory);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            assertArrayEquals(RAR4_TEXT, Files.readAllBytes(fileSystem.getPath("/test.txt")));
            assertArrayEquals(RAR4_TEXT, Files.readAllBytes(fileSystem.getPath("/testdir/test.txt")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/testdir")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/testemptydir")));
            Path link = fileSystem.getPath("/testlink");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("test.txt", Files.readSymbolicLink(link).toString());
        }
    }

    /// Verifies both stored and compressed RAR5 payloads against upstream expectations.
    @Test
    public void readsStoredAndCompressedRar5Archives(@TempDir Path temporaryDirectory) throws IOException {
        Path stored = decodeFixture("test_read_format_rar5_stored.rar.uu", "stored.rar", temporaryDirectory);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(stored)) {
            assertArrayEquals(
                    "hello libarchive test suite!\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/helloworld.txt"))
            );
        }

        Path compressed = decodeFixture(
                "test_read_format_rar5_compressed.rar.uu",
                "compressed.rar",
                temporaryDirectory
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(compressed)) {
            byte[] content = Files.readAllBytes(fileSystem.getPath("/test.bin"));
            assertEquals(1200, content.length);
            assertEquals(0x7cca70cdL, crc32(content));
        }
    }

    /// Verifies the staged corpus retains the upstream license and immutable source manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("COPYING")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Returns the representative 7z coder and filter fixtures.
    private static Stream<Arguments> sevenZipFixtures() {
        return Stream.of(
                Arguments.of("test_read_format_7zip_copy.7z.uu", "file1", 60L, UNKNOWN_CRC32, "    "),
                Arguments.of("test_read_format_7zip_lzma1.7z.uu", "file1", 2844L, UNKNOWN_CRC32, "The libarchive distribution "),
                Arguments.of("test_read_format_7zip_lzma2.7z.uu", "file1", 2844L, UNKNOWN_CRC32, "The libarchive distribution "),
                Arguments.of("test_read_format_7zip_bzip2.7z.uu", "file1", 2844L, UNKNOWN_CRC32, "The libarchive distribution "),
                Arguments.of("test_read_format_7zip_deflate.7z.uu", "file1", 2844L, UNKNOWN_CRC32, "The libarchive distribution "),
                Arguments.of("test_read_format_7zip_ppmd.7z.uu", "ppmd_test.txt", 102400L, UNKNOWN_CRC32, ""),
                Arguments.of("test_read_format_7zip_bcj_lzma2.7z.uu", "x86exe", 27328L, UNKNOWN_CRC32, "\u007fELF"),
                Arguments.of("test_read_format_7zip_bcj2_lzma2_1.7z.uu", "x86exe", 27328L, UNKNOWN_CRC32, "\u007fELF"),
                Arguments.of("test_read_format_7zip_delta_lzma2.7z.uu", "file1", 27627L, UNKNOWN_CRC32, ""),
                Arguments.of("test_read_format_7zip_delta4_lzma2.7z.uu", "file1", 27627L, UNKNOWN_CRC32, ""),
                Arguments.of("test_read_format_7zip_lzma2_arm.7z.uu", "hw-gnueabihf", 7804L, 0x355ec4e1L, ""),
                Arguments.of("test_read_format_7zip_lzma2_arm64.7z.uu", "hw-arm64", 70368L, 0xde97d594L, ""),
                Arguments.of("test_read_format_7zip_lzma2_riscv.7z.uu", "hw-riscv64", 8488L, 0xf7ed24e7L, ""),
                Arguments.of("test_read_format_7zip_lzma2_powerpc.7z.uu", "hw-powerpc", 68340L, 0x71fb03c9L, ""),
                Arguments.of("test_read_format_7zip_lzma2_sparc.7z.uu", "hw-sparc64", 1053016L, 0x6b5b364dL, ""),
                Arguments.of("test_read_format_7zip_deflate_arm64.7z.uu", "hw-arm64", 70368L, 0xde97d594L, ""),
                Arguments.of("test_read_format_7zip_deflate_powerpc.7z.uu", "hw-powerpc", 68340L, 0x71fb03c9L, "")
        );
    }

    /// Returns official ZIP fixtures using optional compression methods.
    private static Stream<Arguments> zipOptionalCompressionFixtures() {
        Map<String, Long> singleEntry = Map.of("vimrc", 0xba8e3baaL);
        Map<String, Long> multiEntry = Map.of(
                "smartd.conf", 0x8dd7379eL,
                "ts.conf", 0x7ae59b31L,
                "vimrc", 0xba8e3baaL
        );
        return Stream.of(
                Arguments.of("test_read_format_zip_bzip2.zipx.uu", ZipMethod.bzip2(), singleEntry),
                Arguments.of("test_read_format_zip_bzip2_multi.zipx.uu", ZipMethod.bzip2(), multiEntry),
                Arguments.of("test_read_format_zip_lzma.zipx.uu", ZipMethod.lzma(), singleEntry),
                Arguments.of("test_read_format_zip_lzma_multi.zipx.uu", ZipMethod.lzma(), multiEntry),
                Arguments.of("test_read_format_zip_lzma_stream_end.zipx.uu", ZipMethod.lzma(), singleEntry),
                Arguments.of("test_read_format_zip_xz_multi.zipx.uu", ZipMethod.xz(), Map.of(
                        "bash.bashrc", 0xf751b8c9L,
                        "pacman.conf", 0xb20b7f88L,
                        "profile", 0x2329f054L
                )),
                Arguments.of("test_read_format_zip_zstd.zipx.uu", ZipMethod.zstandard(), singleEntry),
                Arguments.of("test_read_format_zip_zstd_multi.zipx.uu", ZipMethod.zstandard(), multiEntry)
        );
    }

    /// Returns malformed optional-codec ZIP fixtures with historical hang or leak regressions.
    private static Stream<Arguments> malformedZipOptionalCompressionFixtures() {
        return Stream.of(
                Arguments.of("test_read_format_zip_bz2_hang.zip.uu"),
                Arguments.of("test_read_format_zip_lzma_alone_leak.zipx.uu")
        );
    }

    /// Returns official traditional ZIP and WinZip AES encrypted fixtures.
    private static Stream<Arguments> encryptedZipFixtures() {
        return Stream.of(
                Arguments.of(
                        "test_read_format_zip_traditional_encryption_data.zip.uu",
                        "12345678",
                        ZipEncryption.traditional(),
                        ZipMethod.deflated(),
                        Map.of("bar.txt", 495L, "foo.txt", 495L)
                ),
                Arguments.of(
                        "test_read_format_zip_winzip_aes128.zip.uu",
                        "password",
                        ZipEncryption.winZipAes128(),
                        ZipMethod.deflated(),
                        Map.of("README", 6818L)
                ),
                Arguments.of(
                        "test_read_format_zip_winzip_aes256.zip.uu",
                        "password",
                        ZipEncryption.winZipAes256(),
                        ZipMethod.deflated(),
                        Map.of("README", 6818L)
                ),
                Arguments.of(
                        "test_read_format_zip_winzip_aes256_large.zip.uu",
                        "password",
                        ZipEncryption.winZipAes256(),
                        ZipMethod.deflated(),
                        Map.of(
                                "Makefile", 1_456_747L,
                                "NEWS", 29_357L,
                                "README", 6_818L,
                                "config.h", 32_667L
                        )
                ),
                Arguments.of(
                        "test_read_format_zip_winzip_aes256_stored.zip.uu",
                        "password",
                        ZipEncryption.winZipAes256(),
                        ZipMethod.stored(),
                        Map.of("README", 6818L)
                )
        );
    }

    /// Returns official Zstandard coder and filter graph fixtures.
    private static Stream<Arguments> sevenZipZstandardFilterFixtures() {
        return Stream.of(
                Arguments.of(
                        "test_read_format_7zip_zstd_nobcj.7z.uu",
                        "hw",
                        15952L,
                        0xbd66eebcL,
                        List.of(SevenZipCoderMethod.ZSTANDARD)
                ),
                Arguments.of(
                        "test_read_format_7zip_zstd_bcj.7z.uu",
                        "hw",
                        15952L,
                        0xbd66eebcL,
                        List.of(SevenZipCoderMethod.ZSTANDARD, SevenZipCoderMethod.BCJ_X86)
                ),
                Arguments.of(
                        "test_read_format_7zip_zstd_arm.7z.uu",
                        "hw-gnueabihf",
                        7804L,
                        0x355ec4e1L,
                        List.of(SevenZipCoderMethod.ZSTANDARD, SevenZipCoderMethod.BCJ_ARM)
                ),
                Arguments.of(
                        "test_read_format_7zip_zstd_sparc.7z.uu",
                        "hw-sparc64",
                        1053016L,
                        0x6b5b364dL,
                        List.of(SevenZipCoderMethod.ZSTANDARD, SevenZipCoderMethod.BCJ_SPARC)
                )
        );
    }

    /// Returns the required coder graph for one non-empty 7z entry.
    private static SevenZipCoderGraph coderGraph(SevenZipArkivoEntryAttributes attributes) {
        return Objects.requireNonNull(attributes.coderGraph());
    }

    /// Returns official archives used to exercise arbitrary seekable-channel detection.
    private static Stream<Arguments> seekableChannelFixtures() {
        return Stream.of(
                Arguments.of("test_read_format_ar.ar.uu", "gghh.o", "3333".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("test_compat_gtar_1.tar.uu", GNU_LONG_FILE_NAME, new byte[0]),
                Arguments.of("test_read_format_zip.zip.uu", "file1", "hello\nhello\nhello\n".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("test_read_format_7zip_symbolic_name.7z.uu", "file1", "hellohellohello\nhellohellohello\n".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("test_read_format_rar5_stored.rar.uu", "helloworld.txt", "hello libarchive test suite!\n".getBytes(StandardCharsets.US_ASCII))
        );
    }

    /// Returns official archives used to exercise non-seekable streaming detection.
    private static Stream<Arguments> streamingChannelFixtures() {
        return Stream.of(
                Arguments.of("test_read_format_ar.ar.uu", "55667788".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("test_compat_gtar_1.tar.uu", new byte[0]),
                Arguments.of("test_read_format_zip.zip.uu", "hello\nhello\nhello\n".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("test_read_format_rar5_stored.rar.uu", "hello libarchive test suite!\n".getBytes(StandardCharsets.US_ASCII))
        );
    }

    /// Verifies one AR member's metadata and exact content.
    private static void assertArEntry(
            ArkivoFileSystem fileSystem,
            String name,
            long expectedUserId,
            long expectedSize,
            String expectedContent
    ) throws IOException {
        Path entry = fileSystem.getPath("/" + name);
        ArArkivoEntryAttributes attributes = Files.readAttributes(entry, ArArkivoEntryAttributes.class);
        assertEquals(expectedUserId, attributes.userId());
        assertEquals(expectedSize, attributes.size());
        assertArrayEquals(expectedContent.getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(entry));
    }

    /// Decodes one staged fixture into a disposable binary archive.
    private static Path decodeFixture(String fixtureName, String archiveName, Path temporaryDirectory)
            throws IOException {
        Path target = temporaryDirectory.resolve(archiveName);
        Files.write(target, decodeFixtureBytes(fixtureName));
        return target;
    }

    /// Decodes one staged fixture into an in-memory archive.
    private static byte[] decodeFixtureBytes(String fixtureName) throws IOException {
        return LibarchiveUuDecoder.decode(corpusPath("fixtures").resolve(fixtureName));
    }

    /// Computes the standard CRC-32 used by the RAR5 reference assertion.
    private static long crc32(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }

    /// Resolves one path under the prepared corpus directory.
    private static Path corpusPath(String first, String... more) {
        String directory = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(directory).resolve(Path.of(first, more));
    }
}
