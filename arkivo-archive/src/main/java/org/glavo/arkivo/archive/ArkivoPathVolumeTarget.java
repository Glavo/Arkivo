// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Publishes path-backed archive volumes through a staged transaction.
///
/// Existing output is moved into temporary backup paths before new volumes are published. Successful commit removes
/// stale previous volumes; failed publication removes newly published paths and restores every available backup.
@NotNullByDefault
public final class ArkivoPathVolumeTarget implements ArkivoVolumeTarget {
    /// The prefix used for temporary output directories.
    private static final String STAGING_DIRECTORY_PREFIX = ".arkivo-volumes-";

    /// The path layout used for publication and existing-output discovery.
    private final ArkivoVolumePathLayout layout;

    /// The requested output open options.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// Creates a replacing target that creates missing output paths.
    public ArkivoPathVolumeTarget(ArkivoVolumePathLayout layout) {
        this(layout, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    /// Creates a path-backed target with explicit publication and staged-file open options.
    public ArkivoPathVolumeTarget(
            ArkivoVolumePathLayout layout,
            Set<? extends OpenOption> openOptions
    ) {
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(openOptions, "openOptions");
        HashSet<OpenOption> copiedOptions = new HashSet<>();
        for (OpenOption option : openOptions) {
            copiedOptions.add(Objects.requireNonNull(option, "option"));
        }
        if (copiedOptions.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("Path volume targets do not support APPEND");
        }
        if (copiedOptions.contains(StandardOpenOption.DELETE_ON_CLOSE)) {
            throw new UnsupportedOperationException("Path volume targets do not support DELETE_ON_CLOSE");
        }
        this.openOptions = Set.copyOf(copiedOptions);
    }

    /// Opens one independently staged multi-volume output transaction.
    @Override
    public ArkivoVolumeOutput openOutput() throws IOException {
        Path outputDirectory = normalizedOutputDirectory();
        validate();
        Path stagingDirectory = Files.createTempDirectory(outputDirectory, STAGING_DIRECTORY_PREFIX);
        return new PathVolumeOutput(layout, stagingDirectory, stagingOpenOptions(), replaceExistingOutput());
    }

    /// Validates layout discovery and initial open-option constraints without creating staging state.
    ///
    /// Publication repeats conflict checks during commit because external paths may change after this method returns.
    public void validate() throws IOException {
        normalizedOutputDirectory();
        @Unmodifiable List<Path> existingPaths = existingOutputPaths(List.of());
        validateInitialState(existingPaths);
    }

    /// Returns the normalized directory used for temporary publication state.
    private Path normalizedOutputDirectory() {
        return Objects.requireNonNull(layout.outputDirectory(), "layout.outputDirectory()")
                .toAbsolutePath()
                .normalize();
    }

    /// Applies create and existence checks before allocating staging state.
    private void validateInitialState(@Unmodifiable List<Path> existingPaths) throws IOException {
        if (openOptions.contains(StandardOpenOption.CREATE_NEW) && !existingPaths.isEmpty()) {
            throw new FileAlreadyExistsException(existingPaths.get(0).toString());
        }
        if (!openOptions.contains(StandardOpenOption.CREATE)
                && !openOptions.contains(StandardOpenOption.CREATE_NEW)
                && existingPaths.isEmpty()) {
            Path firstPath = normalizedVolumePath(0L, 0L);
            throw new NoSuchFileException(firstPath.toString());
        }
    }

    /// Returns whether publication may replace previously published paths.
    private boolean replaceExistingOutput() {
        return !openOptions.contains(StandardOpenOption.CREATE_NEW);
    }

    /// Returns open options suitable for newly staged volume files.
    private @Unmodifiable Set<OpenOption> stagingOpenOptions() {
        HashSet<OpenOption> options = new HashSet<>(openOptions);
        options.remove(StandardOpenOption.READ);
        options.remove(StandardOpenOption.CREATE);
        options.remove(StandardOpenOption.CREATE_NEW);
        options.remove(StandardOpenOption.TRUNCATE_EXISTING);
        options.add(StandardOpenOption.CREATE_NEW);
        options.add(StandardOpenOption.WRITE);
        return Set.copyOf(options);
    }

    /// Returns normalized, distinct existing output paths and known current destinations.
    private @Unmodifiable List<Path> existingOutputPaths(@Unmodifiable List<Path> destinations)
            throws IOException {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        List<Path> reportedPaths = Objects.requireNonNull(
                layout.existingVolumePaths(),
                "layout.existingVolumePaths()"
        );
        for (Path path : reportedPaths) {
            paths.add(normalizedPath(path, "existing volume path"));
        }
        for (Path destination : destinations) {
            if (Files.exists(destination)) {
                paths.add(destination);
            }
        }
        return List.copyOf(paths);
    }

    /// Returns one normalized output path from the layout.
    private Path normalizedVolumePath(long index, long finalVolumeIndex) {
        return normalizedPath(
                layout.volumePath(index, finalVolumeIndex),
                "layout.volumePath(" + index + ", " + finalVolumeIndex + ")"
        );
    }

    /// Normalizes one required path.
    private static Path normalizedPath(@Nullable Path path, String description) {
        return Objects.requireNonNull(path, description).toAbsolutePath().normalize();
    }

    /// Stages, publishes, and restores path-backed archive volumes.
    @NotNullByDefault
    private static final class PathVolumeOutput implements ArkivoVolumeOutput {
        /// The path layout used for publication and discovery.
        private final ArkivoVolumePathLayout layout;

        /// The directory holding unpublished volumes and existing-output backups.
        private final Path stagingDirectory;

        /// Open options used for every staged volume file.
        private final @Unmodifiable Set<OpenOption> stagingOpenOptions;

        /// Whether existing output may be replaced.
        private final boolean replaceExistingOutput;

        /// Existing output paths moved into staging for rollback.
        private final ArrayList<OutputBackup> backups = new ArrayList<>();

        /// Newly published paths that must be removed on rollback.
        private final ArrayList<Path> publishedPaths = new ArrayList<>();

        /// The number of staged volume channels opened so far.
        private int openedVolumeCount;

        /// Whether publication has started.
        private boolean publicationStarted;

        /// Whether publication completed successfully.
        private boolean committed;

        /// Whether this output has committed or rolled back.
        private boolean finished;

        /// Whether all staging state has been removed or restored.
        private boolean cleanupComplete;

        /// Creates one staged path output transaction.
        private PathVolumeOutput(
                ArkivoVolumePathLayout layout,
                Path stagingDirectory,
                Set<OpenOption> stagingOpenOptions,
                boolean replaceExistingOutput
        ) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
            this.stagingOpenOptions = Set.copyOf(stagingOpenOptions);
            this.replaceExistingOutput = replaceExistingOutput;
        }

        /// Opens the next staged physical volume.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            ensureOpen();
            if (index < 0L || index != openedVolumeCount) {
                throw new IllegalArgumentException("Volume indexes must be opened once in ascending order");
            }
            SeekableByteChannel channel = Files.newByteChannel(
                    stagedVolumePath(stagingDirectory, openedVolumeCount),
                    stagingOpenOptions
            );
            openedVolumeCount++;
            return channel;
        }

        /// Publishes every staged volume to paths selected by the layout.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            ensureOpen();
            if (finalVolumeIndex < 0L || finalVolumeIndex != openedVolumeCount - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex must identify the last opened volume");
            }
            @Unmodifiable List<Path> outputPaths = outputPaths(finalVolumeIndex);
            publicationStarted = true;
            try {
                if (replaceExistingOutput) {
                    backupExistingOutput(outputPaths);
                } else {
                    requireNoExistingOutput(outputPaths);
                }
                for (int index = 0; index < outputPaths.size(); index++) {
                    Path outputPath = outputPaths.get(index);
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

        /// Removes unpublished output or retries cleanup after a failed publication.
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

        /// Closes this output and rolls it back when unfinished.
        @Override
        public void close() throws IOException {
            rollback();
        }

        /// Resolves and validates every final output path before publication mutates existing files.
        private @Unmodifiable List<Path> outputPaths(long finalVolumeIndex) {
            ArrayList<Path> paths = new ArrayList<>(openedVolumeCount);
            HashSet<Path> distinctPaths = new HashSet<>();
            for (int index = 0; index < openedVolumeCount; index++) {
                Path path = normalizedPath(
                        layout.volumePath(index, finalVolumeIndex),
                        "layout.volumePath(" + index + ", " + finalVolumeIndex + ")"
                );
                if (!distinctPaths.add(path)) {
                    throw new IllegalArgumentException("Volume layout returned duplicate output path: " + path);
                }
                paths.add(path);
            }
            return List.copyOf(paths);
        }

        /// Moves every existing output path into staging for possible rollback.
        private void backupExistingOutput(@Unmodifiable List<Path> outputPaths) throws IOException {
            int backupIndex = 0;
            for (Path outputPath : existingOutputPaths(outputPaths)) {
                Path backupPath = stagingDirectory.resolve("backup-" + backupIndex++);
                Files.move(outputPath, backupPath);
                backups.add(new OutputBackup(outputPath, backupPath));
            }
        }

        /// Rejects publication if any owned or directly targeted output already exists.
        private void requireNoExistingOutput(@Unmodifiable List<Path> outputPaths) throws IOException {
            @Unmodifiable List<Path> existingPaths = existingOutputPaths(outputPaths);
            if (!existingPaths.isEmpty()) {
                throw new FileAlreadyExistsException(existingPaths.get(0).toString());
            }
        }

        /// Returns normalized existing paths reported by the layout plus directly targeted paths.
        private @Unmodifiable List<Path> existingOutputPaths(@Unmodifiable List<Path> outputPaths)
                throws IOException {
            LinkedHashSet<Path> paths = new LinkedHashSet<>();
            List<Path> reportedPaths = Objects.requireNonNull(
                    layout.existingVolumePaths(),
                    "layout.existingVolumePaths()"
            );
            for (Path path : reportedPaths) {
                Path normalized = normalizedPath(path, "existing volume path");
                if (Files.exists(normalized)) {
                    paths.add(normalized);
                }
            }
            for (Path outputPath : outputPaths) {
                if (Files.exists(outputPath)) {
                    paths.add(outputPath);
                }
            }
            return List.copyOf(paths);
        }

        /// Removes new output and restores all existing paths after failed publication.
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
            // Keep unrecovered backups in staging so a later rollback or close can retry restoration.
            if (!cleanupFailed) {
                try {
                    deleteStagingDirectory(stagingDirectory);
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                    cleanupFailed = true;
                }
            }
            if (!cleanupFailed) {
                cleanupComplete = true;
            }
            return failure;
        }

        /// Requires this output to remain unfinished.
        private void ensureOpen() throws IOException {
            if (finished) {
                throw new IOException("Volume output is closed");
            }
        }

        /// Returns one staged volume path.
        private static Path stagedVolumePath(Path stagingDirectory, int volumeIndex) {
            return stagingDirectory.resolve("volume-" + volumeIndex);
        }

        /// Deletes every remaining staged file and the staging directory.
        private static void deleteStagingDirectory(Path stagingDirectory) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
                for (Path path : stream) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(stagingDirectory);
        }

        /// Adds a cleanup failure as suppressed when a primary failure already exists.
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

        /// Stores one existing path and its staged backup path.
        ///
        /// @param outputPath the original published path
        /// @param backupPath the staged backup path
        @NotNullByDefault
        private record OutputBackup(Path outputPath, Path backupPath) {
        }
    }
}
