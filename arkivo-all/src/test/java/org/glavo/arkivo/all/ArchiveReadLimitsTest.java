// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies common archive read limits across independently generated archive formats.
@NotNullByDefault
final class ArchiveReadLimitsTest {
    /// The first entry body.
    private static final byte @Unmodifiable [] FIRST_CONTENT = {1, 2, 3};

    /// The second entry body.
    private static final byte @Unmodifiable [] SECOND_CONTENT = {4, 5, 6, 7};

    /// Verifies entry-count, per-entry-size, and total-size limits across random-access file systems.
    @Test
    void fileSystemsEnforceCommonReadLimits() throws IOException {
        List<Fixture> fixtures = createFixtures();
        try {
            for (Fixture fixture : fixtures) {
                assertFileSystemLimit(
                        fixture,
                        ArchiveReadLimits.builder().maximumEntryCount(1L).build(),
                        1L,
                        ArkivoReadLimitKind.ENTRY_COUNT,
                        2L,
                        null
                );
                assertFileSystemLimit(
                        fixture,
                        ArchiveReadLimits.builder().maximumEntrySize(3L).build(),
                        3L,
                        ArkivoReadLimitKind.ENTRY_SIZE,
                        4L,
                        "second.bin"
                );
                assertFileSystemLimit(
                        fixture,
                        ArchiveReadLimits.builder().maximumTotalEntrySize(6L).build(),
                        6L,
                        ArkivoReadLimitKind.TOTAL_ENTRY_SIZE,
                        7L,
                        "second.bin"
                );
            }
        } finally {
            deleteFixtures(fixtures);
        }
    }

