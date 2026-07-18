// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies 7z codec, solid-folder, encrypted, multi-volume, metadata, and corruption fixtures.
@NotNullByDefault
final class SevenZipCommonsCompressCorpusTest {
    /// The number of simultaneous workers used by the COMPRESS-679 regression.
    private static final int CONCURRENT_WORKER_COUNT = 10;

    /// The total independent archive reads used by the COMPRESS-679 regression.
    private static final int CONCURRENT_READ_COUNT = 30;

    /// The bounded logical output accepted by the ordinary Tier 2 compatibility sweep.
    private static final long TIER2_MAXIMUM_ENTRY_SIZE = 64L * 1024L * 1024L;

    /// Read options that keep the ordinary compatibility sweep bounded independently of compressed input size.
    private static final SevenZipArchiveOptions.Read TIER2_READ_OPTIONS = SevenZipArchiveOptions.READ_DEFAULTS
            .withCommon(SevenZipArchiveOptions.READ_DEFAULTS.common().withLimits(
                    ArchiveReadLimits.builder()
                            .maximumEntrySize(TIER2_MAXIMUM_ENTRY_SIZE)
                            .maximumTotalEntrySize(2L * TIER2_MAXIMUM_ENTRY_SIZE)
                            .maximumDecoderMemorySize(256L * 1024L * 1024L)
                            .build()
            ));

    /// Structurally damaged 7z archives that must be rejected without unbounded allocation.
    private static final @Unmodifiable Set<String> MALFORMED_ARCHIVES = Set.of(
            "COMPRESS-542-1.7z",
            "COMPRESS-542-2.7z",
            "COMPRESS-542-endheadercorrupted.7z",
            "COMPRESS-542-endheadercorrupted2.7z",
            "bla.noendheaderoffset.7z"
    );

    /// A 7z fixture whose archive entries intentionally omit names and cannot be represented as NIO paths.
    private static final @Unmodifiable Set<String> UNSUPPORTED_ARCHIVES = Set.of("bla-nonames.7z");

    /// Fixtures covered by focused tests instead of the ordinary full-body sweep.
    private static final @Unmodifiable Set<String> SPECIALIZED_ARCHIVES = Set.of(
            "COMPRESS-256.7z",
            "COMPRESS-592.7z",
            "COMPRESS-681.7z",
            "bla.encrypted.7z",
            "times.7z"
    );

