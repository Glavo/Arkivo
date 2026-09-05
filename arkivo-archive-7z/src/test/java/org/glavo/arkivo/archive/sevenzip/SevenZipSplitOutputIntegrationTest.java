// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests path-backed and transactional split-volume 7z publication.
@NotNullByDefault
public final class SevenZipSplitOutputIntegrationTest {
    /// The isolated directory used for path-backed split-volume publication.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies that path-backed writes produce conventional bounded split volumes that can be reopened.
    @Test
    public void createsPathBackedSplitArchive() throws IOException {
        long splitSize = 128L;
        byte[] content = new byte[1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) index;
        }
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(
                firstVolume,
                splitSize,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/content.bin"), content);
            assertFalse(Files.exists(firstVolume));
        }

        List<Path> volumePaths = existingVolumePaths(firstVolume);
        assertTrue(volumePaths.size() > 1);
        for (int index = 0; index < volumePaths.size(); index++) {
            long volumeSize = Files.size(volumePaths.get(index));
            assertTrue(volumeSize > 0L);
            assertTrue(volumeSize <= splitSize);
            if (index + 1 < volumePaths.size()) {
                assertEquals(splitSize, volumeSize);
            }
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that replacing path-backed split output removes stale higher-numbered volumes.
    @Test
    public void pathBackedSplitArchiveRemovesStaleVolumes() throws IOException {
        long splitSize = 4096L;
        byte[] content = "replacement split archive".getBytes(StandardCharsets.UTF_8);
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = volumePath(firstVolume, 2);
        Path thirdVolume = volumePath(firstVolume, 3);
        Files.write(firstVolume, new byte[]{1});
        Files.write(secondVolume, new byte[]{2});
        Files.write(thirdVolume, new byte[]{3});

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(
                firstVolume,
                splitSize,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/replacement.txt"), content);
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(firstVolume));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(secondVolume));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(thirdVolume));
        }

        assertTrue(Files.exists(firstVolume));
        assertFalse(Files.exists(secondVolume));
        assertFalse(Files.exists(thirdVolume));
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/replacement.txt")));
        }
    }

    /// Verifies that publication replaces a numbered volume created after the output transaction starts.
    @Test
    public void pathBackedSplitCreationReplacesLateVolume() throws IOException {
        byte[] existingContent = new byte[]{9, 8, 7};
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = volumePath(firstVolume, 2);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(
                firstVolume,
                64L,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.write(fileSystem.getPath("/content.bin"), new byte[512]);
            Files.write(secondVolume, existingContent);
        }

        assertTrue(Files.exists(firstVolume));
        assertFalse(Arrays.equals(existingContent, Files.readAllBytes(secondVolume)));
    }

    /// Verifies that arbitrary transactional targets receive bounded volumes only when the file system closes.
    @Test
    public void createsSplitArchiveInVolumeTarget() throws IOException {
        long splitSize = 96L;
        byte[] content = new byte[768];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, splitSize)) {
            assertFalse(fileSystem.isReadOnly());
            Files.write(fileSystem.getPath("/content.bin"), content);
            assertEquals(0, target.openOutputCount());
        }

        byte[][] volumes = target.committedVolumes();
        assertEquals(1, target.openOutputCount());
        assertTrue(volumes.length > 1);
        assertTrue(target.allOpenedChannelsClosed());
        for (int index = 0; index < volumes.length; index++) {
            assertTrue(volumes[index].length > 0);
            assertTrue(volumes[index].length <= splitSize);
            if (index + 1 < volumes.length) {
                assertEquals(splitSize, volumes[index].length);
            }
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                SevenZipTestArchiveFixtures.volumeSource(volumes)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that an empty split archive still commits one readable non-empty volume.
    @Test
    public void createsEmptySplitArchiveInVolumeTarget() throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);

        try (SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.create(target, 1024L)) {
            // Closing the file system finalizes an archive without entries.
        }

        byte[][] volumes = target.committedVolumes();
        assertEquals(1, volumes.length);
        assertTrue(volumes[0].length > 0);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                SevenZipTestArchiveFixtures.volumeSource(volumes)
        ); DirectoryStream<Path> children = Files.newDirectoryStream(fileSystem.getPath("/"))) {
            assertFalse(children.iterator().hasNext());
        }
    }

    /// Verifies that a volume-open failure rolls back unpublished output and permits close retry.
    @Test
    public void splitArchiveTargetFailureRollsBack() throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(1L, false);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[512]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume open failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertTrue(target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that a target commit failure rolls back all staged volumes.
    @Test
    public void splitArchiveTargetCommitFailureRollsBack() throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[256]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume commit failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertTrue(target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that a zero-progress volume channel fails rather than blocking close and is rolled back.
    @Test
    public void splitArchiveZeroProgressTargetRollsBack() throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false, true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[128]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("7z volume write made no progress", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertTrue(target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that public split-output factories reject non-positive sizes and invalid first-volume names.
    @Test
    public void rejectsInvalidSplitOutputConfiguration() {
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoFileSystem.create(target, 0L));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoFileSystem.create(
                temporaryDirectory.resolve("sample.7z"),
                64L,
                SevenZipArchiveOptions.CREATE_DEFAULTS
        ));
    }

    /// Returns one conventional numbered volume path.
    private static Path volumePath(Path firstVolumePath, int volumeNumber) {
        if (volumeNumber <= 0) {
            throw new IllegalArgumentException("volumeNumber must be positive");
        }
        String fileName = firstVolumePath.getFileName().toString();
        int suffixStart = fileName.lastIndexOf('.') + 1;
        int suffixWidth = fileName.length() - suffixStart;
        String volumeText = Integer.toString(volumeNumber);
        StringBuilder builder = new StringBuilder(fileName.substring(0, suffixStart));
        for (int index = volumeText.length(); index < suffixWidth; index++) {
            builder.append('0');
        }
        return firstVolumePath.resolveSibling(builder.append(volumeText).toString());
    }

    /// Returns the contiguous conventional volumes that currently exist.
    private static @Unmodifiable List<Path> existingVolumePaths(Path firstVolumePath) {
        ArrayList<Path> paths = new ArrayList<>();
        for (int volumeNumber = 1; ; volumeNumber++) {
            Path path = volumePath(firstVolumePath, volumeNumber);
            if (!Files.exists(path)) {
                return List.copyOf(paths);
            }
            paths.add(path);
        }
    }
}
