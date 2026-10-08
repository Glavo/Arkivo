// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks copy identity, link handling, and non-recursive replacement across writable archive providers.
@NotNullByDefault
final class ArchiveCopyContractTest {
    /// Distinguishes copying a link itself, following a source link, and replacing a destination link.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void respectsSymbolicLinkIdentity(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("links." + format);
        createSource(format, archive);
        try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
            Path file = fs.getPath("/file");
            Path link = fs.getPath("/link");
            Path alias = fs.getPath("./link");
            Files.copy(link, alias, LinkOption.NOFOLLOW_LINKS);
            Files.copy(link, alias, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            assertEquals("file", Files.readSymbolicLink(link).toString());
            Files.copy(link, file);
            Files.copy(link, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
            assertTrue(Files.isSymbolicLink(link));

            // A target link is an entry to replace, not an alias to follow for identity checks.
            assertThrows(FileAlreadyExistsException.class, () -> Files.copy(file, link));
            Files.copy(file, link, StandardCopyOption.REPLACE_EXISTING);
            assertFalse(Files.isSymbolicLink(link));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(link));
            Files.copy(fs.getPath("/dangling"), fs.getPath("/copied-link"), LinkOption.NOFOLLOW_LINKS);
            assertEquals("missing", Files.readSymbolicLink(fs.getPath("/copied-link")).toString());
            Files.copy(file, fs.getPath("/dangling"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            assertFalse(Files.isSymbolicLink(fs.getPath("/dangling")));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            for (String name : List.of("file", "link", "dangling")) {
                Path file = fs.getPath("/" + name);
                assertFalse(Files.isSymbolicLink(file));
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
                // A no-op copy must not attempt to open output even in a read-only session.
                Files.copy(file, fs.getPath("./" + name), StandardCopyOption.REPLACE_EXISTING);
            }
            assertEquals("missing", Files.readSymbolicLink(fs.getPath("/copied-link")).toString());
        }
    }

    /// Keeps both trees intact on rejection and copies only the source directory entry into an empty target.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void replacesOnlyEmptyDirectories(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("directories." + format);
        createSource(format, archive);
        try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
            Path source = fs.getPath("/source");
            Path target = fs.getPath("/target");
            for (CopyOption[] options : List.of(new CopyOption[]{StandardCopyOption.REPLACE_EXISTING},
                    new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES})) {
                assertThrows(DirectoryNotEmptyException.class, () -> Files.copy(source, target, options));
                assertArrayEquals(new byte[]{5}, Files.readAllBytes(source.resolve("child")));
                assertArrayEquals(new byte[]{7}, Files.readAllBytes(target.resolve("child")));
            }
            Path empty = fs.getPath("/empty");
            Files.createDirectory(empty);
            Files.copy(source, empty, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            assertTrue(Files.isDirectory(empty));
            assertFalse(Files.exists(empty.resolve("child")));
            Files.copy(source, fs.getPath("/file"), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(Files.isDirectory(fs.getPath("/file")));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertArrayEquals(new byte[]{5}, Files.readAllBytes(fs.getPath("/source/child")));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(fs.getPath("/target/child")));
            for (String name : List.of("empty", "file")) {
                try (var children = Files.list(fs.getPath("/" + name))) {
                    assertEquals(0, children.count());
                }
            }
        }
    }

    /// Identical entry names in separate archive sessions are distinct copy endpoints.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void copiesBetweenDistinctSessions(String format, @TempDir Path directory) throws IOException {
        Path sourceArchive = directory.resolve("source." + format);
        Path targetArchive = directory.resolve("target." + format);
        createSource(format, sourceArchive);
        createSource(format, targetArchive);
        try (var source = ArkivoFormats.openFileSystem(format, sourceArchive);
             var target = ArkivoFormats.updateFileSystem(format, targetArchive)) {
            Path input = source.getPath("/file");
            Path output = target.getPath("/file");
            Files.write(output, new byte[]{11});
            assertFalse(Files.isSameFile(input, output));
            assertThrows(FileAlreadyExistsException.class, () -> Files.copy(input, output));
            assertThrows(NoSuchFileException.class, () -> Files.copy(source.getPath("/missing"), output,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES));
            assertArrayEquals(new byte[]{11}, Files.readAllBytes(output));
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output));
            Files.write(output, new byte[]{13, 17});
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(input));
        }
        try (var source = ArkivoFormats.openFileSystem(format, sourceArchive);
             var target = ArkivoFormats.openFileSystem(format, targetArchive)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(source.getPath("/file")));
            assertArrayEquals(new byte[]{13, 17}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Publishes regular files, populated directories, and both resolvable and dangling links.
    private static void createSource(String format, Path archive) throws IOException {
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.write(fs.getPath("/file"), new byte[]{1, 2, 3});
            Files.createDirectory(fs.getPath("/source"));
            Files.write(fs.getPath("/source/child"), new byte[]{5});
            Files.createDirectory(fs.getPath("/target"));
            Files.write(fs.getPath("/target/child"), new byte[]{7});
            Files.createSymbolicLink(fs.getPath("/link"), fs.getPath("file"));
            Files.createSymbolicLink(fs.getPath("/dangling"), fs.getPath("missing"));
        }
    }
}
