// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoVolumeOutput;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        this.firstVolumePath = Objects.requireNonNull(firstVolumePath, "firstVolumePath").toAbsolutePath().normalize();
        this.openOptions = Set.copyOf(openOptions);
        SevenZipSplitVolumePaths.requireFirstVolumePath(this.firstVolumePath);
    }

    /// Opens a new staged path-backed volume output.
    @Override
    public ArkivoVolumeOutput openOutput() throws IOException {
        validateTargetOptions();
        Path stagingDirectory = Files.createTempDirectory(
                PathVolumeOutput.outputDirectory(firstVolumePath),
                PathVolumeOutput.STAGING_DIRECTORY_PREFIX
        );
        return new PathVolumeOutput(
                firstVolumePath,
                stagingDirectory,
                !openOptions.contains(StandardOpenOption.CREATE_NEW)
        );
    }

    /// Applies first-volume open option checks before staging output.
    void validateTargetOptions() throws IOException {
        if (openOptions.contains(StandardOpenOption.CREATE_NEW)) {
            List<Path> existingPaths = PathVolumeOutput.existingOutputPaths(firstVolumePath);
            if (!existingPaths.isEmpty()) {
                throw new FileAlreadyExistsException(existingPaths.get(0).toString());
            }
        }
        if (!openOptions.contains(StandardOpenOption.CREATE)
                && !openOptions.contains(StandardOpenOption.CREATE_NEW)
                && !Files.exists(firstVolumePath)) {
            throw new NoSuchFileException(firstVolumePath.toString());
        }
    }

    /// Stages, publishes, and restores conventional path-backed 7z split volumes.
    @NotNullByDefault
    private static final class PathVolumeOutput implements ArkivoVolumeOutput {
        /// The prefix used for temporary split output directories.
        private static final String STAGING_DIRECTORY_PREFIX = ".arkivo-7z-split-";

        /// Open options used for every new staged volume.
        private static final @Unmodifiable Set<OpenOption> STAGING_OPEN_OPTIONS =
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        /// The final first-volume path.
        private final Path firstVolumePath;

        /// The directory that holds unpublished split output and backups.
        private final Path stagingDirectory;

        /// Whether existing output volumes may be replaced during publication.
        private final boolean replaceExistingOutput;

        /// Existing output paths moved into staging for rollback.
        private final ArrayList<OutputBackup> backups = new ArrayList<>();

        /// Staged output paths already moved into published locations.
        private final ArrayList<Path> publishedPaths = new ArrayList<>();

        /// The number of staged volume channels opened so far.
        private int openedVolumeCount;

        /// Whether publication has started.
        private boolean publicationStarted;

        /// Whether the new output has been published successfully.
        private boolean committed;

        /// Whether this output has been committed or rolled back.
        private boolean finished;

        /// Whether all staging and backup paths have been removed or restored.
        private boolean cleanupComplete;

        /// Creates one staged path volume output.
        private PathVolumeOutput(
                Path firstVolumePath,
                Path stagingDirectory,
                boolean replaceExistingOutput
        ) {
            this.firstVolumePath = Objects.requireNonNull(firstVolumePath, "firstVolumePath");
            this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
            this.replaceExistingOutput = replaceExistingOutput;
        }

        /// Opens the next staged volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            ensureOpen();
            if (index < 0 || index != openedVolumeCount) {
                throw new IllegalArgumentException("Volume indexes must be opened once in ascending order");
            }
            SeekableByteChannel channel = Files.newByteChannel(
                    stagedVolumePath(stagingDirectory, openedVolumeCount),
                    STAGING_OPEN_OPTIONS
            );
            openedVolumeCount++;
            return channel;
        }

        /// Publishes all staged volumes using conventional numbered 7z paths.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            ensureOpen();
            if (finalVolumeIndex < 0
                    || finalVolumeIndex != openedVolumeCount - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex must identify the last opened volume");
            }
            publicationStarted = true;
            try {
                if (replaceExistingOutput) {
                    backupExistingOutput();
                } else {
                    requireNoExistingOutput();
                }
                for (int index = 0; index <= (int) finalVolumeIndex; index++) {
                    Path outputPath = SevenZipSplitVolumePaths.numberedVolumePath(firstVolumePath, index + 1);
                    Files.move(stagedVolumePath(stagingDirectory, index), outputPath);
                    publishedPaths.add(outputPath);
                }
            } catch (IOException | RuntimeException | Error exception) {
                finished = true;
                @Nullable Throwable failure = rollbackPublication(exception);
                throwFailure(failure);
                return;
            }
            committed = true;
            finished = true;
            deleteStagingDirectory(stagingDirectory);
            cleanupComplete = true;
        }

        /// Removes unpublished output or retries cleanup after a publication attempt.
        @Override
        public void rollback() throws IOException {
            if (cleanupComplete) {
                return;
            }
            finished = true;
            if (publicationStarted && !committed) {
                @Nullable Throwable failure = rollbackPublication(null);
                throwFailure(failure);
                return;
            }
            deleteStagingDirectory(stagingDirectory);
            cleanupComplete = true;
        }

        /// Closes this output and rolls it back when necessary.
        @Override
        public void close() throws IOException {
            rollback();
        }

        /// Moves every existing output volume into staging for a possible rollback.
        private void backupExistingOutput() throws IOException {
            int backupIndex = 0;
            for (Path outputPath : existingOutputPaths()) {
                Path backupPath = stagingDirectory.resolve("backup-" + backupIndex++);
                Files.move(outputPath, backupPath);
                backups.add(new OutputBackup(outputPath, backupPath));
            }
        }

        /// Rejects publication when create-new output paths already exist.
        private void requireNoExistingOutput() throws IOException {
            List<Path> existingPaths = existingOutputPaths();
            if (!existingPaths.isEmpty()) {
                throw new FileAlreadyExistsException(existingPaths.get(0).toString());
            }
        }

        /// Returns every existing numbered output path for this archive name.
        private @Unmodifiable List<Path> existingOutputPaths() throws IOException {
            return existingOutputPaths(firstVolumePath);
        }

        /// Returns every existing numbered output path for the requested first-volume path.
        private static @Unmodifiable List<Path> existingOutputPaths(Path firstVolumePath) throws IOException {
            Path outputDirectory = outputDirectory(firstVolumePath);
            String prefix = SevenZipSplitVolumePaths.volumeFileNamePrefix(firstVolumePath);
            int minimumSuffixWidth = SevenZipSplitVolumePaths.volumeSuffixWidth(firstVolumePath);
            ArrayList<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDirectory)) {
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

        /// Removes published output and restores every backed-up output path after failure.
        private @Nullable Throwable rollbackPublication(@Nullable Throwable failure) {
            boolean cleanupFailed = false;
            for (int index = publishedPaths.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(publishedPaths.get(index));
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                    cleanupFailed = true;
                }
            }
            for (int index = backups.size() - 1; index >= 0; index--) {
                OutputBackup backup = backups.get(index);
                if (!Files.exists(backup.backupPath())) {
                    continue;
                }
                try {
                    Files.move(backup.backupPath(), backup.outputPath());
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                    cleanupFailed = true;
                }
            }
            try {
                deleteStagingDirectory(stagingDirectory);
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
                cleanupFailed = true;
            }
            if (!cleanupFailed) {
                cleanupComplete = true;
            }
            return failure;
        }

        /// Requires this volume output to remain unfinished.
        private void ensureOpen() throws IOException {
            if (finished) {
                throw new IOException("Volume output is closed");
            }
        }

        /// Returns whether a file name is a numbered volume for the requested prefix.
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

        /// Returns the directory in which split output is published.
        private static Path outputDirectory(Path firstVolumePath) {
            Path parent = firstVolumePath.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IllegalArgumentException("firstVolumePath must have a parent directory");
            }
            return parent;
        }

        /// Returns the path used to stage one output volume.
        private static Path stagedVolumePath(Path stagingDirectory, int volumeIndex) {
            return stagingDirectory.resolve("volume-" + volumeIndex);
        }

        /// Removes all staged output and backup paths.
        private static void deleteStagingDirectory(Path stagingDirectory) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
                for (Path path : stream) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(stagingDirectory);
        }

        /// Adds a secondary failure as suppressed when a primary failure already exists.
        private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
            if (failure == null) {
                return exception;
            }
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
            return failure;
        }

        /// Throws an accumulated failure with its original category.
        private static void throwFailure(@Nullable Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error exception) {
                throw exception;
            }
        }

        /// Stores a staged backup of one previously published output path.
        ///
        /// @param outputPath the original published path
        /// @param backupPath the staged backup path
        @NotNullByDefault
        private record OutputBackup(Path outputPath, Path backupPath) {
        }
    }
}
