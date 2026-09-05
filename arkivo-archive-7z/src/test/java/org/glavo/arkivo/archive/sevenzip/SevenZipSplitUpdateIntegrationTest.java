// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests complete-rewrite updates that preserve, change, or publish a split-volume 7z layout.
@NotNullByDefault
public final class SevenZipSplitUpdateIntegrationTest {
    /// The isolated directory used for path-backed update publication.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies that a path-backed update preserves an existing split layout by default.
    @Test
    public void updatePreservesPathBackedSplitOutput() throws IOException {
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = temporaryDirectory.resolve("sample.7z.002");
        byte[] initialContent = new byte[512];
        Arrays.fill(initialContent, (byte) 7);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(
                firstVolume,
                96L,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/value.bin"), initialContent);
        }
        assertTrue(Files.exists(secondVolume));

        byte[] updatedContent = new byte[400];
        Arrays.fill(updatedContent, (byte) 9);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(firstVolume)) {
            Files.write(fileSystem.getPath("/value.bin"), updatedContent);
            Files.writeString(fileSystem.getPath("/new.txt"), "split-new", StandardCharsets.UTF_8);
        }

        assertTrue(Files.exists(secondVolume));
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/value.bin")));
            assertEquals("split-new", Files.readString(fileSystem.getPath("/new.txt"), StandardCharsets.UTF_8));
        }
    }

    /// Verifies that an explicit path update can replace a complete archive with bounded numbered volumes.
    @Test
    public void updateCanSelectExplicitPathSplitSize() throws IOException {
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = temporaryDirectory.resolve("sample.7z.002");
        byte[] expected = new byte[384];
        Arrays.fill(expected, (byte) 0x5a);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(firstVolume)) {
            Files.writeString(fileSystem.getPath("/value.txt"), "before", StandardCharsets.UTF_8);
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(
                firstVolume,
                96L,
                SevenZipArchiveOptions.UPDATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/value.bin"), expected);
        }

        assertTrue(Files.exists(secondVolume));
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/value.bin")));
        }
    }

    /// Verifies that an explicit no-split update transactionally merges existing numbered volumes.
    @Test
    public void updateCanMergePathBackedSplitOutput() throws IOException {
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = temporaryDirectory.resolve("sample.7z.002");
        byte[] content = new byte[320];
        Arrays.fill(content, (byte) 11);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(
                firstVolume,
                80L,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/value.bin"), content);
        }
        assertTrue(Files.exists(secondVolume));

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.updateSingleVolume(firstVolume)) {
            Files.writeString(fileSystem.getPath("/new.txt"), "merged", StandardCharsets.UTF_8);
        }

        assertTrue(Files.exists(firstVolume));
        assertFalse(Files.exists(secondVolume));
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.bin")));
            assertEquals("merged", Files.readString(fileSystem.getPath("/new.txt"), StandardCharsets.UTF_8));
        }
    }

    /// Verifies complete-rewrite updates from an explicit volume source to a transactional volume target.
    @Test
    public void updatesExplicitVolumeSourceToTarget() throws IOException {
        byte[] originalContent = "volume-source".getBytes(StandardCharsets.UTF_8);
        RecordingVolumeSource source = new RecordingVolumeSource(createSourceVolumes(originalContent));
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(source, target, 23L)) {
            Files.writeString(fileSystem.getPath("/hello.txt"), "volume-updated", StandardCharsets.UTF_8);
            Files.writeString(fileSystem.getPath("/new.txt"), "new", StandardCharsets.UTF_8);
        }

        byte[][] committedVolumes = target.committedVolumes();
        assertTrue(committedVolumes.length > 1);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                SevenZipTestArchiveFixtures.volumeSource(committedVolumes)
        )) {
            assertEquals(
                    "volume-updated",
                    Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8)
            );
            assertEquals("new", Files.readString(fileSystem.getPath("/new.txt"), StandardCharsets.UTF_8));
        }
        assertTrue(target.allOpenedChannelsClosed());
        assertTrue(source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that explicit multi-volume publication failure rolls back all output.
    @Test
    public void failedExplicitVolumeUpdateRollsBackOutput() throws IOException {
        byte[] originalContent = "volume-source".getBytes(StandardCharsets.UTF_8);
        RecordingVolumeSource source = new RecordingVolumeSource(createSourceVolumes(originalContent));
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(source, target, 23L);
        Files.writeString(fileSystem.getPath("/hello.txt"), "volume-updated", StandardCharsets.UTF_8);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume commit failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertTrue(target.allOpenedChannelsClosed());
        assertTrue(source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Creates split source volumes containing one file named `hello.txt`.
    private static byte[][] createSourceVolumes(byte[] content) throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 17L)) {
            Files.write(fileSystem.getPath("/hello.txt"), content);
        }
        byte[][] volumes = target.committedVolumes();
        assertTrue(volumes.length > 1);
        return volumes;
    }
}
