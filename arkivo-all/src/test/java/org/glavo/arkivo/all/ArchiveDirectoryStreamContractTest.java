// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks directory contents across updates and traversal under each archive lifecycle coordination mode.
@NotNullByDefault
final class ArchiveDirectoryStreamContractTest {
    /// Crosses writable formats with operations that leave or explicitly remove implicit directories.
    private static Stream<Arguments> directoryMutations() {
        return Stream.of("ar", "tar", "zip", "7z").flatMap(format -> Stream.of(
                "delete-file", "move-file", "move-subtree", "delete-directory", "move-empty-directory", "delete-all"
        ).map(operation -> Arguments.of(format, operation)));
    }

    /// Preserves unmodified parent directories across publication, including empty ancestors of moved subtrees.
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("directoryMutations")
    void retainsImplicitDirectoriesAfterRemovingChildren(String format, String operation, @TempDir Path directory)
            throws IOException {
        Path archive = directory.resolve("implicit." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.writeString(fs.getPath("/parent/nested/value"), "body");
        }
        @Unmodifiable Set<String> expected = switch (operation) {
            case "delete-file" -> Set.of("/", "/parent", "/parent/nested");
            case "move-file" -> Set.of("/", "/parent", "/parent/nested", "/moved");
            case "move-subtree" -> Set.of("/", "/parent", "/moved", "/moved/value");
            case "delete-directory" -> Set.of("/", "/parent");
            case "move-empty-directory" -> Set.of("/", "/parent", "/moved");
            case "delete-all" -> Set.of("/");
            default -> throw new AssertionError(operation);
        };
        try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
            Path file = fs.getPath("/parent/nested/value");
            Path parent = file.getParent();
            switch (operation) {
                case "delete-file" -> Files.delete(file);
                case "move-file" -> Files.move(file, fs.getPath("/moved"));
                case "move-subtree" -> Files.move(parent, fs.getPath("/moved"));
                case "delete-directory", "delete-all" -> {
                    Files.delete(file);
                    Files.delete(parent);
                    if (operation.equals("delete-all")) Files.delete(parent.getParent());
                }
                case "move-empty-directory" -> {
                    Files.delete(file);
                    Files.move(parent, fs.getPath("/moved"));
                }
                default -> throw new AssertionError(operation);
            }
            assertDirectoryTree(fs, expected, operation);
        }
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertDirectoryTree(fs, expected, operation);
        }
    }

    /// Verifies traversal, directory types, and the contents of any surviving moved file.
    private static void assertDirectoryTree(ArkivoFileSystem fs, Set<String> expected, String operation)
            throws IOException {
        try (var paths = Files.walk(fs.getPath("/"))) {
            assertEquals(expected, paths.map(Path::toString).collect(Collectors.toSet()));
        }
        for (String name : expected) {
            Path path = fs.getPath(name);
            if (name.endsWith("/value") || name.equals("/moved") && operation.equals("move-file")) {
                assertEquals("body", Files.readString(path));
            } else {
                assertTrue(Files.isDirectory(path), name);
            }
        }
    }

    /// Crosses writable formats, read/update sessions, and all resource coordination modes.
    private static Stream<Arguments> configurations() {
        return Stream.of("ar", "tar", "zip", "7z").flatMap(format -> Stream.of(false, true)
                .flatMap(update -> Arrays.stream(ArkivoFileSystemThreadSafety.values())
                        .map(safety -> Arguments.of(format, update, safety))));
    }

    /// Preserves empty and dot root spellings, the root cases in OpenJDK zipfs `DirectoryStreamTests`.
    @ParameterizedTest(name = "{0}, update={1}, safety={2}")
    @MethodSource("configurations")
    void preservesRootSpelling(String format, boolean update, ArkivoFileSystemThreadSafety safety,
                              @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, safety, directory)) {
            for (String spelling : List.of("", "/", ".", "./", "/./")) {
                Path root = fs.getPath(spelling);
                var expected = Set.of(root.resolve("first"), root.resolve("skip"), root.resolve("second"));
                var seen = new HashSet<Path>();
                var returned = new HashSet<Path>();
                try (var stream = Files.newDirectoryStream(root, path -> {
                    assertTrue(expected.contains(path), path.toString());
                    assertTrue(seen.add(path));
                    return true;
                })) {
                    stream.forEach(path -> assertTrue(returned.add(path)));
                }
                assertEquals(expected, seen);
                assertEquals(expected, returned);
            }
        }
    }

    /// Reads each accepted path once and rejects iterator removal without altering archive contents.
    @ParameterizedTest(name = "{0}, update={1}, safety={2}")
    @MethodSource("configurations")
    void filtersDuringTraversal(String format, boolean update, ArkivoFileSystemThreadSafety safety,
                                @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, safety, directory)) {
            var calls = new AtomicInteger();
            try (var stream = Files.newDirectoryStream(fs.getPath("/"), path -> {
                calls.incrementAndGet();
                return !path.getFileName().toString().equals("skip");
            })) {
                var iterator = stream.iterator();
                assertEquals(0, calls.get());
                assertThrows(IllegalStateException.class, stream::iterator);
                assertThrows(UnsupportedOperationException.class, iterator::remove);
                var names = new HashSet<String>();
                while (iterator.hasNext()) {
                    int before = calls.get();
                    assertTrue(iterator.hasNext());
                    Path path = iterator.next();
                    assertEquals(before, calls.get());
                    assertTrue(names.add(path.getFileName().toString()));
                    assertEquals(fs.getPath("/"), path.getParent());
                    assertThrows(UnsupportedOperationException.class, iterator::remove);
                    assertTrue(Files.exists(path));
                }
                assertEquals(Set.of("first", "second"), names);
                assertEquals(3, calls.get());
                assertThrows(NoSuchElementException.class, iterator::next);
            }
            assertEquals(3, countEntries(fs));
        }
    }

    /// Filter failures retain their checked cause and occur on the traversal operation that encounters them.
    @ParameterizedTest(name = "{0}, update={1}, safety={2}")
    @MethodSource("configurations")
    void reportsFilterFailuresDuringTraversal(String format, boolean update, ArkivoFileSystemThreadSafety safety,
                                             @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, safety, directory)) {
            for (boolean viaNext : new boolean[]{false, true}) {
                var calls = new AtomicInteger();
                var failure = new IOException("directory filter failed");
                try (var stream = Files.newDirectoryStream(fs.getPath("/"), path -> {
                    if (calls.incrementAndGet() == 2) throw failure;
                    return true;
                })) {
                    var iterator = stream.iterator();
                    assertEquals(0, calls.get());
                    assertTrue(iterator.hasNext());
                    iterator.next();
                    DirectoryIteratorException reported = viaNext
                            ? assertThrows(DirectoryIteratorException.class, iterator::next)
                            : assertThrows(DirectoryIteratorException.class, iterator::hasNext);
                    assertSame(failure, reported.getCause());
                    assertEquals(2, calls.get());
                }
            }
            assertEquals(3, countEntries(fs));
        }
    }

    /// Closing before traversal stops filtering, while closing after read-ahead preserves the buffered path.
    @ParameterizedTest(name = "{0}, update={1}, safety={2}")
    @MethodSource("configurations")
    void closesWithoutDiscardingLookahead(String format, boolean update, ArkivoFileSystemThreadSafety safety,
                                         @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, safety, directory)) {
            for (boolean prefetch : new boolean[]{false, true}) {
                var calls = new AtomicInteger();
                try (var stream = Files.newDirectoryStream(fs.getPath("/"), path -> {
                    calls.incrementAndGet();
                    return true;
                })) {
                    var iterator = stream.iterator();
                    if (prefetch) assertTrue(iterator.hasNext());
                    stream.close();
                    stream.close();
                    assertThrows(IllegalStateException.class, stream::iterator);
                    if (prefetch) assertTrue(Files.exists(iterator.next()));
                    assertFalse(iterator.hasNext());
                    assertThrows(NoSuchElementException.class, iterator::next);
                    assertEquals(prefetch ? 1 : 0, calls.get());
                }
            }
        }
    }

    /// Opens an independently scoped archive containing three direct children.
    private static ArkivoFileSystem open(String format, boolean update, ArkivoFileSystemThreadSafety safety,
                                         Path directory) throws IOException {
        Path archive = directory.resolve("directory." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            for (String name : List.of("first", "skip", "second")) {
                Files.write(fs.getPath("/" + name), new byte[]{1, 2, 3});
            }
        }
        return update
                ? ArkivoFormats.updateFileSystem(format, archive, ArchiveUpdateOptions.DEFAULT.withThreadSafety(safety))
                : ArkivoFormats.openFileSystem(format, archive, ArchiveReadOptions.DEFAULT.withThreadSafety(safety));
    }

    /// Counts unchanged members using a separate directory stream after traversal or failure.
    private static long countEntries(ArkivoFileSystem fs) throws IOException {
        try (var paths = Files.list(fs.getPath("/"))) {
            return paths.count();
        }
    }
}
