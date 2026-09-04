// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies live AR attribute projections for pending streaming-writer members.
@NotNullByDefault
final class ArArkivoStreamingWriterAttributesTest {
    /// Verifies long-name defaults, live metadata updates, validation, and post-commit stability.
    @Test
    void exposesConfiguredFileAttributesUntilCommit() throws IOException {
        String path = "long-member-name.bin";
        byte[] content = {1, 2, 3};
        FileTime modifiedTime = FileTime.from(Instant.parse("2031-02-03T04:05:06Z"));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile(path);
            ArArkivoEntryAttributeView arView = requireView(entry, ArArkivoEntryAttributeView.class);
            BasicFileAttributeView basicView = requireView(entry, BasicFileAttributeView.class);
            assertSame(arView, basicView);
            assertEquals("ar", arView.name());

            ArArkivoEntryAttributes attributes = arView.readAttributes();
            assertEquals(path, attributes.path());
            assertEquals("#1/" + path.getBytes(StandardCharsets.UTF_8).length, attributes.identifier());
            assertEquals(0L, attributes.userId());
            assertEquals(0L, attributes.groupId());
            assertEquals(0100644, attributes.mode());
            assertEquals(FileTime.fromMillis(0L), attributes.lastModifiedTime());
            assertEquals(attributes.lastModifiedTime(), attributes.lastAccessTime());
            assertEquals(attributes.lastModifiedTime(), attributes.creationTime());
            assertTrue(attributes.isRegularFile());
            assertFalse(attributes.isDirectory());
            assertFalse(attributes.isSymbolicLink());
            assertFalse(attributes.isOther());
            assertEquals(0L, attributes.size());
            assertNull(attributes.fileKey());

            arView.setTimes(modifiedTime, FileTime.fromMillis(1L), FileTime.fromMillis(2L));
            arView.setUserId(123L);
            arView.setGroupId(456L);
            arView.setMode(0100600);
            arView.setSize(content.length);
            assertEquals(123L, attributes.userId());
            assertEquals(456L, attributes.groupId());
            assertEquals(0100600, attributes.mode());
            assertEquals(modifiedTime, attributes.lastModifiedTime());
            assertEquals(modifiedTime, attributes.lastAccessTime());
            assertEquals(modifiedTime, attributes.creationTime());
            assertEquals(content.length, attributes.size());

            assertThrows(IllegalArgumentException.class, () -> arView.setUserId(-1L));
            assertThrows(IllegalArgumentException.class, () -> arView.setGroupId(-1L));
            assertThrows(IllegalArgumentException.class, () -> arView.setMode(-1));
            assertThrows(IllegalArgumentException.class, () -> arView.setMode(040755));
            assertThrows(IllegalArgumentException.class, () -> arView.setSize(-1L));
            assertThrows(IllegalArgumentException.class, () -> arView.setSize(10_000_000_000L));

            try (OutputStream body = entry.openOutputStream()) {
                body.write(content);
            }
            assertThrows(IllegalStateException.class, () -> arView.setUserId(1L));
            assertEquals(123L, attributes.userId());
            assertEquals(content.length, attributes.size());
        }

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray())
        )) {
            assertTrue(reader.next());
            ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals(path, attributes.path());
            assertEquals(123L, attributes.userId());
            assertEquals(456L, attributes.groupId());
            assertEquals(0100600, attributes.mode());
            assertEquals(modifiedTime, attributes.lastModifiedTime());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies directory and symbolic-link modes cannot be changed to another member type.
    @Test
    void preservesFixedBodyEntryTypes() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            ArArkivoEntryAttributeView directoryView = requireView(directory, ArArkivoEntryAttributeView.class);
            assertThrows(IllegalArgumentException.class, () -> directoryView.setMode(0100644));
            directoryView.setMode(040700);
            directory.close();
            assertThrows(IllegalStateException.class, () -> directoryView.setMode(040755));

            ArkivoStreamingWriter.Entry symbolicLink = writer.beginSymbolicLink("link", "directory");
            ArArkivoEntryAttributeView linkView = requireView(symbolicLink, ArArkivoEntryAttributeView.class);
            assertThrows(IllegalArgumentException.class, () -> linkView.setMode(0100644));
            linkView.setMode(0120700);
            symbolicLink.close();
            assertThrows(IllegalStateException.class, () -> linkView.setMode(0120777));
        }
    }

    /// Returns the requested non-null pending member attribute view.
    private static <V extends FileAttributeView> V requireView(
            ArkivoStreamingWriter.Entry entry,
            Class<V> type
    ) throws IOException {
        return Objects.requireNonNull(entry.attributeView(type), type.getName());
    }
}
