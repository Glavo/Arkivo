// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks repeated ZIP namespace updates against an independent content model and JDK ZIPFS.
@NotNullByDefault
final class ZipNamespaceInteropTest {
    /// Uses whole seconds within the DOS timestamp range to avoid provider-specific rounding.
    private static final FileTime MODIFIED = FileTime.from(Instant.parse("2004-05-06T07:08:10Z"));

    /// The directories retained throughout every update, including a non-ASCII path component.
    private static final @Unmodifiable List<String> DIRECTORIES = List.of("left", "right", "\u76ee\u5f55");

    /// Combines both original methods, both staging backends, and two reproducible operation sequences.
    private static Stream<Arguments> configurations() {
        return Stream.of(ZipEntry.STORED, ZipEntry.DEFLATED).flatMap(method ->
                Stream.of(false, true).flatMap(memory ->
                        Stream.of(37L, 103L).map(seed -> Arguments.of(method, memory, seed))));
    }

    /// Copying an entry onto a lexical alias is a no-op, including replacement and attribute-copy options.
    @ParameterizedTest(name = "method={0}, memory={1}, seed={2}")
    @MethodSource("configurations")
    void copiesOntoAliasesWithoutReplacingSource(int method, boolean memory, long seed, @TempDir Path directory)
            throws IOException {
        var expected = new TreeMap<String, EntryState>();
        expected.put("left/body", newContent(new Random(seed), 32769));
        Path archive = directory.resolve("aliases.zip");
        createArchive(archive, method, expected);
        Path storage = Files.createDirectory(directory.resolve("storage"));
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(memory
                ? ArkivoEditStorageFactory.memory() : ArkivoEditStorageFactory.temporaryFiles(storage)));
        try (var fs = ZipArkivoFileSystem.update(archive, options)) {
            Path source = fs.getPath("/left/body");
            for (String alias : List.of("left/body", "./left/body", "/left/../left/body", "/left/./body")) {
                Path target = fs.getPath(alias);
                assertTrue(Files.isSameFile(source, target));
                for (CopyOption[] copyOptions : List.of(new CopyOption[0],
                        new CopyOption[]{StandardCopyOption.REPLACE_EXISTING},
                        new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES},
                        new CopyOption[]{LinkOption.NOFOLLOW_LINKS})) {
                    Files.copy(source, target, copyOptions);
                    assertSnapshot(fs, expected);
                }
            }
        }
        assertPhysicalEntries(archive, expected);
        try (var files = Files.list(storage)) {
            assertEquals(0, files.count());
        }
    }

    /// Directory copies do not merge children or replace nonempty destinations.
    @ParameterizedTest(name = "method={0}, memory={1}, seed={2}")
    @MethodSource("configurations")
    void directoryCopiesRejectNonemptyReplacement(int method, boolean memory, long seed, @TempDir Path directory)
            throws IOException {
        var expected = new TreeMap<String, EntryState>();
        var random = new Random(seed);
        expected.put("left/body", newContent(random, 1024));
        expected.put("right/keep", newContent(random, 127));
        Path archive = directory.resolve("directories.zip");
        createArchive(archive, method, expected);
        Path storage = Files.createDirectory(directory.resolve("storage"));
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(memory
                ? ArkivoEditStorageFactory.memory() : ArkivoEditStorageFactory.temporaryFiles(storage)));
        try (var fs = ZipArkivoFileSystem.update(archive, options)) {
            Path source = fs.getPath("/left");
            Path occupied = fs.getPath("/right");
            assertThrows(DirectoryNotEmptyException.class,
                    () -> Files.copy(source, occupied, StandardCopyOption.REPLACE_EXISTING));
            assertThrows(DirectoryNotEmptyException.class, () -> Files.copy(source, occupied,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES));
            assertSnapshot(fs, expected);
            Path empty = fs.getPath("/" + DIRECTORIES.get(2));
            Files.setLastModifiedTime(source, MODIFIED);
            Files.copy(source, empty, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            assertEquals(MODIFIED, Files.getLastModifiedTime(empty));
            assertFalse(Files.exists(empty.resolve("body")));
            assertSnapshot(fs, expected);
        }
        assertPhysicalEntries(archive, expected);
    }

    /// Checks live contents after each operation and both physical record views after every commit.
    @ParameterizedTest(name = "method={0}, memory={1}, seed={2}")
    @MethodSource("configurations")
    void repeatedNamespaceUpdates(int method, boolean memory, long seed, @TempDir Path directory) throws IOException {
        var random = new Random(seed);
        var expected = new TreeMap<String, EntryState>();
        for (String parent : DIRECTORIES) {
            expected.put(parent + "/empty", newContent(random, 0));
            expected.put(parent + "/body", newContent(random, 32769));
        }
        Path archive = directory.resolve("arkivo.zip");
        Path referenceArchive = directory.resolve("jdk.zip");
        createArchive(archive, method, expected);
        Files.copy(archive, referenceArchive);
        Path storage = Files.createDirectory(directory.resolve("storage"));
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(memory
                ? ArkivoEditStorageFactory.memory() : ArkivoEditStorageFactory.temporaryFiles(storage)));

        for (int round = 0; round < 3; round++) {
            try (var actual = ZipArkivoFileSystem.update(archive, options);
                 var reference = FileSystems.newFileSystem(referenceArchive, Map.of())) {
                assertSnapshot(actual, expected);
                assertSnapshot(reference, expected);
                for (int step = 0; step < 24; step++) {
                    List<String> names = List.copyOf(expected.keySet());
                    String source = names.get(random.nextInt(names.size()));
                    String existing = names.get((names.indexOf(source) + 1) % names.size());
                    String unused = DIRECTORIES.get(step % DIRECTORIES.size()) + "/new-" + round + "-" + step;
                    EntryState content = Objects.requireNonNull(expected.get(source));
                    switch (step % 6) {
                        case 0 -> {
                            for (FileSystem fs : List.of(actual, reference)) {
                                Files.copy(fs.getPath(source), fs.getPath(unused), StandardCopyOption.COPY_ATTRIBUTES);
                            }
                            normalizeReferenceTime(reference, unused, content);
                            expected.put(unused, content);
                        }
                        case 1 -> {
                            for (FileSystem fs : List.of(actual, reference)) {
                                Files.move(fs.getPath(source), fs.getPath(unused));
                            }
                            normalizeReferenceTime(reference, unused, content);
                            expected.remove(source);
                            expected.put(unused, content);
                        }
                        case 2 -> {
                            for (FileSystem fs : List.of(actual, reference)) {
                                Files.copy(fs.getPath(source), fs.getPath(existing),
                                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            }
                            normalizeReferenceTime(reference, existing, content);
                            expected.put(existing, content);
                        }
                        case 3 -> {
                            // Overwrite a visible entry, then move the staged replacement over another entry.
                            EntryState replacement = newContent(random, 1 + random.nextInt(8192));
                            for (FileSystem fs : List.of(actual, reference)) {
                                writeEntry(fs, source, replacement);
                                Files.move(fs.getPath(source), fs.getPath(existing), StandardCopyOption.REPLACE_EXISTING);
                            }
                            normalizeReferenceTime(reference, existing, replacement);
                            expected.remove(source);
                            expected.put(existing, replacement);
                        }
                        case 4 -> {
                            // Reusing a deleted path must not resurrect the original local record at commit.
                            EntryState replacement = newContent(random, random.nextInt(1024));
                            for (FileSystem fs : List.of(actual, reference)) {
                                Files.delete(fs.getPath(source));
                                assertThrows(NoSuchFileException.class, () -> Files.readAllBytes(fs.getPath(source)));
                                writeEntry(fs, source, replacement);
                            }
                            expected.put(source, replacement);
                        }
                        case 5 -> {
                            for (FileSystem fs : List.of(actual, reference)) {
                                assertThrows(FileAlreadyExistsException.class, () ->
                                        Files.copy(fs.getPath(source), fs.getPath(existing)));
                                assertThrows(FileAlreadyExistsException.class, () ->
                                        Files.move(fs.getPath(source), fs.getPath(existing)));
                                Files.copy(fs.getPath(source), fs.getPath(source));
                                Files.move(fs.getPath(source), fs.getPath(source));
                            }
                        }
                        default -> throw new AssertionError();
                    }
                    assertSnapshot(actual, expected);
                    assertSnapshot(reference, expected);
                }
                // Leave actual deletions in the archive, not only delete-and-recreate operations.
                String deleted = expected.firstKey();
                for (FileSystem fs : List.of(actual, reference)) {
                    Files.delete(fs.getPath(deleted));
                }
                expected.remove(deleted);
                String survivor = expected.firstKey();
                for (FileSystem fs : List.of(actual, reference)) {
                    assertThrows(NoSuchFileException.class, () ->
                            Files.move(fs.getPath(deleted), fs.getPath(survivor), StandardCopyOption.REPLACE_EXISTING));
                    assertSnapshot(fs, expected);
                }
            }
            for (Path persisted : List.of(archive, referenceArchive)) {
                // ZIPFS uses UTF-8 by default even for records without the language-encoding flag.
                // Keep Arkivo's own output on the default decoder to verify its serialized encoding metadata.
                var readOptions = persisted.equals(referenceArchive)
                        ? ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(
                                ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8))
                        : ZipArchiveOptions.READ_DEFAULTS;
                try (var reopened = ZipArkivoFileSystem.open(persisted, readOptions)) {
                    assertAll(persisted.toString(), () -> assertSnapshot(reopened, expected));
                }
                assertPhysicalEntries(persisted, expected);
            }
            try (var files = Files.list(storage)) {
                assertEquals(0, files.count(), "Staging files retained after closing round " + round);
            }
        }
    }

    /// Checks the complete visible namespace, each file body, and modification times.
    private static void assertSnapshot(FileSystem fs, Map<String, EntryState> expected) throws IOException {
        var paths = new HashSet<>(expected.keySet());
        paths.addAll(DIRECTORIES);
        Path root = fs.getPath("/");
        try (var walk = Files.walk(root)) {
            assertEquals(paths, walk.filter(path -> !path.equals(root))
                    .map(path -> root.relativize(path).toString()).collect(Collectors.toSet()));
        }
        for (String name : DIRECTORIES) {
            assertTrue(Files.isDirectory(fs.getPath(name)), name);
        }
        for (var entry : expected.entrySet()) {
            Path path = fs.getPath(entry.getKey());
            assertArrayEquals(entry.getValue().content(), Files.readAllBytes(path), entry.getKey());
            assertEquals(entry.getValue().content().length, Files.size(path), entry.getKey());
            assertEquals(entry.getValue().modified(), Files.getLastModifiedTime(path), entry.getKey());
        }
    }

    /// Verifies central-directory and sequential local-record views independently with JDK readers.
    private static void assertPhysicalEntries(Path archive, Map<String, EntryState> expected) throws IOException {
        var names = new HashSet<>(expected.keySet());
        DIRECTORIES.forEach(name -> names.add(name + "/"));
        try (var zip = new ZipFile(archive.toFile())) {
            assertEquals(names.size(), zip.size(), "Duplicate or obsolete central-directory entry");
            assertEquals(names, zip.stream().map(ZipEntry::getName).collect(Collectors.toSet()));
            for (var entry : expected.entrySet()) {
                var header = Objects.requireNonNull(zip.getEntry(entry.getKey()));
                try (var input = zip.getInputStream(header)) {
                    assertArrayEquals(entry.getValue().content(), input.readAllBytes(), entry.getKey());
                }
                var crc = new CRC32();
                crc.update(entry.getValue().content());
                assertEquals(crc.getValue(), header.getCrc(), entry.getKey());
                assertEquals(entry.getValue().content().length, header.getSize(), entry.getKey());
                assertEquals(entry.getValue().modified(), header.getLastModifiedTime(), entry.getKey());
            }
        }
        var seen = new HashSet<String>();
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            for (@Nullable ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertTrue(seen.add(entry.getName()), "Duplicate local record: " + entry.getName());
                assertTrue(names.contains(entry.getName()), "Obsolete local record: " + entry.getName());
                byte[] body = entry.isDirectory() ? new byte[0]
                        : Objects.requireNonNull(expected.get(entry.getName())).content();
                assertArrayEquals(body, input.readAllBytes(), entry.getName());
                input.closeEntry();
            }
        }
        assertEquals(names, seen);
    }

    /// Resets only the reference timestamp after ZIPFS moves or copies that recompress an entry.
    ///
    /// ZIPFS can assign the current time in those paths. Arkivo is checked without this adjustment.
    private static void normalizeReferenceTime(FileSystem reference, String name, EntryState state) throws IOException {
        Files.setLastModifiedTime(reference.getPath(name), state.modified());
    }

    /// Writes a replacement and sets the timestamp independently of each provider's write-time policy.
    private static void writeEntry(FileSystem fs, String name, EntryState state) throws IOException {
        Path path = fs.getPath(name);
        Files.write(path, state.content());
        Files.setLastModifiedTime(path, state.modified());
    }

    /// Generates independent deterministic bytes, including lengths across the Deflate window boundary.
    private static EntryState newContent(Random random, int length) {
        byte[] content = new byte[length];
        random.nextBytes(content);
        return new EntryState(content, FileTime.fromMillis(MODIFIED.toMillis() + 2000L * random.nextInt(10000)));
    }

    /// Creates the initial archive independently of Arkivo with explicit directory records.
    private static void createArchive(Path archive, int method, Map<String, EntryState> expected) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            var entries = new TreeMap<>(expected);
            DIRECTORIES.forEach(name -> entries.put(name + "/", new EntryState(new byte[0], MODIFIED)));
            for (var entry : entries.entrySet()) {
                var header = new ZipEntry(entry.getKey());
                var crc = new CRC32();
                crc.update(entry.getValue().content());
                header.setMethod(method);
                header.setSize(entry.getValue().content().length);
                header.setCrc(crc.getValue());
                header.setLastModifiedTime(entry.getValue().modified());
                output.putNextEntry(header);
                output.write(entry.getValue().content());
                output.closeEntry();
            }
        }
    }

    /// Records expected entry data without deriving it from either archive implementation.
    ///
    /// @param content the bytes, never modified after the state is created
    /// @param modified the expected modification time
    @NotNullByDefault
    private record EntryState(byte @Unmodifiable [] content, FileTime modified) {
    }
}