    /// Verifies entry-count limits across forward-only AR, TAR, and ZIP readers.
    @Test
    void streamingReadersEnforceEntryCount() throws IOException {
        List<Fixture> fixtures = createFixtures();
        try {
            for (Fixture fixture : fixtures) {
                if (fixture.format().equals("7z")) {
                    continue;
                }
                try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                        fixture.format(),
                        fixture.path(),
                        readOptions(ArchiveReadLimits.builder().maximumEntryCount(1L).build())
                )) {
                    var readerEntry102 = java.util.Objects.requireNonNull(reader.nextEntry());
                    try (InputStream input = readerEntry102.openInputStream()) {
                        assertArrayEquals(FIRST_CONTENT, input.readAllBytes());
                    }
                    ArkivoReadLimitException exception = assertThrows(
                            ArkivoReadLimitException.class,
                            reader::nextEntry
                    );
                    assertLimit(exception, ArkivoReadLimitKind.ENTRY_COUNT, 1L, 2L, null);
                }
            }
        } finally {
            deleteFixtures(fixtures);
        }
    }

    /// Verifies unknown ZIP data-descriptor sizes are limited by observed decoded bytes.
    @Test
    void streamingZipEnforcesObservedEntryAndTotalSizes() throws IOException {
        Fixture fixture = createZipFixture();
        try {
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                    "zip",
                    fixture.path(),
                    readOptions(ArchiveReadLimits.builder().maximumEntrySize(2L).build())
            )) {
                var readerEntry128 = java.util.Objects.requireNonNull(reader.nextEntry());
                ArkivoReadLimitException exception = assertThrows(
                        ArkivoReadLimitException.class,
                        () -> readCurrentEntry(readerEntry128)
                );
                assertLimit(exception, ArkivoReadLimitKind.ENTRY_SIZE, 2L, 3L, "first.bin");
            }

            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                    "zip",
                    fixture.path(),
                    readOptions(ArchiveReadLimits.builder().maximumTotalEntrySize(5L).build())
            )) {
                var readerEntry141 = java.util.Objects.requireNonNull(reader.nextEntry());
                assertArrayEquals(FIRST_CONTENT, readCurrentEntry(readerEntry141));
                var readerEntry143 = java.util.Objects.requireNonNull(reader.nextEntry());
                ArkivoReadLimitException exception = assertThrows(
                        ArkivoReadLimitException.class,
                        () -> readCurrentEntry(readerEntry143)
                );
                assertLimit(exception, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, 5L, 7L, "second.bin");
            }
        } finally {
            Files.deleteIfExists(fixture.path());
        }
    }

    /// Verifies every random-access archive format rejects metadata beyond the common budget.
    @Test
    void fileSystemsEnforceMetadataSize() throws IOException {
        List<Fixture> fixtures = createFixtures();
        try {
            for (Fixture fixture : fixtures) {
                long maximum = switch (fixture.format()) {
                    case "ar" -> 7L;
                    case "tar" -> 511L;
                    case "7z" -> 32L;
                    default -> 1L;
                };
                ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, () -> {
                    try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                            fixture.format(),
                            fixture.path(),
                            readOptions(ArchiveReadLimits.builder().maximumMetadataSize(maximum).build())
                    )) {
                        Files.readAttributes(fileSystem.getPath("/first.bin"), BasicFileAttributes.class);
                    }
                });
                assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
                assertEquals(maximum, exception.maximum());
                assertTrue(exception.actual() > maximum);
                assertNull(exception.entryPath());
            }
        } finally {
            deleteFixtures(fixtures);
        }
    }

    /// Verifies forward-only AR, TAR, and ZIP readers enforce metadata before exposing an entry.
    @Test
    void streamingReadersEnforceMetadataSize() throws IOException {
        List<Fixture> fixtures = createFixtures();
        try {
            for (Fixture fixture : fixtures) {
                if (fixture.format().equals("7z")) {
                    continue;
                }
                long maximum = switch (fixture.format()) {
                    case "ar" -> 7L;
                    case "tar" -> 511L;
                    default -> 30L;
                };
                try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                        fixture.format(),
                        fixture.path(),
                        readOptions(ArchiveReadLimits.builder().maximumMetadataSize(maximum).build())
                )) {
                    ArkivoReadLimitException exception = assertThrows(
                            ArkivoReadLimitException.class,
                            reader::nextEntry
                    );
                    assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
                    assertEquals(maximum, exception.maximum());
                    assertTrue(exception.actual() > maximum);
                    assertNull(exception.entryPath());
                }
            }
        } finally {
            deleteFixtures(fixtures);
        }
    }

    /// Opens a fixture file system and verifies the expected read-limit failure.
    private static void assertFileSystemLimit(
            Fixture fixture,
            ArchiveReadLimits limits,
            long maximum,
            ArkivoReadLimitKind kind,
            long actual,
            @Nullable String expectedPath
    ) {
        ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, () -> {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                    fixture.format(),
                    fixture.path(),
                    readOptions(limits)
            )) {
                Files.readAttributes(fileSystem.getPath("/first.bin"), BasicFileAttributes.class);
            }
        });
        assertLimit(exception, kind, maximum, actual, expectedPath);
    }

    /// Returns archive options carrying one immutable read-limit set.
    private static ArchiveReadOptions readOptions(ArchiveReadLimits limits) {
        return ArchiveReadOptions.DEFAULT.withLimits(limits);
    }

    /// Verifies all structured fields of a read-limit exception.
    private static void assertLimit(
            ArkivoReadLimitException exception,
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String expectedPath
    ) {
        assertEquals(kind, exception.kind());
        assertEquals(maximum, exception.maximum());
        assertEquals(actual, exception.actual());
        if (expectedPath == null) {
            assertNull(exception.entryPath());
        } else {
            assertEquals(expectedPath, exception.entryPath());
        }
    }

    /// Reads and closes the current streaming entry body.
    private static byte[] readCurrentEntry(ArkivoStreamingReader.Entry entry) throws IOException {
        try (InputStream input = entry.openInputStream()) {
            return input.readAllBytes();
        }
    }

    /// Creates one independently generated fixture for each writable test format.
    private static List<Fixture> createFixtures() throws IOException {
        ArrayList<Fixture> fixtures = new ArrayList<>();
        try {
            fixtures.add(createArFixture());
            fixtures.add(createTarFixture());
            fixtures.add(createZipFixture());
            fixtures.add(createSevenZipFixture());
            return List.copyOf(fixtures);
        } catch (IOException | RuntimeException | Error exception) {
            deleteFixtures(fixtures);
            throw exception;
        }
    }

    /// Creates an AR fixture with two regular files.
    private static Fixture createArFixture() throws IOException {
        Path path = Files.createTempFile("arkivo-read-limits-", ".a");
        try (ArArchiveOutputStream output = new ArArchiveOutputStream(Files.newOutputStream(path))) {
            writeArEntry(output, "first.bin", FIRST_CONTENT);
            writeArEntry(output, "second.bin", SECOND_CONTENT);
        }
        return new Fixture("ar", path);
    }

    /// Writes one AR fixture entry.
    private static void writeArEntry(ArArchiveOutputStream output, String name, byte[] content) throws IOException {
        output.putArchiveEntry(new ArArchiveEntry(name, content.length));
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Creates a TAR fixture with two regular files.
    private static Fixture createTarFixture() throws IOException {
        Path path = Files.createTempFile("arkivo-read-limits-", ".tar");
        try (TarArchiveOutputStream output = new TarArchiveOutputStream(Files.newOutputStream(path))) {
            writeTarEntry(output, "first.bin", FIRST_CONTENT);
            writeTarEntry(output, "second.bin", SECOND_CONTENT);
        }
        return new Fixture("tar", path);
    }

    /// Writes one TAR fixture entry.
    private static void writeTarEntry(TarArchiveOutputStream output, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Creates a non-seekable ZIP fixture whose local headers use data descriptors.
    private static Fixture createZipFixture() throws IOException {
        Path path = Files.createTempFile("arkivo-read-limits-", ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(path))) {
            writeZipEntry(output, "first.bin", FIRST_CONTENT);
            writeZipEntry(output, "second.bin", SECOND_CONTENT);
        }
        return new Fixture("zip", path);
    }

    /// Writes one ZIP fixture entry without a declared local-header size.
    private static void writeZipEntry(ZipArchiveOutputStream output, String name, byte[] content) throws IOException {
        output.putArchiveEntry(new ZipArchiveEntry(name));
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Creates a 7z fixture with two regular files.
    private static Fixture createSevenZipFixture() throws IOException {
        Path path = Files.createTempFile("arkivo-read-limits-", ".7z");
        try (SevenZOutputFile output = new SevenZOutputFile(Files.newByteChannel(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            output.setContentCompression(SevenZMethod.LZMA2);
            writeSevenZipEntry(output, "first.bin", FIRST_CONTENT);
            writeSevenZipEntry(output, "second.bin", SECOND_CONTENT);
        }
        return new Fixture("7z", path);
    }

    /// Writes one 7z fixture entry.
    private static void writeSevenZipEntry(SevenZOutputFile output, String name, byte[] content) throws IOException {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setDirectory(false);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Deletes every fixture path while preserving the first cleanup failure.
    private static void deleteFixtures(List<Fixture> fixtures) throws IOException {
        IOException failure = null;
        for (Fixture fixture : fixtures) {
            try {
                Files.deleteIfExists(fixture.path());
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Identifies one generated archive fixture.
    ///
    /// @param format the Arkivo format name
    /// @param path   the generated archive path
    private record Fixture(String format, Path path) {
    }
}
