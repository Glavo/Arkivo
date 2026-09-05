// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR entry-name validation, normalization, and indexed-path conflicts.
@NotNullByDefault
final class RarEntryNameValidationTest {
    /// Verifies indexed normalization does not replace the decoded path exposed as archive metadata.
    @Test
    void normalizesSafeNamesWithoutDiscardingStoredMetadata() throws IOException {
        String storedPath = "dir\\/./nested//value.txt";
        byte[] archive = RarTestArchiveFixtures.emptyStoredArchive(storedPath);

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals(storedPath, reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }
            assertFalse(reader.next());
        }

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            var path = fileSystem.getPath("/dir/nested/value.txt");
            RarArkivoEntryAttributes attributes = Files.readAttributes(path, RarArkivoEntryAttributes.class);
            assertEquals(storedPath, attributes.path());
            assertEquals(0L, attributes.size());
            assertArrayEquals(new byte[0], Files.readAllBytes(path));
        }
    }

    /// Verifies streaming and indexed readers enforce the same unsafe-name boundary.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param path the stored RAR entry path
    /// @param expectedMessage the diagnostic fragment required from both APIs
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeEntryNames")
    void rejectsUnsafeNamesThroughBothReaderModels(
            String description,
            String path,
            String expectedMessage
    ) throws IOException {
        byte[] archive = RarTestArchiveFixtures.emptyStoredArchive(path);

        IOException streamingFailure;
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            streamingFailure = assertThrows(IOException.class, reader::next);
        }
        assertTrue(streamingFailure.getMessage().contains(expectedMessage), description);

        IOException indexedFailure = assertIndexedReadRejected(archive);
        assertTrue(indexedFailure.getMessage().contains(expectedMessage), description);
    }

    /// Verifies path collisions are rejected while constructing an indexed file system.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param firstPath the first stored RAR entry path
    /// @param secondPath the conflicting second stored path
    /// @param expectedMessage the diagnostic fragment required from the indexed reader
    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingEntryNames")
    void rejectsConflictingIndexedNames(
            String description,
            String firstPath,
            String secondPath,
            String expectedMessage
    ) throws IOException {
        byte[] archive = RarTestArchiveFixtures.emptyStoredArchive(firstPath, secondPath);
        IOException failure = assertIndexedReadRejected(archive);
        assertTrue(failure.getMessage().contains(expectedMessage), description);
    }

    /// Returns unsafe names and their stable validation diagnostics.
    private static Stream<Arguments> unsafeEntryNames() {
        return Stream.of(
                Arguments.of("empty", "", "RAR entry is missing a path"),
                Arguments.of("dot only", ".", "RAR entry is missing a path"),
                Arguments.of("parent segment", "../evil.txt", "RAR entry path must not contain .."),
                Arguments.of("nested parent segment", "safe/../evil.txt", "RAR entry path must not contain .."),
                Arguments.of("backslash parent segment", "safe\\..\\evil.txt", "RAR entry path must not contain .."),
                Arguments.of("absolute", "/evil.txt", "RAR entry path must be relative"),
                Arguments.of("backslash absolute", "\\evil.txt", "RAR entry path must be relative"),
                Arguments.of("drive root", "C:/evil.txt", "RAR entry path must be relative")
        );
    }

    /// Returns path pairs expected to collide after indexed-path normalization.
    private static Stream<Arguments> conflictingEntryNames() {
        return Stream.of(
                Arguments.of("exact duplicate", "duplicate.txt", "duplicate.txt", "Duplicate RAR entry path"),
                Arguments.of(
                        "dot and repeated separators",
                        "dir/hello.txt",
                        "dir//./hello.txt",
                        "Duplicate RAR entry path"
                ),
                Arguments.of(
                        "mixed separators",
                        "dir/file.txt",
                        "dir\\file.txt",
                        "Duplicate RAR entry path"
                ),
                Arguments.of(
                        "regular file used as parent",
                        "dir",
                        "dir/file.txt",
                        "RAR entry path conflicts with directory"
                ),
                Arguments.of(
                        "implicit directory replaced by regular file",
                        "dir/file.txt",
                        "dir",
                        "RAR entry path conflicts with directory"
                )
        );
    }

    /// Opens an in-memory indexed archive and returns its validation failure.
    private static IOException assertIndexedReadRejected(byte[] archive) {
        return assertThrows(IOException.class, () -> {
            try (RarArkivoFileSystem ignored = RarArkivoFileSystem.open(
                    new ReadOnlyByteArrayChannel(archive)
            )) {
                // Opening a RAR file system eagerly indexes every entry name.
            }
        });
    }
}
