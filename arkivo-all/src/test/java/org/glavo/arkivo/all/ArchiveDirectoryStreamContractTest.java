// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks directory-stream traversal under each archive lifecycle coordination mode.
@NotNullByDefault
final class ArchiveDirectoryStreamContractTest {
    /// Crosses writable formats, read/update sessions, and all resource coordination modes.
    private static Stream<Arguments> configurations() {
        return Stream.of("ar", "tar", "zip", "7z").flatMap(format -> Stream.of(false, true)
                .flatMap(update -> Arrays.stream(ArkivoFileSystemThreadSafety.values())
                        .map(safety -> Arguments.of(format, update, safety))));
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
