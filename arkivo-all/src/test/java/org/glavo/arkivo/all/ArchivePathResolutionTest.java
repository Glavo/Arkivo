// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies component traversal and symbolic-link policies in read-only and update archive sessions.
@NotNullByDefault
final class ArchivePathResolutionTest {
    /// Crosses archive providers with read-only and update sessions.
    private static Stream<Arguments> configurations() {
        return Stream.of("ar", "tar", "zip", "7z")
                .flatMap(format -> Stream.of(false, true).map(update -> Arguments.of(format, update)));
    }

    /// Expands links before processing parent components, including parent components in link targets.
    @ParameterizedTest
    @MethodSource("configurations")
    void resolvesLinksBeforeParentComponents(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("/alias/../value.txt", "alias/../value.txt", "/indirect", "/absolute")) {
                Path path = fs.getPath(name);
                assertEquals(fs.getPath("/real/value.txt"), path.toRealPath());
                assertEquals("REAL", Files.readString(path));
            }
            assertThrows(NoSuchFileException.class, () -> fs.getPath("/missing/../value.txt").toRealPath());
            assertThrows(NotDirectoryException.class, () -> fs.getPath("/value.txt/../value.txt").toRealPath());
            assertThrows(NotDirectoryException.class, () -> fs.getPath("/value.txt/.").toRealPath());
            assertEquals(fs.getPath("/value.txt"), fs.getPath("/../../value.txt").toRealPath());
        }
    }

    /// Checks the target of a link while preserving the ability to test the link entry itself.
    @ParameterizedTest
    @MethodSource("configurations")
    void followsLinksForAccessChecks(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            Path broken = fs.getPath("/broken");
            assertFalse(Files.exists(broken));
            assertFalse(Files.isReadable(broken));
            assertTrue(Files.exists(broken, LinkOption.NOFOLLOW_LINKS));
            assertThrows(NoSuchFileException.class, () -> fs.provider().checkAccess(broken));
            Path child = fs.getPath("/dirlink/value.txt");
            assertTrue(Files.exists(child));
            assertTrue(Files.isReadable(child));
            fs.provider().checkAccess(child, AccessMode.READ);
            assertThrows(FileSystemLoopException.class, () -> fs.provider().checkAccess(fs.getPath("/cycle")));
            assertThrows(FileSystemLoopException.class, () -> fs.getPath("/cycle/value.txt").toRealPath());
            assertThrows(FileSystemLoopException.class, () -> fs.getPath("/cycle/../value.txt").toRealPath());
        }
    }

    /// Leaves only the terminal link unfollowed when reading attributes or link targets.
    @ParameterizedTest
    @MethodSource("configurations")
    void resolvesParentsForNoFollowOperations(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            Path child = fs.getPath("/dirlink/value.txt");
            assertTrue(Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile());
            assertEquals(4L, Files.readAttributes(child, "basic:size", LinkOption.NOFOLLOW_LINKS).get("size"));
            @Nullable BasicFileAttributeView view = Files.getFileAttributeView(child, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            assertNotNull(view);
            assertEquals(4L, view.readAttributes().size());
            Path link = fs.getPath("/dirlink/filelink");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(fs.getPath("value.txt"), Files.readSymbolicLink(link));
            assertTrue(Files.readAttributes(link, BasicFileAttributes.class).isRegularFile());
            @Nullable BasicFileAttributeView missingView = Files.getFileAttributeView(
                    fs.getPath("/dirlink/absent"), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(missingView);
            assertThrows(NoSuchFileException.class, missingView::readAttributes);
        }
    }

    /// Preserves lexical link names in real paths without incorrectly cancelling a link followed by a parent.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesLinksInNoFollowRealPaths(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("/alias/../value.txt", "/dirlink/value.txt", "/broken")) {
                assertEquals(fs.getPath(name), fs.getPath(name).toRealPath(LinkOption.NOFOLLOW_LINKS));
            }
            assertEquals(fs.getPath("/value.txt"), fs.getPath("/real/../value.txt").toRealPath(LinkOption.NOFOLLOW_LINKS));
            assertThrows(NoSuchFileException.class,
                    () -> fs.getPath("/missing/../value.txt").toRealPath(LinkOption.NOFOLLOW_LINKS));
            assertThrows(NotDirectoryException.class,
                    () -> fs.getPath("/value.txt/..").toRealPath(LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// Delivers paths relative to the original directory to both filters and iterators.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesDirectoryPathSpelling(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("real", "./real", "/dirlink", "dirlink", "/alias/..")) {
                Path parent = fs.getPath(name);
                var seen = new HashSet<Path>();
                var returned = new HashSet<Path>();
                try (var children = Files.newDirectoryStream(parent, child -> {
                    assertEquals(parent.resolve(child.getFileName()), child);
                    assertEquals(child.getFileName(), parent.relativize(child));
                    seen.add(child);
                    return true;
                })) {
                    children.forEach(returned::add);
                }
                assertEquals(Set.of(parent.resolve("sub"), parent.resolve("value.txt"), parent.resolve("filelink")), returned);
                assertEquals(returned, seen);
            }
        }
    }

    /// Creates a small archive whose lexical and link-resolved parent directories differ.
    private static ArkivoFileSystem open(String format, boolean update, Path directory) throws IOException {
        Path archive = directory.resolve("paths." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.createDirectories(fs.getPath("/real/sub"));
            Files.writeString(fs.getPath("/value.txt"), "ROOT");
            Files.writeString(fs.getPath("/real/value.txt"), "REAL");
            Files.createSymbolicLink(fs.getPath("/alias"), fs.getPath("real/sub"));
            Files.createSymbolicLink(fs.getPath("/dirlink"), fs.getPath("real"));
            Files.createSymbolicLink(fs.getPath("/real/filelink"), fs.getPath("value.txt"));
            Files.createSymbolicLink(fs.getPath("/broken"), fs.getPath("absent"));
            Files.createSymbolicLink(fs.getPath("/cycle"), fs.getPath("cycle"));
            Files.createSymbolicLink(fs.getPath("/indirect"), fs.getPath("alias/../value.txt"));
            Files.createSymbolicLink(fs.getPath("/absolute"), fs.getPath("/alias/../value.txt"));
        }
        return update ? ArkivoFormats.updateFileSystem(format, archive) : ArkivoFormats.openFileSystem(format, archive);
    }
}
