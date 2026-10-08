// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Checks creation attributes through the NIO APIs of every writable archive file system.
@NotNullByDefault
final class ArchiveInitialAttributesTest {
    /// A modification time representable without rounding by all tested formats.
    private static final FileTime MODIFIED = FileTime.from(Instant.parse("2001-02-03T04:05:06Z"));

    /// Copies files, directories, and symbolic links between sessions of the same provider.
    @ParameterizedTest
    @CsvSource({"ar,false", "ar,true", "tar,false", "tar,true", "zip,false", "zip,true", "7z,false", "7z,true"})
    void copiesAttributesBetweenSessions(String format, boolean update, @TempDir Path directory) throws IOException {
        Path sourceArchive = directory.resolve("source." + format);
        Path targetArchive = directory.resolve("target." + format);
        createSource(format, sourceArchive);
        try (var source = ArkivoFormats.openFileSystem(format, sourceArchive);
             var target = openTarget(format, targetArchive, update)) {
            for (String name : new String[]{"directory", "file", "link"}) {
                Path sourcePath = source.getPath("/" + name);
                Path targetPath = target.getPath("/" + name);
                Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                assertThrows(FileAlreadyExistsException.class, () ->
                        Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS));
            }
            Files.copy(source.getPath("/link"), target.getPath("/followed"), StandardCopyOption.COPY_ATTRIBUTES);
        }
        try (var target = ArkivoFormats.openFileSystem(format, targetArchive)) {
            for (String name : new String[]{"directory", "file", "link", "followed"}) {
                assertEquals(MODIFIED, Files.getLastModifiedTime(target.getPath("/" + name), LinkOption.NOFOLLOW_LINKS), name);
            }
            assertTrue(Files.isDirectory(target.getPath("/directory")));
            assertEquals("file", Files.readSymbolicLink(target.getPath("/link")).toString());
            assertFalse(Files.isSymbolicLink(target.getPath("/followed")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/file")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/followed")));
        }
    }

    /// Captures permissions and the last timestamp attribute before committing an empty entry.
    @ParameterizedTest
    @CsvSource({"ar,false", "ar,true", "tar,false", "tar,true", "zip,false", "zip,true", "7z,false", "7z,true"})
    void snapshotsInitialAttributes(String format, boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("attributes." + format);
        var expectedPermissions = PosixFilePermissions.fromString("rwxr-----");
        var permissions = EnumSet.copyOf(expectedPermissions);
        FileAttribute<?>[] attributes = {
                new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0)),
                new InitialAttribute("posix:permissions", permissions),
                new InitialAttribute("basic:lastModifiedTime", MODIFIED)
        };
        try (var target = openTarget(format, archive, update)) {
            Files.createDirectory(target.getPath("/directory"), attributes);
            Files.createSymbolicLink(target.getPath("/link"), Path.of("empty"), attributes);
            try (var ignored = Files.newByteChannel(target.getPath("/empty"),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                permissions.clear();
                attributes[2] = new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0));
            }
        }
        try (var target = ArkivoFormats.openFileSystem(format, archive)) {
            for (String name : new String[]{"directory", "empty", "link"}) {
                Path path = target.getPath("/" + name);
                assertEquals(MODIFIED, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS), name);
                assertEquals(expectedPermissions, Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS), name);
            }
            assertEquals(0L, Files.size(target.getPath("/empty")));
        }
    }

    /// Rejects unsupported names and wrong value types without reserving a path or poisoning the writer.
    @ParameterizedTest
    @CsvSource({"ar,false", "ar,true", "tar,false", "tar,true", "zip,false", "zip,true", "7z,false", "7z,true"})
    void rejectsInvalidAttributesWithoutMutation(String format, boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("invalid." + format);
        try (var target = openTarget(format, archive, update)) {
            FileAttribute<?>[] invalid = {
                    new InitialAttribute("basic:lastModifiedTime", "not a time"),
                    new InitialAttribute("basic:lastModifiedTime", null),
                    new InitialAttribute("unsupported:attribute", MODIFIED),
                    new InitialAttribute("posix:permissions", "not permissions")
            };
            for (int index = 0; index < invalid.length; index++) {
                FileAttribute<?> attribute = invalid[index];
                Path file = target.getPath("/invalid-" + index);
                Class<? extends RuntimeException> failure = index == 2
                        ? UnsupportedOperationException.class : IllegalArgumentException.class;
                assertThrows(failure, () -> {
                    try (var ignored = Files.newByteChannel(file,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attribute)) {
                        fail("Invalid initial attribute was accepted");
                    }
                });
                assertThrows(failure, () -> Files.createDirectory(file, attribute));
                assertThrows(failure, () -> Files.createSymbolicLink(file, Path.of("valid"), attribute));
                assertThrows(NoSuchFileException.class, () -> target.provider().checkAccess(file));
            }
            Files.write(target.getPath("/valid"), new byte[]{7});
        }
        try (var target = ArkivoFormats.openFileSystem(format, archive);
             var entries = Files.list(target.getPath("/"))) {
            assertEquals(Set.of("valid"), entries.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(target.getPath("/valid")));
        }
    }

    /// Rejects malformed AR attributes before creating implicit parents for a nested member.
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void rejectsArAttributesBeforeCreatingParents(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("parents.a");
        try (var target = openTarget("ar", archive, update)) {
            Path file = target.getPath("/missing/child");
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = Files.newByteChannel(file,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        new InitialAttribute("posix:permissions", "not permissions"))) {
                    fail("Invalid permissions were accepted");
                }
            });
            assertThrows(NoSuchFileException.class, () -> target.provider().checkAccess(target.getPath("/missing")));
            Files.write(target.getPath("/valid"), new byte[]{9});
        }
        try (var target = ArkivoFormats.openFileSystem("ar", archive)) {
            assertFalse(Files.exists(target.getPath("/missing")));
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target.getPath("/valid")));
        }
    }

    /// Leaves existing metadata intact when creation attributes accompany a truncating update.
    @ParameterizedTest
    @CsvSource({"ar", "tar", "zip", "7z"})
    void preservesExistingModificationTime(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("existing." + format);
        createSource(format, archive);
        try (var target = ArkivoFormats.updateFileSystem(format, archive)) {
            Path file = target.getPath("/file");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var channel = Files.newByteChannel(file,
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                        new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0)),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")))) {
                    channel.write(ByteBuffer.wrap(new byte[]{8}));
                }
                assertEquals(MODIFIED, Files.getLastModifiedTime(file));
                assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(file));
            }
        }
        try (var target = ArkivoFormats.openFileSystem(format, archive)) {
            assertEquals(MODIFIED, Files.getLastModifiedTime(target.getPath("/file")));
            assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(target.getPath("/file")));
            assertArrayEquals(new byte[]{8}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Checks serialized time resolution with independent AR and 7z readers rather than only round trips.
    @ParameterizedTest
    @CsvSource({"ar,false", "ar,true", "7z,false", "7z,true"})
    void serializesFractionalModificationTime(String format, boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("fractional." + format);
        FileTime requested = FileTime.from(Instant.parse("2001-02-03T04:05:06.123456789Z"));
        FileTime expected = FileTime.from(Instant.parse(format.equals("ar")
                ? "2001-02-03T04:05:06Z" : "2001-02-03T04:05:06.123456700Z"));
        try (var target = openTarget(format, archive, update)) {
            try (var ignored = Files.newByteChannel(target.getPath("/empty"),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    new InitialAttribute("basic:lastModifiedTime", requested))) {
                // An empty entry still has to serialize its timestamp.
            }
        }
        try (var target = ArkivoFormats.openFileSystem(format, archive)) {
            assertEquals(expected, Files.getLastModifiedTime(target.getPath("/empty")));
        }
        if (format.equals("ar")) {
            try (var reference = new ArArchiveInputStream(Files.newInputStream(archive))) {
                var entry = Objects.requireNonNull(reference.getNextEntry());
                assertEquals("empty", entry.getName());
                assertEquals(expected.toInstant().getEpochSecond(), entry.getLastModified());
                assertEquals(-1, reference.read());
                assertNull(reference.getNextEntry());
            }
        } else {
            try (var reference = SevenZFile.builder().setPath(archive).get()) {
                var entry = Objects.requireNonNull(reference.getNextEntry());
                assertEquals("empty", entry.getName());
                assertEquals(expected, entry.getLastModifiedTime());
                assertEquals(-1, reference.read());
                assertNull(reference.getNextEntry());
            }
        }
    }

    /// Creates a small independent source session using pending streaming-writer metadata.
    private static void createSource(String format, Path archive) throws IOException {
        try (var writer = ArkivoFormats.openStreamingWriter(format, Files.newOutputStream(archive))) {
            try (var entry = writer.beginDirectory("directory")) {
                setModificationTime(entry);
            }
            try (var entry = writer.beginFile("file")) {
                setModificationTime(entry);
                try (var output = entry.openOutputStream()) {
                    output.write(new byte[]{1, 2, 3});
                }
            }
            try (var entry = writer.beginSymbolicLink("link", "file")) {
                setModificationTime(entry);
            }
        }
    }

    /// Sets metadata before a streaming writer publishes the entry.
    private static void setModificationTime(ArkivoStreamingWriter.Entry entry) throws IOException {
        Objects.requireNonNull(entry.attributeView(BasicFileAttributeView.class)).setTimes(MODIFIED, null, null);
    }

    /// Opens a new archive directly or first creates an empty archive for an update session.
    private static ArkivoFileSystem openTarget(String format, Path archive, boolean update) throws IOException {
        if (!update) {
            return ArkivoFormats.createFileSystem(format, archive);
        }
        try (var ignored = ArkivoFormats.createFileSystem(format, archive)) {
            // Publish an empty archive before opening the update session.
        }
        return ArkivoFormats.updateFileSystem(format, archive);
    }

    /// Supplies a valid or deliberately malformed initial attribute.
    ///
    /// @param name the attribute name
    /// @param value the supplied value
    @NotNullByDefault
    private record InitialAttribute(String name, @Nullable Object value) implements FileAttribute<@Nullable Object> {
    }
}
