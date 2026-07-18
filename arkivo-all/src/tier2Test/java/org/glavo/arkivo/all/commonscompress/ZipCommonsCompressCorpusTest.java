// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP compatibility with Commons Compress archives produced by several independent tools.
@NotNullByDefault
final class ZipCommonsCompressCorpusTest {
    /// The caller-owned bytes following a complete upstream archive.
    private static final byte @Unmodifiable [] ARCHIVE_TRAILER =
            "Hello, world!\n".getBytes(StandardCharsets.US_ASCII);

    /// ZIP fixtures whose entry compression methods are intentionally not implemented by Arkivo.
    private static final @Unmodifiable Set<String> UNSUPPORTED_METHOD_ARCHIVES = Set.of(
            "SHRUNK.ZIP",
            "imploding-4Kdict-2trees.zip",
            "imploding-8Kdict-3trees.zip",
            "moby-imploded.zip",
            "moby.zip"
    );

    /// ZIP fixtures that are structurally truncated or corrupt by construction.
    private static final @Unmodifiable Set<String> MALFORMED_ARCHIVES = Set.of(
            "COMPRESS-351.zip",
            "COMPRESS-546.zip",
            "COMPRESS-647/test.zip",
            "invalid-zip.zip"
    );

    /// ZIP fixtures whose stored-entry descriptors contradict the physical body or one another.
    private static final @Unmodifiable Set<String> CORRUPT_DATA_DESCRIPTOR_ARCHIVES = Set.of(
            "bla-stored-dd-contradicts-actualsize.zip",
            "bla-stored-dd-sizes-differ.zip"
    );

    /// ZIP fixtures intentionally lacking a complete central directory and therefore only readable forward.
    private static final @Unmodifiable Set<String> STREAMING_ONLY_ARCHIVES = Set.of(
            "archive_with_trailer.zip"
    );

    /// ZIP fixtures whose central-directory-only metadata needs an access-mode-specific assertion.
    private static final @Unmodifiable Set<String> SPECIALIZED_ARCHIVES = Set.of(
            "COMPRESS-214_unix_symlinks.zip",
            "COMPRESS-227.zip",
            "COMPRESS-479.zip",
            "COMPRESS-548.zip"
    );

    /// Container-shaped resources that intentionally do not contain a complete ZIP central directory.
    private static final @Unmodifiable Set<String> NON_ZIP_CONTAINERS = Set.of(
            "org/apache/commons/compress/COMPRESS-626/compress-626-pack200.jar"
    );

    /// ZIP fixtures that need a password unavailable in the upstream test corpus.
    private static final @Unmodifiable Set<String> PASSWORD_ONLY_ARCHIVES = Set.of("password-encrypted.zip");

    /// Symbolic-link targets asserted by the original Commons Compress COMPRESS-214 regression.
    private static final @Unmodifiable Map<String, String> UNIX_SYMLINK_TARGETS = Map.ofEntries(
            Map.entry("COMPRESS-214_unix_symlinks/link1", "../COMPRESS-214_unix_symlinks/./a/b/c/../../../\uF999"),
            Map.entry("COMPRESS-214_unix_symlinks/link2", "../COMPRESS-214_unix_symlinks/./a/b/c/../../../g"),
            Map.entry("COMPRESS-214_unix_symlinks/link3", "../COMPRESS-214_unix_symlinks/././a/b/c/../../../\u76F4\u6A39"),
            Map.entry("COMPRESS-214_unix_symlinks/link4", "\u82B1\u5B50/\u745B\u5B50"),
            Map.entry("COMPRESS-214_unix_symlinks/\uF999", "./\u82B1\u5B50/\u745B\u5B50/\u5897\u8C37/\uF999"),
            Map.entry("COMPRESS-214_unix_symlinks/g", "./a/b/c/d/e/f/g"),
            Map.entry("COMPRESS-214_unix_symlinks/\u76F4\u6A39", "./g"),
            Map.entry("COMPRESS-214_unix_symlinks/link5", "../COMPRESS-214_unix_symlinks/././a/b"),
            Map.entry("COMPRESS-214_unix_symlinks/link6", "../COMPRESS-214_unix_symlinks/././a/b/")
    );

