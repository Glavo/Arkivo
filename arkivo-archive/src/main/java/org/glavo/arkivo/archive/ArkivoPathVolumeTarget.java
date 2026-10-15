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
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
/// stale previous volumes. Moves are performed one path at a time; publication is not atomic across volumes.
/// Symbolic links are moved and replaced as links rather than followed to their targets.
/// Existing directories are rejected as volume destinations and as stale volumes.
/// Parent directories are resolved before publication paths are compared or moved; a symbolic parent followed by
/// `..` is therefore traversed before that parent component is removed. Destination parent directories must exist.
///
/// If publication fails, rollback attempts to remove new output and restore the backups. Cleanup failures are suppressed
/// on the publication failure, and [ArkivoVolumeOutput#rollback()] or [ArkivoVolumeOutput#close()] retries unfinished
/// cleanup. If publication succeeds but deleting staging files fails, commit reports the cleanup failure; the new
/// volumes remain published, and subsequent cleanup does not restore the old archive.
@NotNullByDefault
public final class ArkivoPathVolumeTarget implements ArkivoVolumeTarget {
    /// The prefix used for temporary output directories.
    private static final String STAGING_DIRECTORY_PREFIX = ".arkivo-volumes-";

    /// The path layout used for publication and existing-output discovery.
    private final ArkivoVolumePathLayout layout;

    /// The requested output open options.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// Creates a replacing target that creates missing output paths.
    ///
    /// @param layout the path layout used to publish and discover archive volumes
    public ArkivoPathVolumeTarget(ArkivoVolumePathLayout layout) {
        this(layout, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    /// Creates a path-backed target with explicit publication and staged-file open options.
    ///
    /// @param layout      the path layout used to publish and discover archive volumes
    /// @param openOptions the options applied to staged volume files and publication conflict handling
    /// @throws UnsupportedOperationException if {@code openOptions} contains {@link StandardOpenOption#APPEND} or
    /// {@link StandardOpenOption#DELETE_ON_CLOSE}
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
        Path outputDirectory = resolvedOutputDirectory();
        validate();
        Path stagingDirectory = Files.createTempDirectory(outputDirectory, STAGING_DIRECTORY_PREFIX);
        return new PathVolumeOutput(layout, stagingDirectory, stagingOpenOptions(), replaceExistingOutput());
    }

    /// Validates layout discovery and initial open-option constraints without creating staging state.
    ///
    /// Publication repeats conflict checks during commit because external paths may change after this method returns.
    ///
    /// @throws IOException if the output directory or existing archive layout cannot be inspected,
    ///                     or a discovered volume path denotes a directory
    public void validate() throws IOException {
        resolvedOutputDirectory();
        @Unmodifiable List<Path> existingPaths = existingOutputPaths(List.of());
        validateInitialState(existingPaths);
        requireNonDirectoryPaths(existingPaths);
    }

    /// Resolves the staging directory without removing components before following symbolic links.
    private Path resolvedOutputDirectory() throws IOException {
        return Objects.requireNonNull(layout.outputDirectory(), "layout.outputDirectory()")
                .toRealPath();
    }

    /// Applies create and existence checks before allocating staging state.
    private void validateInitialState(@Unmodifiable List<Path> existingPaths) throws IOException {
        if (openOptions.contains(StandardOpenOption.CREATE_NEW) && !existingPaths.isEmpty()) {
            throw new FileAlreadyExistsException(existingPaths.get(0).toString());
        }
        if (!openOptions.contains(StandardOpenOption.CREATE)
                && !openOptions.contains(StandardOpenOption.CREATE_NEW)
                && existingPaths.isEmpty()) {
            Path firstPath = resolvedVolumePath(0L, 0L);
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

    /// Returns distinct existing paths after resolving their parents but not their terminal links.
    private @Unmodifiable List<Path> existingOutputPaths(@Unmodifiable List<Path> destinations)
            throws IOException {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        List<Path> reportedPaths = Objects.requireNonNull(
                layout.existingVolumePaths(),
                "layout.existingVolumePaths()"
        );
        for (Path path : reportedPaths) {
            Objects.requireNonNull(path, "existing volume path");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                paths.add(resolveVolumePath(path, "existing volume path"));
            }
        }
        for (Path destination : destinations) {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                paths.add(destination);
            }
        }
        return List.copyOf(paths);
    }

    /// Resolves the parent of one output path from the layout.
    private Path resolvedVolumePath(long index, long finalVolumeIndex) throws IOException {
        return resolveVolumePath(
                layout.volumePath(index, finalVolumeIndex),
                "layout.volumePath(" + index + ", " + finalVolumeIndex + ")"
        );
    }

    /// Resolves existing parent directories while preserving the terminal directory entry for replacement.
    private static Path resolveVolumePath(@Nullable Path path, String description) throws IOException {
        Path absolute = Objects.requireNonNull(path, description).toAbsolutePath();
        @Nullable Path parent = absolute.getParent();
        return parent == null ? absolute.toRealPath() : parent.toRealPath().resolve(absolute.getFileName()).normalize();
    }

    /// Rejects directories before any existing output is moved into staging.
    private static void requireNonDirectoryPaths(@Unmodifiable List<Path> paths) throws IOException {
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                throw new FileSystemException(path.toString(), null, "Archive volume path is a directory");
            }
        }
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

        /// Existing output paths whose staged backups still require restoration.
        private final ArrayList<OutputBackup> backups = new ArrayList<>();

        /// Newly published paths that still require removal on rollback.
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
        private @Unmodifiable List<Path> outputPaths(long finalVolumeIndex) throws IOException {
            ArrayList<Path> paths = new ArrayList<>(openedVolumeCount);
            HashSet<Path> distinctPaths = new HashSet<>();
            for (int index = 0; index < openedVolumeCount; index++) {
                Path path = resolveVolumePath(
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
            @Unmodifiable List<Path> existingPaths = existingOutputPaths(outputPaths);
            requireNonDirectoryPaths(existingPaths);
            int backupIndex = 0;
            for (Path outputPath : existingPaths) {
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

        /// Resolves existing reported paths and adds directly targeted paths without following terminal links.
        private @Unmodifiable List<Path> existingOutputPaths(@Unmodifiable List<Path> outputPaths)
                throws IOException {
            LinkedHashSet<Path> paths = new LinkedHashSet<>();
            List<Path> reportedPaths = Objects.requireNonNull(
                    layout.existingVolumePaths(),
                    "layout.existingVolumePaths()"
            );
            for (Path path : reportedPaths) {
                Objects.requireNonNull(path, "existing volume path");
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    paths.add(resolveVolumePath(path, "existing volume path"));
                }
            }
            for (Path outputPath : outputPaths) {
                if (Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)) {
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
                    publishedPaths.remove(index);
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                    cleanupFailed = true;
                }
            }
            for (int index = backups.size() - 1; index >= 0; index--) {
                OutputBackup backup = backups.get(index);
                try {
                    Files.move(backup.backupPath(), backup.outputPath());
                    backups.remove(index);
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
