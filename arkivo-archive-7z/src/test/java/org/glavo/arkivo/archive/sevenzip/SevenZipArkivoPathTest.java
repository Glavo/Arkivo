// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests lexical path behavior exposed by the 7z file-system provider.
@NotNullByDefault
public final class SevenZipArkivoPathTest {
    /// The isolated directory used for the archive fixture.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies path navigation, normalization, relativization, and matching.
    @Test
    public void supportsLexicalPathOperations() throws IOException {
        Path archivePath = Files.write(
                temporaryDirectory.resolve("minimal.7z"),
                SevenZipTestArchiveFixtures.minimalArchive()
        );

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
            Path path = fileSystem.getPath("/a/b/../c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals("/a/c.txt", path.normalize().toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(path));
        }
    }
}