    /// Signed Unix timestamps stored by every numeric entry in the COMPRESS-210 fixture.
    private static final @Unmodifiable Map<String, ExpectedTimes> COMPRESS_210_TIMES = Map.ofEntries(
            Map.entry("2105", new ExpectedTimes(-34_756_095L, -34_756_093L)),
            Map.entry("1970", new ExpectedTimes(1L, 3L)),
            Map.entry("2039", new ExpectedTimes(-2_117_514_495L, -2_117_514_493L)),
            Map.entry("2037", new ExpectedTimes(2_114_380_801L, 2_114_380_803L)),
            Map.entry("2107", new ExpectedTimes(28_315_905L, 28_315_907L)),
            Map.entry("2108", new ExpectedTimes(59_851_905L, 59_851_907L)),
            Map.entry("2000", new ExpectedTimes(946_684_801L, 946_684_803L)),
            Map.entry("1999", new ExpectedTimes(915_148_801L, 915_148_803L)),
            Map.entry("2038", new ExpectedTimes(2_145_916_801L, 2_145_916_803L)),
            Map.entry("2001", new ExpectedTimes(978_307_201L, 978_307_203L)),
            Map.entry("2109", new ExpectedTimes(91_474_305L, 91_474_307L)),
            Map.entry("1971", new ExpectedTimes(31_536_001L, 31_536_003L)),
            Map.entry("2106", new ExpectedTimes(-3_220_095L, -3_220_093L))
    );

    /// Entry leaf names stored in the `ordertest.zip` central directory sequence.
    private static final @Unmodifiable List<String> ORDERTEST_CENTRAL_NAMES = List.of(
            "AbstractUnicodeExtraField.java",
            "AsiExtraField.java",
            "ExtraFieldUtils.java",
            "FallbackZipEncoding.java",
            "GeneralPurposeBit.java",
            "JarMarker.java",
            "NioZipEncoding.java",
            "Simple8BitZipEncoding.java",
            "UnicodeCommentExtraField.java",
            "UnicodePathExtraField.java",
            "UnixStat.java",
            "UnparseableExtraFieldData.java",
            "UnrecognizedExtraField.java",
            "ZipArchiveEntry.java",
            "ZipArchiveInputStream.java",
            "ZipArchiveOutputStream.java",
            "ZipEncoding.java",
            "ZipEncodingHelper.java",
            "ZipExtraField.java",
            "ZipUtil.java",
            "ZipLong.java",
            "ZipShort.java",
            "ZipFile.java"
    );

    /// Entry leaf names stored in the `ordertest.zip` local-file-header sequence.
    private static final @Unmodifiable List<String> ORDERTEST_PHYSICAL_NAMES = List.of(
            "AbstractUnicodeExtraField.java",
            "AsiExtraField.java",
            "ExtraFieldUtils.java",
            "FallbackZipEncoding.java",
            "GeneralPurposeBit.java",
            "JarMarker.java",
            "NioZipEncoding.java",
            "Simple8BitZipEncoding.java",
            "UnicodeCommentExtraField.java",
            "UnicodePathExtraField.java",
            "UnixStat.java",
            "UnparseableExtraFieldData.java",
            "UnrecognizedExtraField.java",
            "ZipArchiveEntry.java",
            "ZipArchiveInputStream.java",
            "ZipArchiveOutputStream.java",
            "ZipEncoding.java",
            "ZipEncodingHelper.java",
            "ZipExtraField.java",
            "ZipFile.java",
            "ZipLong.java",
            "ZipShort.java",
            "ZipUtil.java"
    );