    /// Opens every supported regular-size 7z fixture and consumes all declared entry bodies.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveThroughSeekableFileSystem(String resource) throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource),
                TIER2_READ_OPTIONS
        )) {
            @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> entries =
                    ArchiveCorpusAssertions.readFileSystem(fileSystem);
            if (!resource.equals("COMPRESS-492.7z")) {
                assertFalse(entries.isEmpty(), "7z fixture must expose at least one entry");
            }
            assertStoredCrcValues(fileSystem);
        }
    }

    /// Verifies the non-default encoded-header dictionary and one representative body from COMPRESS-256.
    @Test
    void readsCompressedHeaderWithNonDefaultDictionarySize() throws IOException {
        String path = "/commons-compress-1.7-src/src/test/resources/test.txt";
        String line = "111111111111111111111111111000101011";
        String expected = (line + "\n").repeat(9) + line;
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-256.7z"),
                TIER2_READ_OPTIONS
        ); var paths = Files.walk(fileSystem.getPath("/"))) {
            assertEquals(446L, paths.filter(candidate -> !candidate.equals(fileSystem.getPath("/"))).count());
            assertEquals(expected, Files.readString(fileSystem.getPath(path), StandardCharsets.UTF_8));
        }
    }

    /// Verifies the large COMPRESS-592 archive is rejected quickly by a Tier 2 logical-output limit.
    @Test
    void largeArchiveHonorsTier2LogicalOutputLimit() throws IOException {
        SevenZipArchiveOptions.Read options = SevenZipArchiveOptions.READ_DEFAULTS.withCommon(
                SevenZipArchiveOptions.READ_DEFAULTS.common().withLimits(
                        ArchiveReadLimits.builder().maximumTotalEntrySize(1024L * 1024L).build()
                )
        );
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipArkivoFileSystem.open(
                        CommonsCompressTestResources.resource("COMPRESS-592.7z"),
                        options
                ).close()
        );
        ArkivoReadLimitException limitException = assertInstanceOf(ArkivoReadLimitException.class, exception);
        assertEquals(ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, limitException.kind());
    }

    /// Compares every COMPRESS-320 compression and solid-folder variant with the Copy reference archive.
    @ParameterizedTest(name = "{0}")
    @MethodSource("compress320Variants")
    void codecAndSolidVariantsPreserveContent(String fileName) throws IOException {
        @Unmodifiable Map<String, ArchiveCorpusAssertions.EntryDigest> reference =
                entryMap("COMPRESS-320/Copy.7z");
        @Unmodifiable Map<String, ArchiveCorpusAssertions.EntryDigest> actual =
                entryMap("COMPRESS-320/" + fileName);
        assertEquals(reference, actual);

        boolean expectedSolid = fileName.contains("-solid") && !fileName.startsWith("Copy");
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-320", fileName)
        ); var paths = Files.walk(fileSystem.getPath("/"))) {
            @Unmodifiable List<Path> regularFiles = paths.filter(Files::isRegularFile).toList();
            assertFalse(regularFiles.isEmpty());
            boolean actualSolid = false;
            for (Path path : regularFiles) {
                actualSolid |= Files.readAttributes(path, SevenZipArkivoEntryAttributes.class).solid();
            }
            assertEquals(expectedSolid, actualSolid);
        }
    }

    /// Reads the AES-wrapped Commons 7z fixture with its documented password.
    @Test
    void readsEncryptedArchive() throws IOException {
        byte @Unmodifiable [] password = "foo".getBytes(StandardCharsets.UTF_16LE);
        SevenZipArchiveOptions.Read options = SevenZipArchiveOptions.READ_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(password));
        try (SevenZipArkivoFileSystem encrypted = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("bla.encrypted.7z"),
                options
        ); SevenZipArkivoFileSystem plain = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("bla.7z")
        )) {
            assertEquals(
                    ArchiveCorpusAssertions.readFileSystem(plain),
                    ArchiveCorpusAssertions.readFileSystem(encrypted)
            );
        }
    }

    /// Reads the two-part 7z sample through the explicit volume abstraction.
    @Test
    void readsMultiVolumeArchive() throws IOException {
        @Unmodifiable List<Path> volumes = List.of(
                CommonsCompressTestResources.resource("bla-multi.7z.001"),
                CommonsCompressTestResources.resource("bla-multi.7z.002")
        );
        try (SevenZipArkivoFileSystem split = SevenZipArkivoFileSystem.open(ArkivoVolumeSource.of(volumes));
             SevenZipArkivoFileSystem plain = SevenZipArkivoFileSystem.open(
                     CommonsCompressTestResources.resource("bla.7z")
             )) {
            assertEquals(
                    ArchiveCorpusAssertions.readFileSystem(plain),
                    ArchiveCorpusAssertions.readFileSystem(split)
            );
        }
    }

    /// Verifies archive properties do not shift the first file entry in COMPRESS-681.
    @Test
    void readsArchivePropertiesFixture() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-681.7z")
        )) {
            assertEquals(
                    "https://issues.apache.org/jira/browse/COMPRESS-681",
                    Files.readString(fileSystem.getPath("/COMPRESS-681.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Opens COMPRESS-679 thirty times across ten workers and fully consumes its non-leading target entry.
    @Test
    void readsCompress679Concurrently() throws Exception {
        Path archive = CommonsCompressTestResources.resource(
                "org/apache/commons/compress/COMPRESS-679/file.7z"
        );
        long expectedCrc32;
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            expectedCrc32 = ArchiveCorpusAssertions.crc32(
                    Files.newInputStream(fileSystem.getPath("/file4.txt"))
            );
        }

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_WORKER_COUNT);
        try {
            List<Future<Long>> futures = new ArrayList<>(CONCURRENT_READ_COUNT);
            for (int index = 0; index < CONCURRENT_READ_COUNT; index++) {
                futures.add(executor.submit(() -> {
                    try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
                        return ArchiveCorpusAssertions.crc32(
                                Files.newInputStream(fileSystem.getPath("/file4.txt"))
                        );
                    }
                }));
            }
            for (Future<Long> future : futures) {
                assertEquals(expectedCrc32, future.get().longValue());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /// Repeats, reverses, and interleaves entry reads through Arkivo's independent NIO entry streams.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"bla.7z", "COMPRESS-348.7z"})
    void supportsRepeatedInterleavedAndReverseEntryReads(String resource) throws IOException {
        @Unmodifiable Map<String, ArchiveCorpusAssertions.EntryDigest> reference = entryMap(resource);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        ); var stream = Files.walk(fileSystem.getPath("/"))) {
            Path root = fileSystem.getPath("/");
            @Unmodifiable List<Path> regularFiles = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            assertFalse(regularFiles.isEmpty());

            for (int index = regularFiles.size() - 1; index >= 0; index--) {
                Path path = regularFiles.get(index);
                String entryPath = root.relativize(path).toString().replace('\\', '/');
                ArchiveCorpusAssertions.EntryDigest expected = java.util.Objects.requireNonNull(
                        reference.get(entryPath),
                        entryPath
                );
                assertEquals(expected.size(), Files.size(path), entryPath);
                assertEquals(expected.crc32(), ArchiveCorpusAssertions.crc32(Files.newInputStream(path)), entryPath);
                assertEquals(expected.crc32(), ArchiveCorpusAssertions.crc32(Files.newInputStream(path)), entryPath);
            }

            Path first = regularFiles.get(0);
            Path last = regularFiles.get(regularFiles.size() - 1);
            byte @Unmodifiable [] expectedFirst = Files.readAllBytes(first);
            byte @Unmodifiable [] expectedLast = Files.readAllBytes(last);
            ByteArrayOutputStream actualFirst = new ByteArrayOutputStream(expectedFirst.length);
            ByteArrayOutputStream actualLast = new ByteArrayOutputStream(expectedLast.length);
            try (InputStream firstInput = Files.newInputStream(first);
                 InputStream lastInput = Files.newInputStream(last)) {
                while (true) {
                    byte @Unmodifiable [] firstChunk = firstInput.readNBytes(17);
                    byte @Unmodifiable [] lastChunk = lastInput.readNBytes(19);
                    actualFirst.write(firstChunk);
                    actualLast.write(lastChunk);
                    if (firstChunk.length == 0 && lastChunk.length == 0) {
                        break;
                    }
                }
            }
            assertArrayEquals(expectedFirst, actualFirst.toByteArray());
            assertArrayEquals(expectedLast, actualLast.toByteArray());
        }
    }

    /// Verifies COMPRESS-348 exposes exactly five entries and preserves both zero-length members.
    @Test
    void readsCompress348ZeroLengthEntries() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-348.7z")
        ); var paths = Files.walk(fileSystem.getPath("/"))) {
            Path root = fileSystem.getPath("/");
            @Unmodifiable List<Path> entries = paths
                    .filter(path -> !path.equals(root))
                    .toList();
            assertEquals(5, entries.size());
            assertEquals(0L, Files.size(fileSystem.getPath("/2.txt")));
            assertEquals(0L, Files.size(fileSystem.getPath("/5.txt")));
            for (Path entry : entries) {
                if (!entry.endsWith("2.txt") && !entry.endsWith("5.txt")) {
                    assertTrue(Files.size(entry) > 0L, entry.toString());
                }
            }
        }
    }

    /// Verifies creation, access, and modification times survive the 7z metadata parser.
    @Test
    void readsNanosecondEntryTimes() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource("times.7z")
        )) {
            BasicFileAttributes directory = Files.readAttributes(
                    fileSystem.getPath("/test"),
                    BasicFileAttributes.class
            );
            assertEquals(FileTime.from(Instant.parse("2022-03-21T14:50:46.209975100Z")), directory.lastModifiedTime());
            assertEquals(FileTime.from(Instant.parse("2022-03-21T14:50:46.209975100Z")), directory.lastAccessTime());
            assertEquals(FileTime.from(Instant.parse("2022-03-16T10:19:24.105111500Z")), directory.creationTime());

            BasicFileAttributes file = Files.readAttributes(
                    fileSystem.getPath("/test/test-times.txt"),
                    BasicFileAttributes.class
            );
            assertEquals(FileTime.from(Instant.parse("2022-03-18T10:00:15Z")), file.lastModifiedTime());
            assertEquals(FileTime.from(Instant.parse("2022-03-18T10:14:37.813000200Z")), file.lastAccessTime());
            assertEquals(FileTime.from(Instant.parse("2022-03-18T10:14:37.811003200Z")), file.creationTime());

            BasicFileAttributes secondFile = Files.readAttributes(
                    fileSystem.getPath("/test/test-times2.txt"),
                    BasicFileAttributes.class
            );
            assertEquals(FileTime.from(Instant.parse("2022-03-18T10:00:19Z")), secondFile.lastModifiedTime());
            assertEquals(
                    FileTime.from(Instant.parse("2022-03-18T10:14:37.817003800Z")),
                    secondFile.lastAccessTime()
            );
            assertEquals(
                    FileTime.from(Instant.parse("2022-03-18T10:14:37.814000400Z")),
                    secondFile.creationTime()
            );
        }
    }

    /// Rejects damaged start and end headers through the public file-system entry point.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedArchives")
    void rejectsMalformedArchives(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertThrows(IOException.class, () -> {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Makes unnamed 7z entries an explicit unsupported NIO mapping rather than silently skipping the fixture.
    @Test
    void rejectsEntriesWithoutNames() throws IOException {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipArkivoFileSystem.open(
                        CommonsCompressTestResources.resource("bla-nonames.7z")
                ).close()
        );
        assertTrue(exception.getMessage().toLowerCase(Locale.ROOT).contains("name"), exception.getMessage());
    }

    /// Reads one archive and indexes its deterministic entry digests by normalized path.
    private static @Unmodifiable Map<String, ArchiveCorpusAssertions.EntryDigest> entryMap(
            String resource
    ) throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            return ArchiveCorpusAssertions.readFileSystem(fileSystem).stream().collect(Collectors.toUnmodifiableMap(
                    ArchiveCorpusAssertions.EntryDigest::path,
                    Function.identity()
            ));
        }
    }

    /// Checks decoded content against every available per-entry CRC-32.
    private static void assertStoredCrcValues(SevenZipArkivoFileSystem fileSystem) throws IOException {
        Path root = fileSystem.getPath("/");
        try (var paths = Files.walk(root)) {
            var iterator = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                        path,
                        SevenZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.crc32() != SevenZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                    assertEquals(
                            attributes.crc32(),
                            ArchiveCorpusAssertions.crc32(Files.newInputStream(path)),
                            attributes.path()
                    );
                }
            }
        }
    }

    /// Discovers all supported ordinary 7z fixtures not covered by specialized tests.
    private static Stream<String> readableArchives() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".7z"))
                .filter(path -> !MALFORMED_ARCHIVES.contains(path))
                .filter(path -> !UNSUPPORTED_ARCHIVES.contains(path))
                .filter(path -> !SPECIALIZED_ARCHIVES.contains(path))
                .filter(path -> !path.startsWith("COMPRESS-320/"))
                .sorted();
    }

    /// Returns all COMPRESS-320 algorithms in solid and independent-folder forms.
    private static Stream<String> compress320Variants() {
        return Stream.of(
                "BZip2-solid.7z",
                "BZip2.7z",
                "Copy-solid.7z",
                "Copy.7z",
                "Deflate-solid.7z",
                "Deflate.7z",
                "LZMA-solid.7z",
                "LZMA.7z",
                "LZMA2-solid.7z",
                "LZMA2.7z",
                "PPMd-solid.7z",
                "PPMd.7z"
        );
    }

    /// Returns every known damaged 7z fixture.
    private static Stream<String> malformedArchives() {
        return MALFORMED_ARCHIVES.stream().sorted();
    }
}
