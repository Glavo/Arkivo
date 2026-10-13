// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies component traversal and symbolic-link policies in read-only and update archive sessions.
@NotNullByDefault
final class ArchivePathResolutionTest {
    /// Directory filters may mutate archive entries without holding a non-upgradable read lock.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void directoryFiltersCanMutateEntries(String format, @TempDir Path directory) throws Exception {
        var task = new FutureTask<Boolean>(() -> {
            try (var fs = open(format, true, directory);
                 var stream = Files.newDirectoryStream(fs.getPath("/real"), entry -> {
                     if (entry.getFileName().toString().equals("value.txt")) {
                         Files.delete(entry);
                         Files.writeString(entry.resolveSibling("created.txt"), "CREATED");
                     }
                     return true;
                 })) {
                for (Path ignored : stream) {
                    // Exhaust the snapshot, applying its filter lazily.
                }
                assertFalse(Files.exists(fs.getPath("/real/value.txt")));
                assertEquals("CREATED", Files.readString(fs.getPath("/real/created.txt")));
            }
            try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
                assertFalse(Files.exists(fs.getPath("/real/value.txt")));
                assertEquals("CREATED", Files.readString(fs.getPath("/real/created.txt")));
            }
            return true;
        });
        Thread worker = new Thread(task, "archive-directory-filter-test");
        worker.setDaemon(true);
        worker.start();
        task.get(10, TimeUnit.SECONDS);
    }

    /// Crosses archive providers with read-only and update sessions.
    private static Stream<Arguments> configurations() {
        return Stream.of("ar", "tar", "zip", "7z")
                .flatMap(format -> Stream.of(false, true).map(update -> Arguments.of(format, update)));
    }

    /// Crosses writable formats with operations that must resolve parents before selecting an entry.
    private static Stream<Arguments> mutations() {
        return Stream.of("ar", "tar", "zip", "7z").flatMap(format ->
                Stream.of("stream", "channel", "delete", "move", "directory", "link")
                        .map(operation -> Arguments.of(format, operation)));
    }

    /// Changes only the component-resolved target and retains that result after committing the archive.
    @ParameterizedTest
    @MethodSource("mutations")
    void resolvesMutationParents(String format, String operation, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path target = fs.getPath("/alias/../value.txt");
            Path created = fs.getPath("/alias/../created");
            switch (operation) {
                case "stream" -> Files.writeString(target, "CHANGED");
                case "channel" -> {
                    try (var channel = Files.newByteChannel(target,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        channel.write(ByteBuffer.wrap("CHANGED".getBytes(StandardCharsets.UTF_8)));
                    }
                }
                case "delete" -> Files.delete(target);
                case "move" -> Files.move(target, created);
                case "directory" -> Files.createDirectory(created);
                case "link" -> Files.createSymbolicLink(created, fs.getPath("value.txt"));
                default -> throw new AssertionError(operation);
            }
            assertMutationResult(fs, operation);
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertMutationResult(fs, operation);
        }
    }

    /// Checks both the intended mutation and the unaffected lexical neighbor.
    private static void assertMutationResult(ArkivoFileSystem fs, String operation) throws IOException {
        assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
        assertFalse(Files.exists(fs.getPath("/created"), LinkOption.NOFOLLOW_LINKS));
        Path value = fs.getPath("/real/value.txt");
        Path created = fs.getPath("/real/created");
        switch (operation) {
            case "stream", "channel" -> assertEquals("CHANGED", Files.readString(value));
            case "delete" -> assertFalse(Files.exists(value));
            case "move" -> {
                assertFalse(Files.exists(value));
                assertEquals("REAL", Files.readString(created));
            }
            case "directory" -> assertTrue(Files.isDirectory(created));
            case "link" -> {
                assertTrue(Files.isSymbolicLink(created));
                assertEquals("REAL", Files.readString(created));
            }
            default -> throw new AssertionError(operation);
        }
    }

    /// Follows terminal links for file bodies but preserves them for exclusive creation, moves, and deletion.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void appliesMutationLinkPolicies(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path link = fs.getPath("/real/filelink");
            Files.writeString(link, "TARGET");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            Path broken = fs.getPath("/broken");
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.newOutputStream(broken, StandardOpenOption.CREATE_NEW).close());
            Files.writeString(broken, "CREATED");
            assertTrue(Files.isSymbolicLink(broken));
            assertEquals("CREATED", Files.readString(fs.getPath("/absent")));
            Files.move(link, fs.getPath("/moved-link"));
            assertTrue(Files.isSymbolicLink(fs.getPath("/moved-link")));
            Files.delete(fs.getPath("/moved-link"));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            assertThrows(NoSuchFileException.class,
                    () -> Files.writeString(fs.getPath("/missing/../value.txt"), "BAD"));
            assertThrows(NotDirectoryException.class,
                    () -> Files.delete(fs.getPath("/value.txt/../value.txt")));
            assertThrows(FileSystemLoopException.class,
                    () -> Files.writeString(fs.getPath("/cycle/../value.txt"), "BAD"));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            assertTrue(Files.isSymbolicLink(fs.getPath("/broken")));
            assertEquals("CREATED", Files.readString(fs.getPath("/absent")));
            assertFalse(Files.exists(fs.getPath("/real/filelink"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fs.getPath("/moved-link"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// Retains implicit-parent creation after resolving existing links without cancelling missing parents.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void createsImplicitParentsBelowResolvedLinks(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Files.writeString(fs.getPath("/alias/../new-parent/child"), "CREATED");
            Files.createDirectory(fs.getPath("/alias/../new-directory/child"));
            Files.createSymbolicLink(fs.getPath("/alias/../new-links/link"), fs.getPath("../value.txt"));
            assertThrows(NoSuchFileException.class,
                    () -> Files.writeString(fs.getPath("/missing/nested/../../value.txt"), "BAD"));
            assertFalse(Files.exists(fs.getPath("/missing")));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertEquals("CREATED", Files.readString(fs.getPath("/real/new-parent/child")));
            assertTrue(Files.isDirectory(fs.getPath("/real/new-directory/child")));
            assertEquals("REAL", Files.readString(fs.getPath("/real/new-links/link")));
            assertFalse(Files.exists(fs.getPath("/new-parent")));
            assertFalse(Files.exists(fs.getPath("/new-directory")));
            assertFalse(Files.exists(fs.getPath("/new-links")));
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
        }
    }

    /// Resolves both hard-link paths while retaining TAR's restriction to regular-file targets.
    @Test
    void resolvesTarHardLinkParents(@TempDir Path directory) throws IOException {
        try (var fs = open("tar", true, directory)) {
            Files.createLink(fs.getPath("/alias/../hard"), fs.getPath("/alias/../value.txt"));
            assertEquals("REAL", Files.readString(fs.getPath("/real/hard")));
            assertFalse(Files.exists(fs.getPath("/hard")));
            assertThrows(FileSystemException.class,
                    () -> Files.createLink(fs.getPath("/rejected"), fs.getPath("/real/filelink")));
        }
        try (var fs = ArkivoFormats.openFileSystem("tar", directory.resolve("paths.tar"))) {
            assertEquals("REAL", Files.readString(fs.getPath("/real/hard")));
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
            assertFalse(Files.exists(fs.getPath("/rejected")));
        }
    }

    /// Keeps dot components and symbolic-link lookup semantics when paths round-trip through provider URIs.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesUriPathComponents(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory);
             var registered = fs.provider().newFileSystem(fs.getPath("/").toUri(), Map.of())) {
            for (String name : List.of("/alias/../value.txt", "alias/.././value.txt", "/dirlink/./value.txt")) {
                Path path = fs.getPath(name);
                Path restored = fs.provider().getPath(path.toUri());
                assertEquals(registered, restored.getFileSystem());
                assertEquals(path.toAbsolutePath().toString(), restored.toString());
                assertEquals("REAL", Files.readString(restored));
            }
            Path missing = fs.getPath("/missing/../value.txt");
            Path restored = fs.provider().getPath(missing.toUri());
            assertEquals(missing.toString(), restored.toString());
            assertThrows(NoSuchFileException.class, () -> Files.readString(restored));
        }
    }

    /// Escapes delimiter-like source names without confusing literal percent escapes with exclamation marks.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesUriSourceDelimiters(String format, boolean update, @TempDir Path directory) throws IOException {
        for (String name : List.of("bang!", "literal%21", "space !", "bang!/nested!", "bang!inside")) {
            Path parent = Files.createDirectories(directory.resolve(name));
            try (var fs = open(format, update, parent);
                 var registered = fs.provider().newFileSystem(URI.create(fs.getPath("/").toUri()
                         .toASCIIString().replace("%21inside", "!inside")), Map.of())) {
                Path entry = registered.getPath("/real/value.txt");
                assertEquals(entry, fs.provider().getPath(entry.toUri()));
                assertEquals("REAL", Files.readString(fs.provider().getPath(entry.toUri())));
                assertEquals(registered, fs.provider().getFileSystem(entry.toUri()));
            }
        }
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