    /// Opens every supported ordinary and split ZIP through seekable and streaming APIs.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveThroughFileSystemAndStreaming(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> seekable;
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            seekable = ArchiveCorpusAssertions.readFileSystem(fileSystem);
            assertStoredCrcValues(fileSystem);
        }
        assertFalse(seekable.isEmpty(), "seekable ZIP view must expose at least one entry");

        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> streaming;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            streaming = ArchiveCorpusAssertions.readStreaming(reader);
        }
        ArchiveCorpusAssertions.assertEquivalentEntries(seekable, streaming);
    }

    /// Opens every valid JAR, APK, and Numbers container through its applicable ZIP access modes.
    @ParameterizedTest(name = "{0}")
    @MethodSource("zipContainerResources")
    void readsZipContainerThroughFileSystemAndStreaming(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> seekable;
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            seekable = ArchiveCorpusAssertions.readFileSystem(fileSystem);
        }
        if (resource.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            assertFalse(seekable.isEmpty(), "APK must expose at least one ZIP entry");
            return;
        }
        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> streaming;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            streaming = ArchiveCorpusAssertions.readStreaming(reader);
        }
        ArchiveCorpusAssertions.assertEquivalentEntries(seekable, streaming);
    }

    /// Rejects the Pack200 regression input whose `.jar` suffix does not describe a ZIP container.
    @Test
    void rejectsPack200InputWithJarSuffix() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org/apache/commons/compress/COMPRESS-626/compress-626-pack200.jar"
        );
        assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Verifies the bounded expansion ratios and complete entry bodies of the upstream ZIP bomb regression fixture.
    @Test
    void readsZipBombStatisticsAndBodies() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("zipbomb.xlsx")
        )) {
            assertEntryStatisticsAndConsume(fileSystem.getPath("/[Content_Types].xml"), 8_390_036L, 8_600L);
            assertEntryStatisticsAndConsume(fileSystem.getPath("/xl/worksheets/sheet1.xml"), 1_348L, 508L);
        }
    }

    /// Verifies Unicode path extra fields and UTF-8 flags from 7-Zip and WinZip.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unicodeArchives")
    void resolvesUnicodeEntryNames(String resource) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            assertTrue(Files.exists(fileSystem.getPath("/ascii.txt")));
            assertTrue(Files.exists(fileSystem.getPath("/\u00d6lf\u00e4sser.txt")));
            assertTrue(Files.exists(fileSystem.getPath("/\u20ac_for_Dollar.txt")));
        }
    }

    /// Verifies WinZip backslashes are exposed as archive path separators.
    @Test
    void normalizesWinZipBackslashes() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("test-winzip.zip")
        )) {
            assertTrue(Files.isDirectory(fileSystem.getPath("/\u00e4")));
            assertTrue(Files.isRegularFile(fileSystem.getPath("/\u00e4/\u00fc.txt")));
        }
    }

    /// Verifies PK00 and arbitrary preambles do not hide the first entry.
    @ParameterizedTest(name = "{0}")
    @MethodSource("preambleArchives")
    void readsArchivesWithPreambles(String resource) throws IOException {
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            assertFalse(ArchiveCorpusAssertions.readStreaming(reader).isEmpty());
        }
    }

    /// Distinguishes the handcrafted central-directory sequence from physical local-header order.
    @Test
    void distinguishesCentralDirectoryAndPhysicalEntryOrder() throws IOException {
        Path archive = CommonsCompressTestResources.resource("ordertest.zip");
        @Unmodifiable List<String> centralNames;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(archive.toFile())) {
            centralNames = zipFile.stream()
                    .map(entry -> leafName(entry.getName()))
                    .toList();
        }

        @Unmodifiable List<String> physicalNames;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            physicalNames = ArchiveCorpusAssertions.readStreaming(reader).stream()
                    .map(entry -> leafName(entry.path()))
                    .toList();
        }

        assertEquals(ORDERTEST_CENTRAL_NAMES, centralNames);
        assertEquals(ORDERTEST_PHYSICAL_NAMES, physicalNames);
        assertFalse(centralNames.equals(physicalNames));
    }

    /// Verifies central-directory Unix mode metadata and every COMPRESS-214 symbolic-link target.
    @Test
    void readsUnixSymbolicLinks() throws IOException {
        ZipArchiveOptions.Read utf8 = ZipArchiveOptions.READ_DEFAULTS.withLegacyCharsetDetector(
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8)
        );
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-214_unix_symlinks.zip"),
                utf8
        )) {
            for (Map.Entry<String, String> entry : UNIX_SYMLINK_TARGETS.entrySet()) {
                Path link = fileSystem.getPath("/" + entry.getKey());
                assertTrue(Files.isSymbolicLink(link), entry.getKey());
                String expectedTarget = entry.getValue();
                if (expectedTarget.endsWith("/")) {
                    expectedTarget = expectedTarget.substring(0, expectedTarget.length() - 1);
                }
                assertEquals(expectedTarget, Files.readSymbolicLink(link).toString().replace('\\', '/'));
            }
        }

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                CommonsCompressTestResources.resource("COMPRESS-214_unix_symlinks.zip"),
                utf8
        )) {
            Map<String, String> actualTargets = new HashMap<>();
            int entryCount = 0;
            while (reader.next()) {
                var attributes = reader.readAttributes();
                if (attributes.isRegularFile()) {
                    byte @Unmodifiable [] content;
                    try (var input = reader.openInputStream()) {
                        content = input.readAllBytes();
                    }
                    if (UNIX_SYMLINK_TARGETS.containsKey(attributes.path())) {
                        actualTargets.put(attributes.path(), new String(content, StandardCharsets.UTF_8));
                    }
                }
                entryCount++;
            }
            assertEquals(21, entryCount);
            assertEquals(UNIX_SYMLINK_TARGETS, actualTargets);
        }
    }

    /// Verifies every signed modification and access timestamp from the COMPRESS-210 regression archive.
    @Test
    void readsInfoZipExtendedTimestamps() throws IOException {
        Path archive = CommonsCompressTestResources.resource("COMPRESS-210_unix_time_zip_test.zip");
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            for (Map.Entry<String, ExpectedTimes> entry : COMPRESS_210_TIMES.entrySet()) {
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        fileSystem.getPath("/COMPRESS-210_unix_time_zip_test/" + entry.getKey()),
                        ZipArkivoEntryAttributes.class
                );
                assertExpectedTimes(entry.getKey(), entry.getValue(), attributes);
            }
        }

        Set<String> observedEntries = new HashSet<>();
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            while (reader.next()) {
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                String entryName = leafName(attributes.path());
                @Nullable ExpectedTimes expected = COMPRESS_210_TIMES.get(entryName);
                if (expected != null) {
                    assertExpectedTimes(entryName, expected, attributes);
                    observedEntries.add(entryName);
                }
                consumeRegularEntry(reader, attributes);
            }
        }
        assertEquals(COMPRESS_210_TIMES.keySet(), observedEntries);
    }

    /// Verifies every numeric UID/GID pair from the COMPRESS-211 regression archive.
    @Test
    void readsInfoZipNewUnixIdentifiers() throws IOException {
        Path archive = CommonsCompressTestResources.resource("COMPRESS-211_uid_gid_zip_test.zip");
        int seekableCount = 0;
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive);
             var paths = Files.walk(fileSystem.getPath("/uid_gid_zip_test"))) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                ZipArkivoEntryAttributes attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
                assertExpectedUnixIdentifiers(attributes);
                seekableCount++;
            }
        }
        assertEquals(13, seekableCount);

        int streamingCount = 0;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            while (reader.next()) {
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertExpectedUnixIdentifiers(attributes);
                consumeRegularEntry(reader, attributes);
                streamingCount++;
            }
        }
        assertEquals(seekableCount, streamingCount);
    }

    /// Verifies duplicate physical names remain visible in streaming order while the NIO view rejects ambiguity.
    @Test
    void exposesDuplicateNamesOnlyThroughStreaming() throws IOException {
        Path archive = CommonsCompressTestResources.resource("COMPRESS-227.zip");
        assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            long duplicates = ArchiveCorpusAssertions.readStreaming(reader).stream()
                    .filter(entry -> entry.path().equals("test1.txt"))
                    .count();
            assertEquals(2L, duplicates);
        }
    }

    /// Verifies the central Unicode path extra field and streaming local-header fallback for COMPRESS-479.
    @Test
    void distinguishesCentralAndLocalUnicodeNames() throws IOException {
        Path archive = CommonsCompressTestResources.resource("COMPRESS-479.zip");
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertTrue(Files.isRegularFile(fileSystem.getPath("/\u20ac_for_Dollar.txt")));
        }
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            assertTrue(ArchiveCorpusAssertions.readStreaming(reader).stream()
                    .anyMatch(entry -> entry.path().equals("%U20AC_for_Dollar.txt")));
        }
    }

    /// Iterates the COMPRESS-548 local header while safely ignoring its truncated trailing extra-field record.
    @Test
    void readsEntryWithTruncatedTrailingExtraField() throws IOException {
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                CommonsCompressTestResources.resource("COMPRESS-548.zip")
        )) {
            assertTrue(reader.next());
            assertEquals("docker_routine.sh", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Reads fixtures whose local records are useful even though no complete central directory is available.
    @ParameterizedTest(name = "{0}")
    @MethodSource("streamingOnlyArchives")
    void readsStreamingOnlyArchive(String resource) throws IOException {
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            assertFalse(ArchiveCorpusAssertions.readStreaming(reader).isEmpty());
        }
    }

    /// Leaves the caller-owned source positioned exactly at the first byte following the complete ZIP archive.
    @Test
    void leavesCallerOwnedSourceAtArchiveTrailer() throws IOException {
        try (var source = Files.newInputStream(
                CommonsCompressTestResources.resource("archive_with_trailer.zip")
        ); ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source)) {
            ArchiveCorpusAssertions.readStreaming(reader);
            assertArrayEquals(ARCHIVE_TRAILER, source.readNBytes(ARCHIVE_TRAILER.length));
            assertEquals(-1, source.read());
        }
    }

    /// Verifies the supported codec fixtures expose the method declared by their central-directory metadata.
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("codecArchives")
    void readsSupportedCompressionMethod(String resource, String entryName) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            Path entry = fileSystem.getPath("/" + entryName);
            ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
            assertTrue(attributes.compressionMethod() != null);
            assertTrue(Files.readAllBytes(entry).length > 0);
        }
    }

    /// Keeps Shrink and Implode visible as explicit unsupported-format contracts.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedMethodArchives")
    void rejectsUnsupportedCompressionMethods(String resource) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            Path entry;
            try (var paths = Files.walk(fileSystem.getPath("/"))) {
                var iterator = paths.filter(Files::isRegularFile).iterator();
                if (!iterator.hasNext()) {
                    throw new AssertionError("unsupported fixture has no file entry");
                }
                entry = iterator.next();
            }
            ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
            assertNull(attributes.compressionMethod());
            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(entry));
            assertTrue(exception.getMessage().contains("Unsupported ZIP compression method"), exception.getMessage());
        }
    }

    /// Rejects fixtures whose central directory or local entry structure is invalid.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedArchives")
    void rejectsMalformedArchives(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertThrows(IOException.class, () -> {
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(Files.newInputStream(archive))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Rejects the extensionless COMPRESS-598 fuzz input without leaking runtime exceptions from malformed ZIP data.
    @Test
    void rejectsMalformedFuzzInput() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org/apache/commons/compress/fuzz/crash-f2efd9eaeb86cda597d07b5e3c3d81363633c2da"
        );
        assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertThrows(IOException.class, () -> {
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Rejects stored-entry descriptors that disagree with entry data through both ZIP access modes.
    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptDataDescriptorArchives")
    void rejectsCorruptDataDescriptors(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertThrows(IOException.class, () -> {
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Verifies an encrypted upstream fixture cannot be decoded without credentials.
    @Test
    void encryptedFixtureRequiresPassword() throws IOException {
        Path archive = CommonsCompressTestResources.resource("password-encrypted.zip");
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                archive,
                ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.none())
        )) {
            IOException exception = assertThrows(
                    IOException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/LICENSE.txt"))
            );
            assertTrue(exception.getMessage().toLowerCase(Locale.ROOT).contains("password"), exception.getMessage());
        }
    }

    /// Verifies the lone downloaded volume is reported as truncated rather than looping at EOF.
    @Test
    void rejectsTruncatedSevenZipStyleVolume() throws IOException {
        Path archive = CommonsCompressTestResources.resource("apache-maven-2.2.1.zip.001");
        assertThrows(IOException.class, () -> {
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(Files.newInputStream(archive))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Checks every regular entry body against the CRC-32 declared in ZIP metadata.
    private static void assertStoredCrcValues(ZipArkivoFileSystem fileSystem) throws IOException {
        Path root = fileSystem.getPath("/");
        try (var paths = Files.walk(root)) {
            var iterator = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        path,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.crc32() != ZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                    assertEquals(
                            attributes.crc32(),
                            ArchiveCorpusAssertions.crc32(Files.newInputStream(path)),
                            attributes.path()
                    );
                }
            }
        }
    }

    /// Asserts timestamps stored in one COMPRESS-210 entry.
    private static void assertExpectedTimes(
            String entryName,
            ExpectedTimes expected,
            ZipArkivoEntryAttributes attributes
    ) {
        FileTime expectedModifiedTime = FileTime.from(Instant.ofEpochSecond(expected.modifiedSeconds()));
        FileTime expectedAccessTime = FileTime.from(Instant.ofEpochSecond(expected.accessSeconds()));
        assertEquals(expectedModifiedTime, attributes.lastModifiedTime(), entryName);
        assertEquals(expectedAccessTime, attributes.lastAccessTime(), entryName);
        assertEquals(expectedModifiedTime, attributes.creationTime(), entryName);
    }

    /// Asserts numeric Unix identifiers and their synthesized principal names for one COMPRESS-211 entry.
    private static void assertExpectedUnixIdentifiers(ZipArkivoEntryAttributes attributes) {
        long expected = expectedUnixIdentifier(attributes.path());
        assertEquals(expected, attributes.userId(), attributes.path());
        assertEquals(expected, attributes.groupId(), attributes.path());
        assertEquals(Long.toString(expected), attributes.owner().getName(), attributes.path());
        assertEquals(Long.toString(expected), attributes.group().getName(), attributes.path());
    }

    /// Returns the numeric UID/GID encoded by one COMPRESS-211 entry name.
    private static long expectedUnixIdentifier(String path) {
        if (path.contains("uid555_gid555")) {
            return 555L;
        }
        if (path.contains("uid5555_gid5555")) {
            return 5_555L;
        }
        if (path.contains("uid55555_gid55555")) {
            return 55_555L;
        }
        if (path.contains("uid555555_gid555555")) {
            return 555_555L;
        }
        if (path.contains("min_unix")) {
            return 0L;
        }
        if (path.contains("max_unix")) {
            return 0xffff_fffeL;
        }
        return 1_000L;
    }

    /// Returns the final archive-path component, or the empty string for a trailing separator.
    private static String leafName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    /// Consumes a regular streaming entry without retaining its expanded body.
    private static void consumeRegularEntry(
            ZipArkivoStreamingReader reader,
            ZipArkivoEntryAttributes attributes
    ) throws IOException {
        if (attributes.isRegularFile()) {
            try (var input = reader.openInputStream()) {
                input.transferTo(OutputStream.nullOutputStream());
            }
        }
    }

    /// Checks ZIP metadata and consumes one entry without retaining its expanded body.
    private static void assertEntryStatisticsAndConsume(
            Path entry,
            long expectedSize,
            long expectedCompressedSize
    ) throws IOException {
        ZipArkivoEntryAttributes attributes = Files.readAttributes(entry, ZipArkivoEntryAttributes.class);
        assertEquals(expectedSize, attributes.size());
        assertEquals(expectedCompressedSize, attributes.compressedSize());
        try (var input = Files.newInputStream(entry)) {
            assertEquals(expectedSize, input.transferTo(OutputStream.nullOutputStream()));
        }
    }

    /// Discovers supported ordinary and conventionally split ZIP fixtures.
    private static Stream<String> readableArchives() throws IOException {
        return zipResources()
                .filter(resource -> !UNSUPPORTED_METHOD_ARCHIVES.contains(resource))
                .filter(resource -> !MALFORMED_ARCHIVES.contains(resource))
                .filter(resource -> !CORRUPT_DATA_DESCRIPTOR_ARCHIVES.contains(resource))
                .filter(resource -> !STREAMING_ONLY_ARCHIVES.contains(resource))
                .filter(resource -> !SPECIALIZED_ARCHIVES.contains(resource))
                .filter(resource -> !PASSWORD_ONLY_ARCHIVES.contains(resource));
    }

    /// Returns both independently produced Unicode-name archives.
    private static Stream<String> unicodeArchives() {
        return Stream.of("utf8-7zip-test.zip", "utf8-winzip-test.zip");
    }

    /// Returns samples with a PK00 marker, SFX preamble, or trailing bytes.
    private static Stream<String> preambleArchives() {
        return Stream.of(
                "COMPRESS-208.zip",
                "COMPRESS-621.zip",
                "archive_with_bytes_after_data.zip",
                "archive_with_trailer.zip"
        );
    }

    /// Returns supported non-Deflate compression fixtures and one representative entry name.
    private static Stream<Object @Unmodifiable []> codecArchives() {
        return Stream.of(
                new Object[]{"bzip2-zip.zip", "lots-of-as"},
                new Object[]{"COMPRESS-380/COMPRESS-380.zip", "input2"},
                new Object[]{"COMPRESS-692/compress-692.zip", "dolor.txt"},
                new Object[]{"org/apache/commons/compress/zip/test-method-xz.zip", "LICENSE.txt"}
        );
    }

    /// Returns all fixtures using Shrink or Implode.
    private static Stream<String> unsupportedMethodArchives() {
        return UNSUPPORTED_METHOD_ARCHIVES.stream().sorted();
    }

    /// Returns all known structurally invalid ZIP fixtures.
    private static Stream<String> malformedArchives() {
        return MALFORMED_ARCHIVES.stream().sorted();
    }

    /// Returns all fixtures with contradictory stored-entry descriptors.
    private static Stream<String> corruptDataDescriptorArchives() {
        return CORRUPT_DATA_DESCRIPTOR_ARCHIVES.stream().sorted();
    }

    /// Returns all fixtures intentionally lacking a complete central directory.
    private static Stream<String> streamingOnlyArchives() {
        return STREAMING_ONLY_ARCHIVES.stream().sorted();
    }

    /// Discovers ZIP resources relative to the official source resource root.
    private static Stream<String> zipResources() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".zip"))
                .sorted();
    }

    /// Discovers ZIP-based application containers covered by the generic corpus comparison.
    private static Stream<String> zipContainerResources() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .filter(path -> !NON_ZIP_CONTAINERS.contains(path))
                .filter(path -> {
                    String lowerCase = path.toLowerCase(Locale.ROOT);
                    return lowerCase.endsWith(".jar")
                            || lowerCase.endsWith(".apk")
                            || lowerCase.endsWith(".numbers");
                })
                .sorted();
    }

    /// Stores the signed Unix timestamps expected for one COMPRESS-210 entry.
    ///
    /// @param modifiedSeconds the expected modification timestamp in Unix seconds
    /// @param accessSeconds the expected access timestamp in Unix seconds
    @NotNullByDefault
    private record ExpectedTimes(long modifiedSeconds, long accessSeconds) {
    }
}
