// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;
import java.util.zip.ZipOutputStream;

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

    /// Compares lexical operation combinations covered by OpenJDK zipfs `PathOps` with the running JDK provider.
    @ParameterizedTest
    @ValueSource(strings = {"", "/", ".", "..", "/..", "/./.", "a", "/a", "a/", "a/b", "/a/b",
            "//a//b/", "a/./b", "a/b/..", "../a", "../../a", "/a/b/c", "a/b/c"})
    void matchesJdkLexicalOperations(String name) throws IOException {
        Path archive = temporaryDirectory.resolve("paths.zip");
        try (var ignored = new ZipOutputStream(Files.newOutputStream(archive))) {
        }
        try (var fs = ZipArkivoFileSystem.open(archive); var reference = FileSystems.newFileSystem(archive)) {
            Path path = fs.getPath(name);
            Path expected = reference.getPath(name);
            assertEquals(expected.toString(), path.toString());
            assertEquals(expected.isAbsolute(), path.isAbsolute());
            assertEquals(expected.getNameCount(), path.getNameCount());
            assertEquals(Objects.toString(expected.getRoot()), Objects.toString(path.getRoot()));
            assertEquals(Objects.toString(expected.getParent()), Objects.toString(path.getParent()));
            // Older JDK zipfs versions mishandle empty names; use the Path contract for those cases.
            if (name.isEmpty()) {
                assertEquals("", path.getFileName().toString());
                assertEquals("", path.getName(0).toString());
                assertEquals("", path.subpath(0, 1).toString());
            } else {
                assertEquals(Objects.toString(expected.getFileName()), Objects.toString(path.getFileName()));
            }
            assertEquals(expected.normalize().toString(), path.normalize().toString());
            for (int start = 0; !name.isEmpty() && start < expected.getNameCount(); start++) {
                assertEquals(expected.getName(start).toString(), path.getName(start).toString());
                for (int end = start + 1; end <= expected.getNameCount(); end++) {
                    assertEquals(expected.subpath(start, end).toString(), path.subpath(start, end).toString());
                }
            }
            for (String other : List.of("", "/", "a", "ab", "/a", "b", "a/b", "/a/b", "b/", "../c")) {
                Path operand = fs.getPath(other);
                Path referenceOperand = reference.getPath(other);
                String context = name + " against " + other;
                if (name.isEmpty() || other.isEmpty()) {
                    assertEquals(name.isEmpty() && other.isEmpty(), path.startsWith(operand), context);
                    assertEquals(name.isEmpty() && other.isEmpty(), path.endsWith(operand), context);
                } else {
                    assertEquals(expected.startsWith(referenceOperand), path.startsWith(operand), context);
                    assertEquals(expected.endsWith(referenceOperand), path.endsWith(operand), context);
                }
                assertEquals(expected.resolve(referenceOperand).toString(), path.resolve(operand).toString(), context);
                assertEquals(expected.resolveSibling(referenceOperand).toString(),
                        path.resolveSibling(operand).toString(), context);
                assertEquals(expected.resolve(other).toString(), path.resolve(other).toString(), context);
                assertEquals(expected.resolveSibling(other).toString(), path.resolveSibling(other).toString(), context);
            }
        }
    }

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
