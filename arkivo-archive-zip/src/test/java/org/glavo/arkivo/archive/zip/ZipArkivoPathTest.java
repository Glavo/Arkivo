// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the lexical and provider-specific behavior of paths created by a ZIP file system.
@NotNullByDefault
final class ZipArkivoPathTest {
    /// Provides an isolated backing path that does not require archive parsing.
    @TempDir
    Path temporaryDirectory;

    /// Verifies ZIP paths implement the core NIO path operations without reading archive entries.
    @Test
    void implementsLexicalPathOperations() throws IOException {
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(temporaryDirectory.resolve("missing.zip"))) {
            Path path = fileSystem.getPath("/a/b/../c.txt");
            Path normalized = fileSystem.getPath("/a/c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals(fileSystem, path.getFileSystem());
            assertTrue(path.isAbsolute());
            assertEquals("/", path.getRoot().toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals(4, path.getNameCount());
            assertEquals("b", path.getName(1).toString());
            assertEquals("b/..", path.subpath(1, 3).toString());
            assertThrows(IllegalArgumentException.class, () -> path.getName(-1));
            assertThrows(IllegalArgumentException.class, () -> path.getName(path.getNameCount()));
            assertThrows(IllegalArgumentException.class, () -> path.subpath(-1, 1));
            assertThrows(IllegalArgumentException.class, () -> path.subpath(1, 1));
            assertThrows(IllegalArgumentException.class, () -> path.subpath(0, path.getNameCount() + 1));
            assertTrue(path.startsWith("/a"));
            assertTrue(path.endsWith("c.txt"));
            assertFalse(path.endsWith("/c.txt"));
            assertTrue(normalized.endsWith("/a/c.txt"));
            assertFalse(normalized.endsWith("/c.txt"));
            assertFalse(normalized.endsWith("/"));
            assertEquals(normalized, path.normalize());
            assertEquals("/a/child", fileSystem.getPath("/a").resolve("child").toString());
            assertEquals("/a/child", normalized.resolveSibling("child").toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
            assertEquals("/relative", fileSystem.getPath("relative").toAbsolutePath().toString());
            assertEquals(0, normalized.compareTo(fileSystem.getPath("/a/c.txt")));
            assertEquals(
                    List.of("a", "b", "..", "c.txt"),
                    StreamSupport.stream(path.spliterator(), false).map(Path::toString).toList()
            );
            assertFalse(path.startsWith(Path.of("/a")));
            assertThrows(ProviderMismatchException.class, () -> path.resolve(Path.of("other")));
            assertThrows(ProviderMismatchException.class, () -> path.relativize(Path.of("other")));
            assertThrows(ClassCastException.class, () -> path.compareTo(Path.of("other")));
        }
    }
}
