// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/// Maps conventional numbered 7z volumes to publication paths.
///
/// @param firstVolumePath the absolute first-volume path with its traversal components preserved
@NotNullByDefault
record SevenZipVolumePathLayout(Path firstVolumePath) implements ArkivoVolumePathLayout {
    /// Converts the first-volume path to an absolute path and validates its numbered suffix.
    SevenZipVolumePathLayout {
        firstVolumePath = Objects.requireNonNull(firstVolumePath, "firstVolumePath").toAbsolutePath();
        SevenZipSplitVolumePaths.requireFirstVolumePath(firstVolumePath);
    }

    /// Returns the common output directory for numbered volumes.
    @Override
    public Path outputDirectory() {
        @Nullable Path parent = firstVolumePath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("firstVolumePath must have a parent directory");
        }
        return parent;
    }

    /// Returns the conventional numbered path for one zero-based logical volume.
    @Override
    public Path volumePath(long index, long finalVolumeIndex) {
        if (index < 0L || index > finalVolumeIndex || index >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid 7z volume index: " + index);
        }
        return SevenZipSplitVolumePaths.numberedVolumePath(firstVolumePath, (int) index + 1);
    }

    /// Returns all existing ASCII-numbered paths owned by this archive layout.
    @Override
    public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
        String prefix = SevenZipSplitVolumePaths.volumeFileNamePrefix(firstVolumePath);
        int minimumSuffixWidth = SevenZipSplitVolumePaths.volumeSuffixWidth(firstVolumePath);
        ArrayList<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDirectory())) {
            for (Path path : stream) {
                @Nullable Path fileName = path.getFileName();
                if (fileName != null
                        && isNumberedVolumeName(fileName.toString(), prefix, minimumSuffixWidth)) {
                    paths.add(path);
                }
            }
        }
        paths.sort(Comparator.comparing(Path::toString));
        return List.copyOf(paths);
    }

    /// Returns whether a file name belongs to this ASCII-numbered volume sequence.
    private static boolean isNumberedVolumeName(
            String fileName,
            String prefix,
            int minimumSuffixWidth
    ) {
        if (!fileName.startsWith(prefix) || fileName.length() < prefix.length() + minimumSuffixWidth) {
            return false;
        }
        boolean nonZero = false;
        for (int index = prefix.length(); index < fileName.length(); index++) {
            char character = fileName.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            nonZero |= character != '0';
        }
        return nonZero;
    }
}
