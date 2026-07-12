// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT16_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE;

/// Resolves conventional ZIP split volume paths.
@NotNullByDefault
final class ZipSplitVolumePaths {
    /// The minimum ZIP end of central directory record size.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22;

    /// The maximum ZIP comment size stored in the end of central directory record.
    private static final int ZIP_COMMENT_MAX_SIZE = 0xffff;

    /// The largest byte range that must be scanned to find the end of central directory record.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH =
            ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE + ZIP_COMMENT_MAX_SIZE;

    /// The ZIP64 end of central directory locator size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE = 20;

    /// The minimum ZIP64 end of central directory record size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 56;

    /// Prevents instantiation.
    private ZipSplitVolumePaths() {
    }

    /// Returns conventional split volume paths for the given final archive path, or `null` for a single-volume path.
    static @Nullable @Unmodifiable List<Path> discover(Path archivePath) throws IOException {
        Path firstVolumePath = numberedVolumePath(archivePath, 0);
        if (!Files.exists(firstVolumePath) || !endRecordUsesSplitVolumes(archivePath)) {
            return null;
        }

        ArrayList<Path> paths = new ArrayList<>();
        for (int diskNumber = 0; ; diskNumber++) {
            Path volumePath = numberedVolumePath(archivePath, diskNumber);
            if (!Files.exists(volumePath)) {
                break;
            }
            paths.add(volumePath);
        }
        paths.add(archivePath);
        return List.copyOf(paths);
    }

    /// Returns the conventional path for a numbered split volume.
    static Path numberedVolumePath(Path archivePath, int diskNumber) {
        String volumeNumber = Integer.toString(diskNumber + 1);
        if (volumeNumber.length() == 1) {
            volumeNumber = "0" + volumeNumber;
        }
        String fileName = archivePath.getFileName().toString();
        String baseName = fileName.length() >= 4
                && fileName.regionMatches(true, fileName.length() - 4, ".zip", 0, 4)
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        Path parent = archivePath.getParent();
        Path volumeFileName = Path.of(baseName + ".z" + volumeNumber);
        return parent != null ? parent.resolve(volumeFileName) : volumeFileName;
    }

    /// Returns whether the final archive path declares split ZIP end metadata.
    private static boolean endRecordUsesSplitVolumes(Path archivePath) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(archivePath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE) {
                return false;
            }

            int searchSize = (int) Math.min(size, ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH);
            long searchOffset = size - searchSize;
            ByteBuffer buffer = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, searchOffset, buffer);
            buffer.flip();

            for (int index = searchSize - ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE; index >= 0; index--) {
                if (buffer.getInt(index) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                    continue;
                }
                int commentLength = Short.toUnsignedInt(buffer.getShort(index + 20));
                if (index + ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE + commentLength != searchSize) {
                    continue;
                }

                int endDiskNumber = Short.toUnsignedInt(buffer.getShort(index + 4));
                int centralDirectoryDiskNumber = Short.toUnsignedInt(buffer.getShort(index + 6));
                if (endDiskNumber == UINT16_MAX || centralDirectoryDiskNumber == UINT16_MAX) {
                    return zip64EndRecordUsesSplitVolumes(channel, searchOffset + index);
                }
                return endDiskNumber != 0 || centralDirectoryDiskNumber != 0;
            }

            return false;
        }
    }

    /// Returns whether ZIP64 end metadata declares split volumes.
    private static boolean zip64EndRecordUsesSplitVolumes(SeekableByteChannel channel, long endRecordOffset)
            throws IOException {
        long locatorOffset = endRecordOffset - ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE;
        if (locatorOffset < 0) {
            return false;
        }

        ByteBuffer locator = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, locatorOffset, locator);
        locator.flip();
        if (locator.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
            return false;
        }

        long zip64EndDiskNumber = Integer.toUnsignedLong(locator.getInt(4));
        long zip64EndOffset = locator.getLong(8);
        long totalDiskCount = Integer.toUnsignedLong(locator.getInt(16));
        if (zip64EndDiskNumber != 0 || totalDiskCount > 1) {
            return true;
        }
        if (zip64EndOffset < 0 || zip64EndOffset + ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE > channel.size()) {
            return false;
        }

        ByteBuffer record = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, zip64EndOffset, record);
        record.flip();
        if (record.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            return false;
        }

        long recordDiskNumber = Integer.toUnsignedLong(record.getInt(16));
        long centralDirectoryDiskNumber = Integer.toUnsignedLong(record.getInt(20));
        return recordDiskNumber != 0 || centralDirectoryDiskNumber != 0;
    }

    /// Reads bytes from a channel until the destination buffer is full.
    private static void readFully(SeekableByteChannel channel, long position, ByteBuffer destination)
            throws IOException {
        channel.position(position);
        while (destination.hasRemaining()) {
            if (channel.read(destination) < 0) {
                throw new IOException("Unexpected end of ZIP storage");
            }
        }
    }
}
