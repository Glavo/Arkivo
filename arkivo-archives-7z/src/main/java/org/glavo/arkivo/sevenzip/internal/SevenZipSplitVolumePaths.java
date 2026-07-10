// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

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

/// Resolves conventional numbered 7z split volume paths.
@NotNullByDefault
final class SevenZipSplitVolumePaths {
    /// The fixed 7z file signature bytes.
    private static final byte @Unmodifiable [] SIGNATURE = new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

    /// The first conventional 7z split volume number.
    private static final int FIRST_VOLUME_NUMBER = 1;

    /// The second conventional 7z split volume number.
    private static final int SECOND_VOLUME_NUMBER = 2;

    /// The minimum width of a conventional 7z split volume suffix.
    private static final int MIN_VOLUME_SUFFIX_WIDTH = 3;

    /// The sentinel returned when a file name does not contain a conventional split volume number.
    private static final int NO_VOLUME_NUMBER = -1;

    /// Prevents instantiation.
    private SevenZipSplitVolumePaths() {
    }

    /// Requires a path to identify conventional first volume number one.
    static void requireFirstVolumePath(Path firstVolumePath) {
        Path fileNamePath = firstVolumePath.getFileName();
        if (fileNamePath == null || splitVolumeNumber(fileNamePath.toString()) != FIRST_VOLUME_NUMBER) {
            throw new IllegalArgumentException("7z split output path must end in a numbered first-volume suffix such as .001");
        }
    }

    /// Returns conventional split volume paths for a first-volume path, or `null` for a single-volume path.
    static @Nullable @Unmodifiable List<Path> discover(Path firstVolumePath) throws IOException {
        Path fileNamePath = firstVolumePath.getFileName();
        if (fileNamePath == null || splitVolumeNumber(fileNamePath.toString()) != FIRST_VOLUME_NUMBER) {
            return null;
        }

        Path secondVolumePath = numberedVolumePath(firstVolumePath, SECOND_VOLUME_NUMBER);
        if (!Files.exists(firstVolumePath) || !Files.exists(secondVolumePath)
                || firstVolumeContainsCompleteArchive(firstVolumePath)) {
            return null;
        }

        ArrayList<Path> paths = new ArrayList<>();
        for (int volumeNumber = FIRST_VOLUME_NUMBER; ; volumeNumber++) {
            Path volumePath = numberedVolumePath(firstVolumePath, volumeNumber);
            if (!Files.exists(volumePath)) {
                break;
            }
            paths.add(volumePath);
            if (volumeNumber == Integer.MAX_VALUE) {
                throw new IOException("7z split archive has too many conventional volumes");
            }
        }
        return List.copyOf(paths);
    }

    /// Returns whether the first volume already contains the complete 7z archive payload.
    private static boolean firstVolumeContainsCompleteArchive(Path firstVolumePath) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(firstVolumePath, StandardOpenOption.READ)) {
            long channelSize = channel.size();
            if (channelSize < SevenZipSignatureHeader.SIZE) {
                return false;
            }

            ByteBuffer buffer = ByteBuffer.allocate(SevenZipSignatureHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, buffer);
            buffer.flip();

            for (byte expected : SIGNATURE) {
                if (buffer.get() != expected) {
                    return false;
                }
            }

            buffer.position(12);
            long nextHeaderOffset = buffer.getLong();
            long nextHeaderSize = buffer.getLong();
            if (nextHeaderOffset < 0 || nextHeaderSize < 0) {
                return false;
            }

            try {
                long nextHeaderStart = Math.addExact(SevenZipSignatureHeader.SIZE, nextHeaderOffset);
                long archiveSize = Math.addExact(nextHeaderStart, nextHeaderSize);
                return channelSize >= archiveSize;
            } catch (ArithmeticException exception) {
                return false;
            }
        }
    }

    /// Returns the conventional path for a numbered split volume.
    static Path numberedVolumePath(Path firstVolumePath, int volumeNumber) {
        if (volumeNumber <= 0) {
            throw new IllegalArgumentException("volumeNumber must be positive");
        }
        String fileName = firstVolumePath.getFileName().toString();
        int suffixStart = fileName.lastIndexOf('.') + 1;
        int suffixWidth = fileName.length() - suffixStart;
        String volumeFileName = fileName.substring(0, suffixStart) + paddedVolumeNumber(volumeNumber, suffixWidth);
        Path volumePath = Path.of(volumeFileName);
        Path parent = firstVolumePath.getParent();
        return parent != null ? parent.resolve(volumePath) : volumePath;
    }

    /// Returns the first-volume file name prefix before its numeric suffix.
    static String volumeFileNamePrefix(Path firstVolumePath) {
        requireFirstVolumePath(firstVolumePath);
        String fileName = firstVolumePath.getFileName().toString();
        return fileName.substring(0, fileName.lastIndexOf('.') + 1);
    }

    /// Returns the minimum numeric suffix width established by the first-volume path.
    static int volumeSuffixWidth(Path firstVolumePath) {
        requireFirstVolumePath(firstVolumePath);
        String fileName = firstVolumePath.getFileName().toString();
        return fileName.length() - fileName.lastIndexOf('.') - 1;
    }

    /// Returns a positive volume number padded to the requested minimum width.
    private static String paddedVolumeNumber(int volumeNumber, int minimumWidth) {
        String text = Integer.toString(volumeNumber);
        if (text.length() >= minimumWidth) {
            return text;
        }

        StringBuilder builder = new StringBuilder(minimumWidth);
        for (int index = text.length(); index < minimumWidth; index++) {
            builder.append('0');
        }
        return builder.append(text).toString();
    }

    /// Returns the numeric suffix value when the file name is a conventional 7z split volume path.
    private static int splitVolumeNumber(String fileName) {
        int suffixStart = fileName.lastIndexOf('.') + 1;
        int suffixWidth = fileName.length() - suffixStart;
        if (suffixStart <= 0 || suffixWidth < MIN_VOLUME_SUFFIX_WIDTH) {
            return NO_VOLUME_NUMBER;
        }

        int number = 0;
        for (int index = suffixStart; index < fileName.length(); index++) {
            int digit = Character.digit(fileName.charAt(index), 10);
            if (digit < 0) {
                return NO_VOLUME_NUMBER;
            }
            if (number > (Integer.MAX_VALUE - digit) / 10) {
                return NO_VOLUME_NUMBER;
            }
            number = number * 10 + digit;
        }
        return number;
    }

    /// Reads bytes from a channel until the destination buffer is full.
    private static void readFully(SeekableByteChannel channel, ByteBuffer destination) throws IOException {
        while (destination.hasRemaining()) {
            if (channel.read(destination) < 0) {
                throw new IOException("Unexpected end of 7z signature header");
            }
        }
    }
}
