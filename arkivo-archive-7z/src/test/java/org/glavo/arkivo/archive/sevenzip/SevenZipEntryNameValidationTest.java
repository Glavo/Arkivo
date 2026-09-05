// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies 7z entry-name normalization and read/write path validation.
@NotNullByDefault
final class SevenZipEntryNameValidationTest {
    /// Verifies dot segments and repeated separators are normalized without replacing stored metadata.
    @Test
    void normalizesSafeNamesWithoutDiscardingStoredMetadata() throws IOException {
        byte[] archive = SevenZipTestArchiveFixtures.emptyFileArchive("dir//./hello.txt");

        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
             SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(channel)) {
            var path = fileSystem.getPath("/dir/hello.txt");
            SevenZipArkivoEntryAttributes attributes =
                    Files.readAttributes(path, SevenZipArkivoEntryAttributes.class);

            assertEquals("dir//./hello.txt", attributes.path());
            assertEquals(0L, attributes.size());
            assertArrayEquals(new byte[0], Files.readAllBytes(path));
        }
    }

    /// Verifies unsafe entry names are rejected before a file system is exposed.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param path the stored 7z entry path
    /// @param expectedMessage the diagnostic fragment required from the indexed reader
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeEntryNames")
    void rejectsUnsafeNames(String description, String path, String expectedMessage) {
        IOException failure = assertIndexedReadRejected(SevenZipTestArchiveFixtures.emptyFileArchive(path));
        assertTrue(failure.getMessage().contains(expectedMessage), description);
    }

    /// Verifies the streaming writer rejects unsafe paths without leaving an entry pending.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param path the unsafe logical entry path
    /// @param expectedMessage the diagnostic fragment required from the writer
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeWriterEntryPaths")
    void rejectsUnsafeWriterPathsWithoutPoisoningWriter(
            String description,
            String path,
            String expectedMessage
    ) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archive)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.beginFile(path)
            );
            assertTrue(failure.getMessage().contains(expectedMessage), description);

            try (var output = writer.beginFile("safe.txt").openOutputStream()) {
                output.write('x');
            }
        }

        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive.toByteArray());
             SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(channel)) {
            assertArrayEquals(new byte[]{'x'}, Files.readAllBytes(fileSystem.getPath("/safe.txt")));
        }
    }

    /// Verifies exact, normalized, and file-parent path conflicts are rejected while indexing.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param firstPath the first stored 7z entry path
    /// @param secondPath the conflicting second stored path
    /// @param expectedMessage the diagnostic fragment required from the indexed reader
    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingEntryNames")
    void rejectsConflictingNames(
            String description,
            String firstPath,
            String secondPath,
            String expectedMessage
    ) {
        byte[] archive = SevenZipTestArchiveFixtures.emptyFileArchive(firstPath, secondPath);
        IOException failure = assertIndexedReadRejected(archive);
        assertTrue(failure.getMessage().contains(expectedMessage), description);
    }

    /// Returns unsafe names and their stable validation diagnostics.
    private static Stream<Arguments> unsafeEntryNames() {
        return Stream.of(
                Arguments.of("parent segment", "../evil.txt", "7z entry path must not contain .."),
                Arguments.of("absolute", "/evil.txt", "7z entry path must be relative"),
                Arguments.of("drive root", "C:/evil.txt", "7z entry path must be relative"),
                Arguments.of("backslash parent segment", "..\\evil.txt", "7z entry path must not contain ..")
        );
    }

    /// Returns unsafe logical paths and their stable streaming-writer diagnostics.
    private static Stream<Arguments> unsafeWriterEntryPaths() {
        return Stream.of(
                Arguments.of("empty", "", "must not be empty"),
                Arguments.of("dot only", ".", "must not be empty"),
                Arguments.of("absolute", "/evil.txt", "must be relative"),
                Arguments.of("backslash absolute", "\\evil.txt", "must be relative"),
                Arguments.of("drive root", "C:/evil.txt", "must not contain drive roots"),
                Arguments.of("backslash drive root", "C:\\evil.txt", "must not contain drive roots"),
                Arguments.of("parent segment", "safe/../evil.txt", "must not contain ..")
        );
    }

    /// Returns path pairs that conflict after archive-path normalization.
    private static Stream<Arguments> conflictingEntryNames() {
        return Stream.of(
                Arguments.of("exact duplicate", "dir/hello.txt", "dir/hello.txt", "Duplicate 7z entry path"),
                Arguments.of(
                        "dot and repeated separators",
                        "dir//hello.txt",
                        "dir/./hello.txt",
                        "Duplicate 7z entry path"
                ),
                Arguments.of(
                        "regular file used as parent",
                        "dir",
                        "dir/file.txt",
                        "7z entry path conflicts with directory"
                ),
                Arguments.of(
                        "implicit directory replaced by regular file",
                        "dir/file.txt",
                        "dir",
                        "7z entry path conflicts with directory"
                )
        );
    }

    /// Forces indexed metadata parsing and returns its validation failure.
    private static IOException assertIndexedReadRejected(byte[] archive) {
        return assertThrows(IOException.class, () -> {
            try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
                 SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.open(channel)) {
                // Opening a 7z file system eagerly indexes every entry name.
            }
        });
    }
}
