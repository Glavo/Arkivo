// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR metadata preparation and identical header rules for streamed and rewritten entries.
@NotNullByDefault
final class TarHeaderEncodingTest {
    /// Rejects NUL before creating pending entries or emitting fixed-width and PAX name fields.
    @ParameterizedTest
    @ValueSource(strings = {"\0entry", "entry\0suffix", "entry\0", "parent\0/child"})
    void rejectsNulStreamingEntryNames(String invalid) throws IOException {
        for (String name : List.of(invalid, "p".repeat(101) + invalid)) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (var writer = TarArkivoStreamingWriter.open(target, ArkivoEditStorage.memory())) {
                assertThrows(IllegalArgumentException.class, () -> writer.beginFile(name));
                assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory(name));
                assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink(name, "source"));
                assertThrows(IllegalArgumentException.class, () -> writer.beginHardLink(name, "source"));
                assertThrows(IllegalArgumentException.class, () -> writer.beginHardLink("link", name));
                assertEquals(0, target.size());
                try (var body = writer.beginFile("source").openOutputStream()) {
                    body.write(7);
                }
            }
            try (var reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(target.toByteArray()))) {
                assertTrue(reader.next());
                assertEquals("source", reader.readAttributes().path());
                try (var body = reader.openInputStream()) {
                    assertArrayEquals(new byte[]{7}, body.readAllBytes());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Rejects NUL names before changing the index, reserving parents, or replacing an existing entry.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNulFileSystemEntryNames(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("names.tar");
        if (update) {
            try (var writer = TarArkivoStreamingWriter.create(archive)) {
                writer.beginFile("source").close();
            }
        }
        try (var fs = update ? TarArkivoFileSystem.update(archive) : TarArkivoFileSystem.create(archive)) {
            Path source = fs.getPath("/source");
            if (!update) {
                Files.write(source, new byte[0]);
            }
            for (String invalid : List.of("\0entry", "entry\0suffix", "entry\0", "parent\0/child",
                    "p".repeat(101) + "\0suffix")) {
                Path target = fs.getPath("/" + invalid);
                assertThrows(FileSystemException.class, () -> Files.write(target, new byte[]{1}));
                assertThrows(FileSystemException.class, () -> Files.createDirectory(target));
                assertThrows(FileSystemException.class, () -> Files.createSymbolicLink(target, fs.getPath("source")));
                assertThrows(FileSystemException.class, () -> Files.createLink(target, source));
                if (update) {
                    assertThrows(FileSystemException.class, () -> Files.copy(source, target));
                    assertThrows(FileSystemException.class, () -> Files.move(source, target));
                    assertTrue(Files.exists(source));
                    assertFalse(Files.exists(target));
                }
            }
            Files.write(fs.getPath("/after"), new byte[]{7});
        }
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals("source", reader.readAttributes().path());
            assertTrue(reader.next());
            assertEquals("after", reader.readAttributes().path());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{7}, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Rejects malformed principal names before mutation while preserving valid USTAR and PAX text.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMalformedPrincipalNames(boolean update, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("principals.tar");
        String user = "user\uD83D\uDE00";
        String group = "group\uD83D\uDE00".repeat(8);
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archive)) {
            try (ArkivoStreamingWriter.Entry entry = writer.beginFile("entry")) {
                if (!update) {
                    verifyPrincipalNames(Objects.requireNonNull(entry.attributeView(TarArkivoEntryAttributeView.class)),
                            Objects.requireNonNull(entry.attributeView(PosixFileAttributeView.class)), user, group);
                }
                try (OutputStream body = entry.openOutputStream()) {
                    body.write(7);
                }
            }
            writer.beginFile("following").close();
        }
        if (update) {
            try (var fs = TarArkivoFileSystem.update(archive)) {
                Path path = fs.getPath("/entry");
                TarArkivoEntryAttributeView tar = Objects.requireNonNull(
                        Files.getFileAttributeView(path, TarArkivoEntryAttributeView.class));
                verifyPrincipalNames(tar, Objects.requireNonNull(
                        Files.getFileAttributeView(path, PosixFileAttributeView.class)), user, group);
                TarArkivoEntryAttributes snapshot = tar.readAttributes();
                assertThrows(IllegalArgumentException.class,
                        () -> Files.setAttribute(path, "tar:userName", "bad\0name"));
                assertThrows(IllegalArgumentException.class,
                        () -> Files.setAttribute(path, "tar:groupName", "bad\uD800name"));
                assertEquals(user, snapshot.userName());
                assertEquals(group, snapshot.groupName());
                assertEquals(user, tar.readAttributes().userName());
                assertEquals(group, tar.readAttributes().groupName());
            }
        }
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(user, attributes.userName());
            assertEquals(group, attributes.groupName());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{7}, body.readAllBytes());
            }
            assertTrue(reader.next());
            assertEquals("following", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Exercises both attribute views without allowing an invalid assignment to replace the preceding value.
    private static void verifyPrincipalNames(
            TarArkivoEntryAttributeView tar, PosixFileAttributeView posix, String user, String group
    ) throws IOException {
        tar.setUserName("original-user");
        tar.setGroupName("original-group");
        for (String invalid : List.of("x\0y", "x\uD800", "x\uDC00", "x\uDC00\uD800",
                "x".repeat(33) + "\0tail", "x".repeat(33) + "\uD800tail")) {
            assertThrows(IllegalArgumentException.class, () -> tar.setUserName(invalid));
            assertThrows(IllegalArgumentException.class, () -> tar.setGroupName(invalid));
            assertThrows(IllegalArgumentException.class, () -> posix.setOwner(() -> invalid));
            assertThrows(IllegalArgumentException.class, () -> posix.setGroup(() -> invalid));
            assertEquals("original-user", tar.readAttributes().userName());
            assertEquals("original-group", tar.readAttributes().groupName());
        }
        tar.setUserName(null);
        tar.setGroupName(null);
        assertNull(tar.readAttributes().userName());
        assertNull(tar.readAttributes().groupName());
        tar.setUserName("");
        tar.setGroupName("");
        assertEquals("", tar.readAttributes().userName());
        assertEquals("", tar.readAttributes().groupName());
        posix.setOwner(() -> user);
        posix.setGroup(() -> group);
        assertEquals(user, tar.readAttributes().userName());
        assertEquals(group, tar.readAttributes().groupName());
    }

    /// Verifies an invalid fixed header cannot leave PAX records that affect the following entry.
    @ParameterizedTest
    @MethodSource("completionFailures")
    void discardsUnencodableEntries(int paxField, boolean staged, boolean priorEntry) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(target, ArkivoEditStorage.memory())) {
            if (priorEntry) {
                writer.beginFile("before").close();
            }
            byte @Unmodifiable [] before = target.toByteArray();
            ArkivoStreamingWriter.Entry entry = writer.beginFile(paxField == 0 ? "p".repeat(101) : "bad");
            TarArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(TarArkivoEntryAttributeView.class));
            switch (paxField) {
                case 1 -> view.setUserName("u".repeat(33));
                case 2 -> view.setGroupName("g".repeat(33));
                case 3 -> view.setTimes(FileTime.fromMillis(1234L), null, null);
                default -> { }
            }
            view.setMode(Integer.MAX_VALUE);
            if (staged) {
                OutputStream body = entry.openOutputStream();
                body.write(1);
                assertThrows(IOException.class, body::close);
                assertArrayEquals(before, target.toByteArray());
                body.close();
            } else {
                assertThrows(IOException.class, entry::close);
                assertArrayEquals(before, target.toByteArray());
                entry.close();
            }
            assertThrows(IllegalStateException.class, () -> view.setMode(0644));
            writer.beginFile("after").close();
        }
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(target.toByteArray()))) {
            if (priorEntry) {
                assertTrue(reader.next());
                assertEquals("before", reader.readAttributes().path());
            }
            assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("after", attributes.path());
            assertEquals(FileTime.fromMillis(0L), attributes.lastModifiedTime());
            assertEquals(0L, attributes.size());
            assertFalse(reader.next());
        }
    }

    /// Verifies snapshot validation neither emits PAX metadata nor consumes the supplied body.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void rejectsSnapshotHeadersBeforeOutput(int field) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        long size = field == 3 ? 1L << 33 : 1L;
        TarEntryAttributes attributes = new TarEntryAttributes(
                "p".repeat(101), TarEntryAttributes.REGULAR_TYPE, false, field == 0 ? Integer.MAX_VALUE : 0644,
                field == 1 ? -1L : 0L, field == 2 ? -1L : 0L, null, null, null, size,
                FileTime.fromMillis(1234L), null, null, null);
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{7});
        try (TarArkivoStreamingWriterImpl writer = new TarArkivoStreamingWriterImpl(target, ArkivoEditStorage.memory());
             var body = Channels.newChannel(source)) {
            assertThrows(IOException.class, () -> writer.writeSnapshot(attributes, body, size));
            assertEquals(0, target.size());
            assertEquals(1, source.available());
            writer.beginFile("after").close();
        }
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(target.toByteArray()))) {
            assertTrue(reader.next());
            assertEquals("after", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Compares complete archives across entry types, USTAR/PAX paths, numeric boundaries, and timestamp ranges.
    @ParameterizedTest
    @MethodSource("equivalentHeaders")
    void usesIdenticalHeadersForStreamingAndSnapshots(int type, int pathKind, int metadataKind) throws IOException {
        String path = switch (pathKind) {
            case 0 -> "entry";
            case 1 -> "prefix/".repeat(16) + "entry";
            default -> "\u00e9".repeat(80);
        };
        String link = pathKind == 2 ? "target/".repeat(20) : "target";
        byte @Unmodifiable [] content = type == 0 ? new byte[]{1, 2, 3} : new byte[0];
        FileTime time = FileTime.from(switch (metadataKind) {
            case 0 -> Instant.EPOCH;
            case 1 -> Instant.ofEpochSecond(-2L, 123_456_789L);
            default -> Instant.ofEpochSecond(1L << 34, 987_654_321L);
        });
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        TarArkivoEntryAttributes attributes;
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(streamed, ArkivoEditStorage.memory())) {
            ArkivoStreamingWriter.Entry entry = switch (type) {
                case 0 -> writer.beginFile(path);
                case 1 -> writer.beginDirectory(path);
                case 2 -> writer.beginSymbolicLink(path, link);
                default -> writer.beginHardLink(path, link);
            };
            TarArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(TarArkivoEntryAttributeView.class));
            view.setTimes(time, null, null);
            view.setUserId(metadataKind == 2 ? Long.MAX_VALUE : (1L << 21) - 1L);
            view.setGroupId(metadataKind == 0 ? 0L : 1L << 21);
            view.setUserName("\u00e9".repeat(metadataKind == 0 ? 16 : 17));
            view.setGroupName("g".repeat(metadataKind == 0 ? 32 : 33));
            if (metadataKind != 0) {
                view.setRecordedLastAccessTime(time);
                view.setRecordedStatusChangeTime(time);
                view.setRecordedCreationTime(time);
            }
            attributes = view.readAttributes();
            if (type == 0) {
                try (OutputStream body = entry.openOutputStream()) {
                    body.write(content);
                }
            } else {
                entry.close();
            }
        }
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriterImpl writer = new TarArkivoStreamingWriterImpl(rewritten, ArkivoEditStorage.memory());
             var body = Channels.newChannel(new ByteArrayInputStream(content))) {
            writer.writeSnapshot(attributes, body, content.length);
        }
        assertArrayEquals(streamed.toByteArray(), rewritten.toByteArray());
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(rewritten.toByteArray()))) {
            assertTrue(reader.next());
            TarArkivoEntryAttributes decoded = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(type == 1 ? path + "/" : path, decoded.path());
            assertEquals(time, decoded.lastModifiedTime());
            assertEquals(attributes.userId(), decoded.userId());
            assertEquals(attributes.groupId(), decoded.groupId());
            assertEquals(attributes.userName(), decoded.userName());
            assertEquals(attributes.groupName(), decoded.groupName());
            if (type >= 2) {
                assertEquals(attributes.linkName(), decoded.linkName());
            }
            if (type == 0) {
                try (var body = reader.openInputStream()) {
                    assertArrayEquals(content, body.readAllBytes());
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Supplies PAX triggers with both entry-completion paths and an optional preceding entry.
    private static Stream<Arguments> completionFailures() {
        return Stream.of(0, 1, 2, 3).flatMap(field -> Stream.of(false, true)
                .flatMap(staged -> Stream.of(false, true).map(prior -> Arguments.of(field, staged, prior))));
    }

    /// Supplies independent entry-type, path-encoding, and extended-metadata cases.
    private static Stream<Arguments> equivalentHeaders() {
        return Stream.of(0, 1, 2, 3).flatMap(type -> Stream.of(0, 1, 2)
                .flatMap(path -> Stream.of(0, 1, 2).map(metadata -> Arguments.of(type, path, metadata))));
    }
}
