// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Tests initial metadata and attribute-preserving copies into TAR file systems.
@NotNullByDefault
final class TarInitialAttributesTest {
    /// Exercises source-provider copies without relying on host symbolic-link privileges or time precision.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiesModificationTimes(boolean update, @TempDir Path directory) throws IOException {
        Path sourceArchive = directory.resolve("source.tar");
        Path targetArchive = directory.resolve("target.tar");
        FileTime time = FileTime.from(Instant.parse("2001-02-03T04:05:06.123456789Z"));
        try (var writer = TarArkivoStreamingWriter.create(sourceArchive)) {
            try (var entry = writer.beginDirectory("directory")) {
                setModificationTime(entry, time);
            }
            try (var entry = writer.beginFile("file")) {
                setModificationTime(entry, time);
                try (var output = entry.openOutputStream()) {
                    output.write(new byte[]{1, 2, 3});
                }
            }
            try (var entry = writer.beginSymbolicLink("link", "file")) {
                setModificationTime(entry, time);
            }
        }
        try (var source = TarArkivoFileSystem.open(sourceArchive);
             var target = openTarget(targetArchive, update)) {
            for (String name : new String[]{"directory", "file", "link"}) {
                Files.copy(source.getPath("/" + name), target.getPath("/" + name),
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                assertThrows(FileAlreadyExistsException.class, () ->
                        Files.copy(source.getPath("/" + name), target.getPath("/" + name),
                                StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS));
            }
            Files.copy(source.getPath("/link"), target.getPath("/followed"), StandardCopyOption.COPY_ATTRIBUTES);
            Files.copy(source.getPath("/file"), target.getPath("/plain"));
            assertThrows(FileAlreadyExistsException.class, () ->
                    Files.copy(source.getPath("/file"), target.getPath("/plain")));
        }
        try (var target = TarArkivoFileSystem.open(targetArchive)) {
            for (String name : new String[]{"directory", "file", "link", "followed"}) {
                assertEquals(time, Files.getLastModifiedTime(target.getPath("/" + name), LinkOption.NOFOLLOW_LINKS), name);
            }
            assertTrue(Files.isDirectory(target.getPath("/directory")));
            assertEquals("file", Files.readSymbolicLink(target.getPath("/link")).toString());
            assertFalse(Files.isSymbolicLink(target.getPath("/followed")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/file")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/followed")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/plain")));
        }
    }

    /// Preserves fractional and pre-epoch times together with permissions; the last duplicate attribute wins.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsEntriesWithInitialAttributes(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("attributes.tar");
        FileTime time = FileTime.from(Instant.parse("1969-12-31T23:59:59.123456789Z"));
        var permissions = PosixFilePermissions.fromString("rwxr-----");
        FileAttribute<?>[] attributes = {
                new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0)),
                PosixFilePermissions.asFileAttribute(permissions),
                new InitialAttribute("basic:lastModifiedTime", time)
        };
        try (var target = openTarget(archive, update)) {
            Files.createDirectory(target.getPath("/directory"), attributes);
            Files.createSymbolicLink(target.getPath("/link"), Path.of("file"), attributes);
            try (var channel = Files.newByteChannel(target.getPath("/file"),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                channel.write(ByteBuffer.wrap(new byte[]{4, 5, 6}));
            }
        }
        try (var target = TarArkivoFileSystem.open(archive)) {
            for (String name : new String[]{"directory", "file", "link"}) {
                Path path = target.getPath("/" + name);
                assertEquals(time, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS), name);
                assertEquals(permissions, Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS), name);
            }
            assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Snapshots caller-owned creation data and commits metadata even when no body is written.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotsAttributesForEmptyEntries(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("empty.tar");
        FileTime time = FileTime.from(Instant.parse("2038-01-19T03:14:08Z"));
        var expectedPermissions = PosixFilePermissions.fromString("rwxr-----");
        var permissions = EnumSet.copyOf(expectedPermissions);
        FileAttribute<?>[] attributes = {
                new InitialAttribute("posix:permissions", permissions),
                new InitialAttribute("basic:lastModifiedTime", time)
        };
        try (var target = openTarget(archive, update)) {
            try (var ignored = Files.newByteChannel(target.getPath("/empty"),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                permissions.clear();
                attributes[1] = new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0));
            }
        }
        try (var target = TarArkivoFileSystem.open(archive)) {
            Path file = target.getPath("/empty");
            assertEquals(0L, Files.size(file));
            assertEquals(time, Files.getLastModifiedTime(file));
            assertEquals(expectedPermissions, Files.getPosixFilePermissions(file));
        }
    }

    /// Rejects invalid attributes before reserving a name, emitting an entry, or synthesizing parents.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsInvalidAttributesWithoutMutation(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("invalid.tar");
        try (var target = openTarget(archive, update)) {
            FileAttribute<?>[] invalid = {
                    new InitialAttribute("basic:lastModifiedTime", "not a time"),
                    new InitialAttribute("basic:lastModifiedTime", null),
                    new InitialAttribute("basic:creationTime", FileTime.fromMillis(0)),
                    new InitialAttribute("posix:permissions", "not permissions")
            };
            for (int index = 0; index < invalid.length; index++) {
                FileAttribute<?> attribute = invalid[index];
                String parent = "/invalid-" + index;
                Path file = target.getPath(parent + "/file");
                Class<? extends RuntimeException> failure = index == 2
                        ? UnsupportedOperationException.class : IllegalArgumentException.class;
                assertThrows(failure, () -> {
                    try (var ignored = Files.newByteChannel(file,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attribute)) {
                        fail("Invalid initial attribute was accepted");
                    }
                });
                assertThrows(failure, () -> Files.createDirectory(target.getPath(parent + "/directory"), attribute));
                assertThrows(failure, () -> Files.createSymbolicLink(target.getPath(parent + "/link"), Path.of("file"), attribute));
                assertThrows(NoSuchFileException.class, () -> target.provider().checkAccess(target.getPath(parent)));
            }
            Files.write(target.getPath("/valid"), new byte[]{7});
        }
        try (var target = TarArkivoFileSystem.open(archive);
             var entries = Files.list(target.getPath("/"))) {
            assertEquals(Set.of("valid"), entries.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(target.getPath("/valid")));
        }
    }

    /// Does not apply creation attributes when an update channel opens an existing file.
    @Test
    void preservesExistingAttributes(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("existing.tar");
        FileTime time = FileTime.from(Instant.parse("2001-02-03T04:05:06.123456789Z"));
        try (var writer = TarArkivoStreamingWriter.create(archive);
             var entry = writer.beginFile("file")) {
            setModificationTime(entry, time);
        }
        try (var target = TarArkivoFileSystem.update(archive)) {
            try (var channel = Files.newByteChannel(target.getPath("/file"),
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    new InitialAttribute("basic:lastModifiedTime", FileTime.fromMillis(0)),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")))) {
                channel.write(ByteBuffer.wrap(new byte[]{8}));
            }
            assertEquals(time, Files.getLastModifiedTime(target.getPath("/file")));
        }
        try (var target = TarArkivoFileSystem.open(archive)) {
            assertEquals(time, Files.getLastModifiedTime(target.getPath("/file")));
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(target.getPath("/file")));
            assertArrayEquals(new byte[]{8}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Opens either a forward-only archive or an update session on an empty archive.
    private static TarArkivoFileSystem openTarget(Path archive, boolean update) throws IOException {
        if (!update) {
            return TarArkivoFileSystem.create(archive);
        }
        try (var ignored = TarArkivoFileSystem.create(archive)) {
            // Commit an empty archive before opening the update session.
        }
        return TarArkivoFileSystem.update(archive);
    }

    /// Sets the pending entry's timestamp before its header is written.
    private static void setModificationTime(ArkivoStreamingWriter.Entry entry, FileTime time) throws IOException {
        Objects.requireNonNull(entry.attributeView(BasicFileAttributeView.class)).setTimes(time, null, null);
    }

    /// Supplies a typed or deliberately invalid creation attribute.
    ///
    /// @param name the attribute name
    /// @param value the value passed to the provider
    @NotNullByDefault
    private record InitialAttribute(String name, @Nullable Object value) implements FileAttribute<@Nullable Object> {
    }
}
