// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Creates transactional path-backed output for conventional numbered 7z volumes.
///
/// @param firstVolumePath the final path of volume one
/// @param openOptions the requested archive open options
@NotNullByDefault
record SevenZipPathVolumeTarget(Path firstVolumePath, @Unmodifiable Set<OpenOption> openOptions)
        implements ArkivoVolumeTarget {
    /// Creates a path-backed 7z volume target.
    SevenZipPathVolumeTarget(Path firstVolumePath, Set<OpenOption> openOptions) {
        this.firstVolumePath = Objects.requireNonNull(firstVolumePath, "firstVolumePath")
                .toAbsolutePath()
                .normalize();
        this.openOptions = Set.copyOf(openOptions);
        SevenZipSplitVolumePaths.requireFirstVolumePath(this.firstVolumePath);
    }

    /// Opens a staged path-backed volume output through the shared transaction implementation.
    @Override
    public ArkivoVolumeOutput openOutput() throws IOException {
        return pathTarget().openOutput();
    }

    /// Validates path discovery and create-new constraints without allocating output state.
    void validateTargetOptions() throws IOException {
        pathTarget().validate();
    }

    /// Returns the shared path-backed transaction target for this 7z layout.
    private ArkivoPathVolumeTarget pathTarget() {
        return new ArkivoPathVolumeTarget(new PathLayout(firstVolumePath), openOptions);
    }

    /// Maps conventional numbered 7z volumes to final paths.
    ///
    /// @param firstVolumePath the normalized first-volume path
    @NotNullByDefault
    private record PathLayout(Path firstVolumePath) implements ArkivoVolumePathLayout {
        /// Creates one 7z path layout.
        private PathLayout {
            Objects.requireNonNull(firstVolumePath, "firstVolumePath");
        }

        /// Returns the common output directory for numbered volumes.
        @Override
        public Path outputDirectory() {
            Path parent = firstVolumePath.getParent();
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

        /// Returns all existing numbered paths owned by this archive layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
            String prefix = SevenZipSplitVolumePaths.volumeFileNamePrefix(firstVolumePath);
            int minimumSuffixWidth = SevenZipSplitVolumePaths.volumeSuffixWidth(firstVolumePath);
            ArrayList<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDirectory())) {
                for (Path path : stream) {
                    Path fileName = path.getFileName();
                    if (fileName != null
                            && isNumberedVolumeName(fileName.toString(), prefix, minimumSuffixWidth)) {
                        paths.add(path);
                    }
                }
            }
            paths.sort(Comparator.comparing(Path::toString));
            return List.copyOf(paths);
        }

        /// Returns whether a file name belongs to this numbered volume sequence.
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
                if (!Character.isDigit(character)) {
                    return false;
                }
                nonZero |= character != '0';
            }
            return nonZero;
        }
    }
}
