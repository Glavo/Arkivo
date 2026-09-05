// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies path-backed archive creation through the aggregate [ArkivoFormats] facade.
@NotNullByDefault
final class ArkivoFormatsPathCreationTest {
    /// Content written through every created archive file system.
    private static final byte @Unmodifiable [] CONTENT =
            "aggregate path creation".getBytes(StandardCharsets.UTF_8);

    /// Per-test directory managed by JUnit.
    @TempDir
    Path temporaryDirectory;

    /// Verifies the default overload creates every installed writable path-backed format.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"7z", "ar", "tar", "zip"})
    void createsEveryWritablePathFormat(String formatName) throws IOException {
        Path archive = temporaryDirectory.resolve("archive." + formatName);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.createFileSystem(formatName, archive)) {
            populate(fileSystem, formatName);
        }

        assertTrue(Files.size(archive) > 0L, formatName);
        assertArchiveContent(archive, formatName);
    }

    /// Verifies aliases and explicit creation options are honored by the aggregate facade.
    @Test
    void createsAliasWithExplicitOptions() throws IOException {
        Path archive = temporaryDirectory.resolve("archive.7z");
        ArchiveCreateOptions options = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);

        try (ArkivoFileSystem fileSystem = ArkivoFormats.createFileSystem("sevenzip", archive, options)) {
            populate(fileSystem, "sevenzip");
        }

        assertArchiveContent(archive, "sevenzip");
    }

    /// Verifies lookup failures occur before a destination path is created.
    @Test
    void rejectsUnavailableFormatsWithoutTouchingDestination() {
        Path unknownArchive = temporaryDirectory.resolve("unknown.archive");
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.createFileSystem("missing", unknownArchive)
        );
        assertFalse(Files.exists(unknownArchive));

        Path streamingOnlyArchive = temporaryDirectory.resolve("archive.cpio");
        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.createFileSystem("cpio", streamingOnlyArchive)
        );
        assertFalse(Files.exists(streamingOnlyArchive));

        Path readOnlyArchive = temporaryDirectory.resolve("archive.rar");
        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.createFileSystem("rar", readOnlyArchive)
        );
        assertFalse(Files.exists(readOnlyArchive));
    }

    /// Populates one writable archive file system with the common test entry.
    private static void populate(ArkivoFileSystem fileSystem, String description) throws IOException {
        assertFalse(fileSystem.isReadOnly(), description);
        Files.write(fileSystem.getPath("/value.txt"), CONTENT);
    }

    /// Reopens one created archive through format detection and verifies its content.
    private static void assertArchiveContent(Path archive, String description) throws IOException {
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            assertArrayEquals(
                    CONTENT,
                    Files.readAllBytes(fileSystem.getPath("/value.txt")),
                    description
            );
        }
    }
}
