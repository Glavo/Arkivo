// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT16_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies conventional split ZIP path construction and end-record discovery.
@NotNullByDefault
final class ZipSplitVolumePathsTest {
    /// Temporary directory containing synthetic ZIP end records.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies numbered paths preserve the base name and use a minimum two-digit suffix.
    @Test
    void constructsConventionalNumberedPaths() {
        Path archive = temporaryDirectory.resolve("Example.ZIP");

        assertEquals(temporaryDirectory.resolve("Example.z01"), ZipSplitVolumePaths.numberedVolumePath(archive, 0));
        assertEquals(temporaryDirectory.resolve("Example.z09"), ZipSplitVolumePaths.numberedVolumePath(archive, 8));
        assertEquals(temporaryDirectory.resolve("Example.z10"), ZipSplitVolumePaths.numberedVolumePath(archive, 9));
        assertEquals(temporaryDirectory.resolve("Example.z100"), ZipSplitVolumePaths.numberedVolumePath(archive, 99));
        assertEquals(Path.of("bundle.z01"), ZipSplitVolumePaths.numberedVolumePath(Path.of("bundle"), 0));
    }

    /// Verifies classic end metadata determines the complete immutable volume path sequence.
    @Test
    void discoversClassicSplitArchiveAndIgnoresFalseCommentSignature() throws IOException {
        Path archive = temporaryDirectory.resolve("classic.zip");
        Path firstVolume = temporaryDirectory.resolve("classic.z01");
        Files.write(firstVolume, new byte[0]);

        byte[] comment = new byte[28];
        ByteBuffer.wrap(comment).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeSections(
                archive,
                new byte[]{11, 12, 13, 14, 15},
                classicEndRecord(2, 1, comment)
        );

        List<Path> volumes = Objects.requireNonNull(ZipSplitVolumePaths.discover(archive));
        assertEquals(
                List.of(firstVolume, temporaryDirectory.resolve("classic.z02"), archive),
                volumes
        );
        assertThrows(UnsupportedOperationException.class, () -> volumes.add(archive));
    }

    /// Verifies missing first volumes, single-disk metadata, and incomplete ZIP64 metadata are not split archives.
    @Test
    void returnsNullWhenSplitDiscoveryIsNotSupportedByStorageMetadata() throws IOException {
        Path archive = temporaryDirectory.resolve("single.zip");
        assertNull(ZipSplitVolumePaths.discover(archive));

        Files.write(temporaryDirectory.resolve("single.z01"), new byte[0]);
        Files.write(archive, classicEndRecord(0, 0, new byte[0]));
        assertNull(ZipSplitVolumePaths.discover(archive));

        Files.write(archive, classicEndRecord(UINT16_MAX, UINT16_MAX, new byte[0]));
        assertNull(ZipSplitVolumePaths.discover(archive));
    }

    /// Verifies a ZIP64 locator can directly declare the number of split volumes.
    @Test
    void discoversZip64SplitFromLocatorDiskCount() throws IOException {
        Path archive = temporaryDirectory.resolve("locator.zip");
        Path firstVolume = temporaryDirectory.resolve("locator.z01");
        Files.write(firstVolume, new byte[0]);
        writeSections(
                archive,
                zip64EndRecord(2L, 2L),
                zip64Locator(2L, 0L, 3L),
                classicEndRecord(UINT16_MAX, UINT16_MAX, new byte[0])
        );

        assertEquals(
                List.of(firstVolume, temporaryDirectory.resolve("locator.z02"), archive),
                ZipSplitVolumePaths.discover(archive)
        );
    }

    /// Verifies ZIP64 end-record disk fields are used when the locator describes only one disk.
    @Test
    void discoversZip64SplitFromEndRecordDiskNumbers() throws IOException {
        Path archive = temporaryDirectory.resolve("record.zip");
        Path firstVolume = temporaryDirectory.resolve("record.z01");
        Files.write(firstVolume, new byte[0]);
        writeSections(
                archive,
                zip64EndRecord(3L, 2L),
                zip64Locator(0L, 0L, 1L),
                classicEndRecord(UINT16_MAX, UINT16_MAX, new byte[0])
        );

        assertEquals(
                List.of(
                        firstVolume,
                        temporaryDirectory.resolve("record.z02"),
                        temporaryDirectory.resolve("record.z03"),
                        archive
                ),
                ZipSplitVolumePaths.discover(archive)
        );
    }

    /// Verifies implausibly large ZIP64 volume counts fail before path allocation.
    @Test
    void rejectsZip64VolumeCountThatCannotBeRepresented() throws IOException {
        Path archive = temporaryDirectory.resolve("oversized.zip");
        Files.write(temporaryDirectory.resolve("oversized.z01"), new byte[0]);
        writeSections(
                archive,
                zip64Locator(0L, 0L, Integer.toUnsignedLong(Integer.MIN_VALUE) + 1L),
                classicEndRecord(UINT16_MAX, UINT16_MAX, new byte[0])
        );

        IOException failure = assertThrows(IOException.class, () -> ZipSplitVolumePaths.discover(archive));
        assertEquals("ZIP split archive has too many conventional volumes", failure.getMessage());
    }

    /// Creates a classic ZIP end of central directory record with the supplied disk metadata and comment.
    private static byte[] classicEndRecord(int finalDisk, int directoryDisk, byte[] comment) {
        ByteBuffer record = ByteBuffer.allocate(22 + comment.length).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        record.putShort((short) finalDisk);
        record.putShort((short) directoryDisk);
        record.putShort((short) 0);
        record.putShort((short) 0);
        record.putInt(0);
        record.putInt(0);
        record.putShort((short) comment.length);
        record.put(comment);
        return record.array();
    }

    /// Creates a minimum-size ZIP64 end of central directory record with the supplied disk metadata.
    private static byte[] zip64EndRecord(long finalDisk, long directoryDisk) {
        ByteBuffer record = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        record.putLong(44L);
        record.putShort((short) 45);
        record.putShort((short) 45);
        record.putInt((int) finalDisk);
        record.putInt((int) directoryDisk);
        return record.array();
    }

    /// Creates a ZIP64 end of central directory locator.
    private static byte[] zip64Locator(long endRecordDisk, long endRecordOffset, long totalDiskCount) {
        ByteBuffer locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        locator.putInt(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
        locator.putInt((int) endRecordDisk);
        locator.putLong(endRecordOffset);
        locator.putInt((int) totalDiskCount);
        return locator.array();
    }

    /// Concatenates the supplied byte sections and writes the resulting synthetic archive.
    private static void writeSections(Path path, byte[]... sections) throws IOException {
        int size = 0;
        for (byte[] section : sections) {
            size = Math.addExact(size, section.length);
        }
        ByteBuffer content = ByteBuffer.allocate(size);
        for (byte[] section : sections) {
            content.put(section);
        }
        Files.write(path, content.array());
    }
}
