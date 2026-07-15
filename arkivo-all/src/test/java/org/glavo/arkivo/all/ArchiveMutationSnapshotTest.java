// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies update-session snapshot semantics across every writable archive format.
@NotNullByDefault
final class ArchiveMutationSnapshotTest {
    /// Writable formats and their documented active-writer visibility behavior.
    private static final @Unmodifiable List<FormatCase> FORMATS = List.of(
            new FormatCase("ar", ".a", false),
            new FormatCase("tar", ".tar", false),
            new FormatCase("zip", ".zip", true),
            new FormatCase("7z", ".7z", false)
    );

    /// Replacement timestamp used by metadata snapshot assertions.
    private static final FileTime REPLACEMENT_TIME = FileTime.fromMillis(1_700_000_000_000L);

    /// Verifies opened channels, directory streams, and attributes retain their pre-mutation state.
    @Test
    @Timeout(value = 120)
    void openedResourcesRetainMutationSnapshots(@TempDir Path directory) throws Exception {
        for (FormatCase format : FORMATS) {
            Path archive = directory.resolve("snapshot-" + format.name() + format.extension());
            byte[] replacementBefore = bytes("replacement-before-" + format.name());
            byte[] replacementAfter = bytes("replacement-after-with-a-different-size-" + format.name());
            byte[] deletedContent = bytes("deleted-" + format.name());
            byte[] movedContent = bytes("moved-" + format.name());
            byte[] metadataContent = bytes("metadata-" + format.name());
            byte[] addedContent = bytes("added-" + format.name());
            createArchive(archive, format.name(), Map.of(
                    "replace.bin", replacementBefore,
                    "delete.bin", deletedContent,
                    "move.bin", movedContent,
                    "metadata.bin", metadataContent
            ));

            try (ArkivoFileSystem fileSystem = openUpdate(format, archive);
                 SeekableByteChannel replacementSnapshot =
                         Files.newByteChannel(fileSystem.getPath("/replace.bin"));
                 SeekableByteChannel deletedSnapshot =
                         Files.newByteChannel(fileSystem.getPath("/delete.bin"));
                 SeekableByteChannel movedSnapshot =
                         Files.newByteChannel(fileSystem.getPath("/move.bin"));
                 DirectoryStream<Path> directorySnapshot =
                         Files.newDirectoryStream(fileSystem.getPath("/"))) {
                Path replacement = fileSystem.getPath("/replace.bin");
                Path deleted = fileSystem.getPath("/delete.bin");
                Path moved = fileSystem.getPath("/move.bin");
                Path movedTarget = fileSystem.getPath("/moved.bin");
                Path metadata = fileSystem.getPath("/metadata.bin");
                Path added = fileSystem.getPath("/added.bin");
                BasicFileAttributes replacementAttributes =
                        Files.readAttributes(replacement, BasicFileAttributes.class);
                BasicFileAttributes metadataAttributes =
                        Files.readAttributes(metadata, BasicFileAttributes.class);
                FileTime originalMetadataTime = metadataAttributes.lastModifiedTime();

                Files.write(replacement, replacementAfter, StandardOpenOption.TRUNCATE_EXISTING);
                Files.delete(deleted);
                Files.move(moved, movedTarget);
                Files.setLastModifiedTime(metadata, REPLACEMENT_TIME);
                Files.write(added, addedContent, StandardOpenOption.CREATE_NEW);

                assertArrayEquals(replacementBefore, readAll(replacementSnapshot), archive.toString());
                assertArrayEquals(deletedContent, readAll(deletedSnapshot), archive.toString());
                assertArrayEquals(movedContent, readAll(movedSnapshot), archive.toString());
                assertEquals(replacementBefore.length, replacementAttributes.size(), archive.toString());
                assertEquals(originalMetadataTime, metadataAttributes.lastModifiedTime(), archive.toString());
                assertEquals(replacementAfter.length,
                        Files.readAttributes(replacement, BasicFileAttributes.class).size(), archive.toString());
                assertEquals(REPLACEMENT_TIME,
                        Files.readAttributes(metadata, BasicFileAttributes.class).lastModifiedTime(),
                        archive.toString());
                assertEquals(
                        Set.of("replace.bin", "delete.bin", "move.bin", "metadata.bin"),
                        childNames(directorySnapshot),
                        archive.toString()
                );
                assertEquals(
                        Set.of("replace.bin", "moved.bin", "metadata.bin", "added.bin"),
                        currentChildNames(fileSystem.getPath("/")),
                        archive.toString()
                );
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(format.name(), archive)) {
                assertArrayEquals(replacementAfter,
                        Files.readAllBytes(fileSystem.getPath("/replace.bin")), archive.toString());
                assertThrows(NoSuchFileException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/delete.bin")), archive.toString());
                assertThrows(NoSuchFileException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/move.bin")), archive.toString());
                assertArrayEquals(movedContent,
                        Files.readAllBytes(fileSystem.getPath("/moved.bin")), archive.toString());
                assertArrayEquals(addedContent,
                        Files.readAllBytes(fileSystem.getPath("/added.bin")), archive.toString());
                assertEquals(REPLACEMENT_TIME,
                        Files.readAttributes(fileSystem.getPath("/metadata.bin"), BasicFileAttributes.class)
                                .lastModifiedTime(),
                        archive.toString());
            }
        }
    }

    /// Verifies new reads of an active write are rejected unless the format documents old-state visibility.
    @Test
    @Timeout(value = 120)
    void activeWritesExposeOnlyDocumentedState(@TempDir Path directory) throws Exception {
        for (FormatCase format : FORMATS) {
            Path archive = directory.resolve("active-" + format.name() + format.extension());
            byte[] oldContent = bytes("old-active-content-" + format.name());
            byte[] newContent = bytes("new-active-content-" + format.name());
            byte[] stableContent = bytes("stable-content-" + format.name());
            createArchive(archive, format.name(), Map.of(
                    "active.bin", oldContent,
                    "stable.bin", stableContent
            ));

            try (ArkivoFileSystem fileSystem = openUpdate(format, archive);
                 SeekableByteChannel snapshot = Files.newByteChannel(fileSystem.getPath("/active.bin"));
                 SeekableByteChannel writer = Files.newByteChannel(
                         fileSystem.getPath("/active.bin"),
                         Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                 )) {
                writeAll(writer, newContent);

                assertArrayEquals(oldContent, readAll(snapshot), archive.toString());
                assertArrayEquals(stableContent,
                        Files.readAllBytes(fileSystem.getPath("/stable.bin")), archive.toString());
                if (format.readsActiveOldState()) {
                    assertArrayEquals(oldContent,
                            Files.readAllBytes(fileSystem.getPath("/active.bin")), archive.toString());
                    try (InputStream input = Files.newInputStream(fileSystem.getPath("/active.bin"))) {
                        assertArrayEquals(oldContent, input.readAllBytes(), archive.toString());
                    }
                    assertEquals(oldContent.length,
                            Files.readAttributes(fileSystem.getPath("/active.bin"), BasicFileAttributes.class).size(),
                            archive.toString());
                } else {
                    assertThrows(FileSystemException.class,
                            () -> Files.readAllBytes(fileSystem.getPath("/active.bin")), archive.toString());
                    assertThrows(FileSystemException.class,
                            () -> Files.newInputStream(fileSystem.getPath("/active.bin")), archive.toString());
                    assertThrows(FileSystemException.class,
                            () -> Files.readAttributes(
                                    fileSystem.getPath("/active.bin"),
                                    BasicFileAttributes.class
                            ),
                            archive.toString());
                }

                writer.close();
                assertArrayEquals(newContent,
                        Files.readAllBytes(fileSystem.getPath("/active.bin")), archive.toString());
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(format.name(), archive)) {
                assertArrayEquals(newContent,
                        Files.readAllBytes(fileSystem.getPath("/active.bin")), archive.toString());
            }
        }
    }

    /// Opens one complete-rewrite update session.
    private static ArkivoFileSystem openUpdate(FormatCase format, Path archive) throws IOException {
        return ArkivoFormats.openFileSystem(format.name(), archive, Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        ));
    }

    /// Creates one archive through the generic streaming-writer API.
    private static void createArchive(Path archive, String format, Map<String, byte[]> entries) throws IOException {
        try (ArkivoStreamingWriter writer =
                     ArkivoFormats.openStreamingWriter(format, Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writer.beginFile(entry.getKey());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(entry.getValue());
                }
            }
        }
    }

    /// Reads every byte from a channel after restoring its position to the beginning.
    private static byte[] readAll(SeekableByteChannel channel) throws IOException {
        channel.position(0L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            output.write(buffer.array(), 0, buffer.remaining());
            buffer.clear();
        }
        return output.toByteArray();
    }

    /// Writes every supplied byte to a channel.
    private static void writeAll(SeekableByteChannel channel, byte[] bytes) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    /// Returns the file names exposed by one directory snapshot.
    private static @Unmodifiable Set<String> childNames(DirectoryStream<Path> stream) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Path child : stream) {
            names.add(child.getFileName().toString());
        }
        return Set.copyOf(names);
    }

    /// Opens a directory stream and returns its current child file names.
    private static @Unmodifiable Set<String> currentChildNames(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return childNames(stream);
        }
    }

    /// Returns UTF-8 bytes for deterministic fixture text.
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// Describes one writable format and its active-writer read behavior.
    ///
    /// @param name installed format name
    /// @param extension fixture archive extension
    /// @param readsActiveOldState whether new reads observe the old state during an active replacement
    private record FormatCase(String name, String extension, boolean readsActiveOldState) {
    }
}
