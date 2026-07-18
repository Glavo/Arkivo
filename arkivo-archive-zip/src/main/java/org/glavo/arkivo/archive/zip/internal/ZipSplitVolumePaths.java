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
public final class ZipSplitVolumePaths {
    /// The sentinel returned when end metadata describes a single-volume archive.
    private static final long NOT_SPLIT = -1L;

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
    ///
    /// This method reads and closes the final archive path while inspecting its end records and may block. The returned
    /// list is ordered from `.z01` through the final archive path and does not transfer ownership of any open resource.
    ///
    /// @param archivePath the final archive path, conventionally ending in `.zip`
    /// @return the immutable ordered volume paths, or `null` when `.z01` is absent or end metadata is single-volume
    /// @throws NullPointerException if `archivePath` is `null`
    /// @throws IOException if end metadata cannot be read or describes too many conventional volumes
    public static @Nullable @Unmodifiable List<Path> discover(Path archivePath) throws IOException {
        Path firstVolumePath = numberedVolumePath(archivePath, 0);
        if (!Files.exists(firstVolumePath)) {
            return null;
        }

        long finalDiskNumber = finalDiskNumber(archivePath);
        if (finalDiskNumber == NOT_SPLIT) {
            return null;
        }
        if (finalDiskNumber >= Integer.MAX_VALUE) {
            throw new IOException("ZIP split archive has too many conventional volumes");
        }

        ArrayList<Path> paths = new ArrayList<>((int) finalDiskNumber + 1);
        for (int diskNumber = 0; diskNumber < finalDiskNumber; diskNumber++) {
            paths.add(numberedVolumePath(archivePath, diskNumber));
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

    /// Returns the zero-based final disk number declared by ZIP end metadata, or `NOT_SPLIT`.
    private static long finalDiskNumber(Path archivePath) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(archivePath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE) {
                return NOT_SPLIT;
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
                    return zip64FinalDiskNumber(channel, searchOffset + index);
                }
                return endDiskNumber != 0 || centralDirectoryDiskNumber != 0
                        ? Math.max(endDiskNumber, centralDirectoryDiskNumber)
                        : NOT_SPLIT;
            }

            return NOT_SPLIT;
        }
    }

    /// Returns the zero-based final disk number declared by ZIP64 end metadata, or `NOT_SPLIT`.
    private static long zip64FinalDiskNumber(SeekableByteChannel channel, long endRecordOffset)
            throws IOException {
        long locatorOffset = endRecordOffset - ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE;
        if (locatorOffset < 0) {
            return NOT_SPLIT;
        }

        ByteBuffer locator = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, locatorOffset, locator);
        locator.flip();
        if (locator.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
            return NOT_SPLIT;
        }

        long zip64EndDiskNumber = Integer.toUnsignedLong(locator.getInt(4));
        long zip64EndOffset = locator.getLong(8);
        long totalDiskCount = Integer.toUnsignedLong(locator.getInt(16));
        if (totalDiskCount > 1) {
            return totalDiskCount - 1L;
        }
        if (zip64EndDiskNumber != 0) {
            return zip64EndDiskNumber;
        }
        if (zip64EndOffset < 0 || zip64EndOffset + ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE > channel.size()) {
            return NOT_SPLIT;
        }

        ByteBuffer record = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, zip64EndOffset, record);
        record.flip();
        if (record.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            return NOT_SPLIT;
        }

        long recordDiskNumber = Integer.toUnsignedLong(record.getInt(16));
        long centralDirectoryDiskNumber = Integer.toUnsignedLong(record.getInt(20));
        return recordDiskNumber != 0 || centralDirectoryDiskNumber != 0
                ? Math.max(recordDiskNumber, centralDirectoryDiskNumber)
                : NOT_SPLIT;
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
