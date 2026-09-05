// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP64 boundaries that require archive structures too large for the default test tier.
@NotNullByDefault
public final class Zip64ScalabilityTest {
    /// Number of entries required to overflow a classic ZIP entry-count field.
    private static final int ZIP64_ENTRY_COUNT = 0x1_0000;

    /// Verifies that the streaming ZIP writer emits ZIP64 end records when the entry count overflows ZIP32 fields.
    @Test
    public void streamingWriterZip64EndRecordForManyEntries() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            writeEmptyDirectories(writer);
        }

        byte @Unmodifiable [] archive = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = archive.length - 22;
        int locatorOffset = endOffset - 20;
        int zip64EndOffset = Math.toIntExact(buffer.getLong(locatorOffset + 8));

        assertEquals(0x07064b50, buffer.getInt(locatorOffset));
        assertEquals(0x06064b50, buffer.getInt(zip64EndOffset));
        assertEquals(44L, buffer.getLong(zip64EndOffset + 4));
        assertEquals(ZIP64_ENTRY_COUNT, buffer.getLong(zip64EndOffset + 24));
        assertEquals(ZIP64_ENTRY_COUNT, buffer.getLong(zip64EndOffset + 32));
        assertEquals(0x06054b50, buffer.getInt(endOffset));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 8)));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 10)));
    }

    /// Verifies that split ZIP64 locator disk metadata matches the physical volume layout.
    @Test
    public void streamingWriterZip64EndRecordForSplitArchive(@TempDir Path directory) throws IOException {
        SplitVolumeLayout layout = new SplitVolumeLayout(directory);
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(
                new ArkivoPathVolumeTarget(layout),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            writeEmptyDirectories(writer);
        }

        @Unmodifiable List<Path> volumes = layout.volumePaths();
        assertTrue(volumes.size() > 1);
        byte @Unmodifiable [] finalVolume = Files.readAllBytes(volumes.get(volumes.size() - 1));
        ByteBuffer finalBuffer = ByteBuffer.wrap(finalVolume).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = finalVolume.length - 22;
        int locatorOffset = endOffset - 20;

        assertEquals(0x07064b50, finalBuffer.getInt(locatorOffset));
        int zip64EndDisk = finalBuffer.getInt(locatorOffset + 4);
        long zip64EndOffset = finalBuffer.getLong(locatorOffset + 8);
        assertEquals(volumes.size(), Integer.toUnsignedLong(finalBuffer.getInt(locatorOffset + 16)));
        assertTrue(Integer.toUnsignedLong(zip64EndDisk) < volumes.size());

        byte @Unmodifiable [] zip64EndVolume = Files.readAllBytes(volumes.get(zip64EndDisk));
        ByteBuffer zip64Buffer = ByteBuffer.wrap(zip64EndVolume).order(ByteOrder.LITTLE_ENDIAN);
        int zip64Offset = Math.toIntExact(zip64EndOffset);
        assertEquals(0x06064b50, zip64Buffer.getInt(zip64Offset));
        assertEquals(44L, zip64Buffer.getLong(zip64Offset + 4));
        assertEquals(zip64EndDisk, zip64Buffer.getInt(zip64Offset + 16));
        assertEquals(ZIP64_ENTRY_COUNT, zip64Buffer.getLong(zip64Offset + 32));

        assertEquals(0x06054b50, finalBuffer.getInt(endOffset));
        assertEquals(0xffff, Short.toUnsignedInt(finalBuffer.getShort(endOffset + 10)));
    }

    /// Writes the minimum number of empty directories that requires a ZIP64 entry count.
    ///
    /// @param writer the streaming writer that receives the directories
    private static void writeEmptyDirectories(ZipArkivoStreamingWriter writer) throws IOException {
        for (int index = 0; index < ZIP64_ENTRY_COUNT; index++) {
            var entry = writer.beginDirectory("dir-" + index);
            entry.close();
        }
    }

    /// Maps one split ZIP transaction to deterministic paths under a temporary directory.
    @NotNullByDefault
    private static final class SplitVolumeLayout implements ArkivoVolumePathLayout {
        /// Sentinel used before the transaction publishes its final volume index.
        private static final long UNKNOWN_FINAL_VOLUME_INDEX = -1L;

        /// Directory receiving published split volumes.
        private final Path directory;

        /// Final published volume index, or [#UNKNOWN_FINAL_VOLUME_INDEX] before publication.
        private long finalVolumeIndex = UNKNOWN_FINAL_VOLUME_INDEX;

        /// Creates a split-volume layout under the given directory.
        ///
        /// @param directory the temporary publication directory
        private SplitVolumeLayout(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        /// Returns the temporary publication directory.
        @Override
        public Path outputDirectory() {
            return directory;
        }

        /// Returns the deterministic path for one split volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            if (index < 0L || index > finalVolumeIndex) {
                throw new IllegalArgumentException("Volume index is out of range");
            }
            if (this.finalVolumeIndex != UNKNOWN_FINAL_VOLUME_INDEX
                    && this.finalVolumeIndex != finalVolumeIndex) {
                throw new IllegalStateException("Final volume index changed during publication");
            }
            this.finalVolumeIndex = finalVolumeIndex;
            return pathFor(index, finalVolumeIndex);
        }

        /// Returns no existing paths for this fresh temporary layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            return List.of();
        }

        /// Returns all published volume paths in logical order.
        ///
        /// @return immutable published paths from volume zero through the final volume
        private @Unmodifiable List<Path> volumePaths() {
            if (finalVolumeIndex == UNKNOWN_FINAL_VOLUME_INDEX) {
                throw new IllegalStateException("Split volumes have not been published");
            }
            ArrayList<Path> paths = new ArrayList<>();
            for (long index = 0L; index <= finalVolumeIndex; index++) {
                paths.add(pathFor(index, finalVolumeIndex));
            }
            return List.copyOf(paths);
        }

        /// Returns one deterministic path without changing publication state.
        ///
        /// @param index the zero-based volume index
        /// @param finalVolumeIndex the zero-based final volume index
        /// @return the publication path for `index`
        private Path pathFor(long index, long finalVolumeIndex) {
            String name = index == finalVolumeIndex ? "archive.zip" : "archive.part-" + index;
            return directory.resolve(name);
        }
    }
}
