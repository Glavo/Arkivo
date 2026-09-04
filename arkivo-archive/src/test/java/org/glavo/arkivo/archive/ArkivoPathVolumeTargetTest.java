// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies staged publication and recovery for path-backed multi-volume targets.
@NotNullByDefault
final class ArkivoPathVolumeTargetTest {
    /// Temporary output directory for each test invocation.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies final-index-dependent naming and removal of stale output volumes.
    @Test
    void publishesNamedVolumesAndRemovesStaleOutput() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        Files.write(layout.volumePath(0L, 3L), bytes("old-0"));
        Files.write(layout.volumePath(1L, 3L), bytes("old-1"));
        Files.write(layout.volumePath(2L, 3L), bytes("old-2"));
        Files.write(layout.volumePath(3L, 3L), bytes("old-final"));

        try (ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput()) {
            writeVolume(output, 0L, "new-0");
            writeVolume(output, 1L, "new-final");
            output.commit(1L);
        }

        assertArrayEquals(bytes("new-0"), Files.readAllBytes(layout.volumePath(0L, 1L)));
        assertArrayEquals(bytes("new-final"), Files.readAllBytes(layout.volumePath(1L, 1L)));
        assertFalse(Files.exists(layout.numberedPath(1L)));
        assertFalse(Files.exists(layout.numberedPath(2L)));
        assertNoStagingDirectories();
    }

    /// Verifies failed publication removes new output and restores every previous volume.
    @Test
    void restoresExistingVolumesAfterPublicationFailure() throws IOException {
        TestLayout successfulLayout = new TestLayout(temporaryDirectory);
        Path oldNumberedPath = successfulLayout.numberedPath(0L);
        Path oldFinalPath = successfulLayout.finalPath();
        Files.write(oldNumberedPath, bytes("old-numbered"));
        Files.write(oldFinalPath, bytes("old-final"));

        ArkivoVolumePathLayout failingLayout = new FailingLayout(successfulLayout);
        try (ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(failingLayout).openOutput()) {
            writeVolume(output, 0L, "new-numbered");
            writeVolume(output, 1L, "new-final");
            assertThrows(IOException.class, () -> output.commit(1L));
        }

        assertArrayEquals(bytes("old-numbered"), Files.readAllBytes(oldNumberedPath));
        assertArrayEquals(bytes("old-final"), Files.readAllBytes(oldFinalPath));
        assertFalse(Files.exists(temporaryDirectory.resolve("missing")));
        assertNoStagingDirectories();
    }

    /// Verifies volume indexes, final-index validation, and terminal-state rejection around one successful commit.
    @Test
    void enforcesVolumeTransactionStateMachine() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput();

        assertThrows(IllegalArgumentException.class, () -> output.openVolume(-1L));
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(1L));
        writeVolume(output, 0L, "only-volume");
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(0L));
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(2L));
        assertThrows(IllegalArgumentException.class, () -> output.commit(-1L));
        assertThrows(IllegalArgumentException.class, () -> output.commit(1L));

        output.commit(0L);
        assertArrayEquals(bytes("only-volume"), Files.readAllBytes(layout.finalPath()));
        assertThrows(IOException.class, () -> output.openVolume(1L));
        assertThrows(IOException.class, () -> output.commit(0L));

        output.rollback();
        output.close();
        assertNoStagingDirectories();
    }

    /// Verifies `CREATE_NEW` detects destinations created after staging and preserves the external file.
    @Test
    void rejectsCreateNewPublicationRace() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        ArkivoPathVolumeTarget target = new ArkivoPathVolumeTarget(
                layout,
                Set.of(StandardOpenOption.CREATE_NEW)
        );

        try (ArkivoVolumeOutput output = target.openOutput()) {
            writeVolume(output, 0L, "staged");
            Files.write(layout.finalPath(), bytes("external"));

            assertThrows(FileAlreadyExistsException.class, () -> output.commit(0L));
            assertArrayEquals(bytes("external"), Files.readAllBytes(layout.finalPath()));
            assertThrows(IOException.class, () -> output.openVolume(1L));
        }

        assertNoStagingDirectories();
    }

    /// Verifies failed staging cleanup leaves recoverable state and a later rollback completes deletion.
    @Test
    void retriesRollbackCleanupAfterFailure() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput();
        writeVolume(output, 0L, "staged");

        Path stagingDirectory = singleStagingDirectory();
        Path stagedVolume = stagingDirectory.resolve("volume-0");
        Files.delete(stagedVolume);
        Files.createDirectory(stagedVolume);
        Path blocker = Files.writeString(stagedVolume.resolve("blocker"), "blocker");

        assertThrows(DirectoryNotEmptyException.class, output::rollback);
        assertTrue(Files.exists(stagingDirectory));
        assertThrows(IOException.class, () -> output.openVolume(1L));

        Files.delete(blocker);
        output.rollback();
        output.rollback();
        output.close();
        assertFalse(Files.exists(stagingDirectory));
        assertNoStagingDirectories();
    }

    /// Verifies cleanup can be retried after publication succeeds without rolling the published volume back.
    @Test
    void retriesCleanupAfterCommittedPublication() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput();
        writeVolume(output, 0L, "published");

        Path stagingDirectory = singleStagingDirectory();
        Path blockerDirectory = Files.createDirectory(stagingDirectory.resolve("blocker"));
        Path blocker = Files.writeString(blockerDirectory.resolve("child"), "blocker");

        assertThrows(DirectoryNotEmptyException.class, () -> output.commit(0L));
        assertArrayEquals(bytes("published"), Files.readAllBytes(layout.finalPath()));
        assertTrue(Files.exists(stagingDirectory));
        assertThrows(IOException.class, () -> output.openVolume(1L));

        Files.delete(blocker);
        output.rollback();
        output.close();
        assertArrayEquals(bytes("published"), Files.readAllBytes(layout.finalPath()));
        assertFalse(Files.exists(stagingDirectory));
        assertNoStagingDirectories();
    }

    /// Verifies publication-time runtime failures retain their identity and clean all staging state.
    @Test
    void propagatesRuntimePublicationFailure() throws IOException {
        TestLayout delegate = new TestLayout(temporaryDirectory);
        IllegalStateException failure = new IllegalStateException("discovery failure");
        FailingDiscoveryLayout layout = new FailingDiscoveryLayout(delegate, failure);

        ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput();
        writeVolume(output, 0L, "staged");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> output.commit(0L));

        assertSame(failure, thrown);
        assertThrows(IOException.class, () -> output.openVolume(1L));
        output.close();
        assertFalse(Files.exists(delegate.finalPath()));
        assertNoStagingDirectories();
    }

    /// Verifies publication-time errors retain their identity after transaction cleanup.
    @Test
    void propagatesErrorPublicationFailure() throws IOException {
        TestLayout delegate = new TestLayout(temporaryDirectory);
        AssertionError failure = new AssertionError("fatal discovery failure");
        FailingDiscoveryLayout layout = new FailingDiscoveryLayout(delegate, failure);

        ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(layout).openOutput();
        writeVolume(output, 0L, "staged");
        AssertionError thrown = assertThrows(AssertionError.class, () -> output.commit(0L));

        assertSame(failure, thrown);
        assertThrows(IOException.class, () -> output.openVolume(1L));
        output.close();
        assertFalse(Files.exists(delegate.finalPath()));
        assertNoStagingDirectories();
    }

    /// Verifies create-new and existing-target options are checked before staging starts.
    @Test
    void validatesPublicationOpenOptionsBeforeStaging() throws IOException {
        TestLayout layout = new TestLayout(temporaryDirectory);
        Files.write(layout.finalPath(), bytes("existing"));

        ArkivoPathVolumeTarget createNewTarget = new ArkivoPathVolumeTarget(
                layout,
                Set.of(StandardOpenOption.CREATE_NEW)
        );
        assertThrows(FileAlreadyExistsException.class, createNewTarget::openOutput);

        Files.delete(layout.finalPath());
        ArkivoPathVolumeTarget existingTarget = new ArkivoPathVolumeTarget(
                layout,
                Set.of(StandardOpenOption.WRITE)
        );
        assertThrows(NoSuchFileException.class, existingTarget::openOutput);

        assertThrows(
                UnsupportedOperationException.class,
                () -> new ArkivoPathVolumeTarget(layout, Set.of(StandardOpenOption.APPEND))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> new ArkivoPathVolumeTarget(layout, Set.of(StandardOpenOption.DELETE_ON_CLOSE))
        );

        HashSet<OpenOption> mutableOptions = new HashSet<>(Set.of(StandardOpenOption.CREATE_NEW));
        ArkivoPathVolumeTarget snapshotTarget = new ArkivoPathVolumeTarget(layout, mutableOptions);
        mutableOptions.clear();
        mutableOptions.add(StandardOpenOption.APPEND);
        try (ArkivoVolumeOutput output = snapshotTarget.openOutput()) {
            writeVolume(output, 0L, "snapshot");
            output.commit(0L);
        }
        assertArrayEquals(bytes("snapshot"), Files.readAllBytes(layout.finalPath()));

        assertNoStagingDirectories();
    }

    /// Verifies constructor arguments and individual open options are required.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesTargetConstructorArguments() {
        TestLayout layout = new TestLayout(temporaryDirectory);
        HashSet<OpenOption> optionsWithNull = new HashSet<>();
        optionsWithNull.add(null);

        assertThrows(NullPointerException.class, () -> new ArkivoPathVolumeTarget(null));
        assertThrows(NullPointerException.class, () -> new ArkivoPathVolumeTarget(layout, null));
        assertThrows(
                NullPointerException.class,
                () -> new ArkivoPathVolumeTarget(layout, optionsWithNull)
        );
    }

    /// Verifies duplicate final paths are rejected before existing output is modified.
    @Test
    void rejectsDuplicatePublishedPathsWithoutMutatingExistingOutput() throws IOException {
        TestLayout successfulLayout = new TestLayout(temporaryDirectory);
        Files.write(successfulLayout.finalPath(), bytes("existing"));
        ArkivoVolumePathLayout duplicateLayout = new DuplicateLayout(successfulLayout);

        try (ArkivoVolumeOutput output = new ArkivoPathVolumeTarget(duplicateLayout).openOutput()) {
            writeVolume(output, 0L, "first");
            writeVolume(output, 1L, "second");
            assertThrows(IllegalArgumentException.class, () -> output.commit(1L));
        }

        assertArrayEquals(bytes("existing"), Files.readAllBytes(successfulLayout.finalPath()));
        assertNoStagingDirectories();
    }

    /// Writes one complete staged volume and closes its channel.
    private static void writeVolume(ArkivoVolumeOutput output, long index, String value) throws IOException {
        try (WritableByteChannel channel = output.openVolume(index)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes(value));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /// Encodes one test value as UTF-8 bytes.
    private static byte @Unmodifiable [] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// Verifies no transaction staging directory remains after an operation.
    private void assertNoStagingDirectories() throws IOException {
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".arkivo-volumes-")));
        }
    }

    /// Returns the only transaction staging directory currently present.
    private Path singleStagingDirectory() throws IOException {
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            List<Path> stagingDirectories = paths
                    .filter(path -> path.getFileName().toString().startsWith(".arkivo-volumes-"))
                    .toList();
            assertEquals(1, stagingDirectories.size());
            return stagingDirectories.get(0);
        }
    }

    /// Implements a final-index-dependent test path layout.
    ///
    /// @param directory the output directory
    @NotNullByDefault
    private record TestLayout(Path directory) implements ArkivoVolumePathLayout {
        /// Returns the transaction staging directory.
        @Override
        public Path outputDirectory() {
            return directory;
        }

        /// Returns a numbered path for preceding volumes and a stable final path for the last volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            if (index < 0L || index > finalVolumeIndex) {
                throw new IllegalArgumentException("Invalid test volume index: " + index);
            }
            return index == finalVolumeIndex ? finalPath() : numberedPath(index);
        }

        /// Returns all existing paths owned by this test layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.equals("archive.bin") || name.startsWith("part-");
                        })
                        .sorted()
                        .toList();
            }
        }

        /// Returns one numbered preceding-volume path.
        private Path numberedPath(long index) {
            return directory.resolve("part-" + index + ".bin");
        }

        /// Returns the stable final-volume path.
        private Path finalPath() {
            return directory.resolve("archive.bin");
        }
    }

    /// Selects an unavailable directory for the final path to force publication failure.
    ///
    /// @param delegate the successful layout used for discovery and preceding volumes
    @NotNullByDefault
    private record FailingLayout(TestLayout delegate) implements ArkivoVolumePathLayout {
        /// Returns the shared transaction staging directory.
        @Override
        public Path outputDirectory() {
            return delegate.outputDirectory();
        }

        /// Returns a missing-parent path for the final volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            return index == finalVolumeIndex
                    ? delegate.directory().resolve("missing").resolve("archive.bin")
                    : delegate.volumePath(index, finalVolumeIndex);
        }

        /// Returns existing paths from the successful layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
            return delegate.existingVolumePaths();
        }
    }

    /// Maps every logical volume to one path to exercise duplicate validation.
    ///
    /// @param delegate the layout supplying discovery and output directory behavior
    @NotNullByDefault
    private record DuplicateLayout(TestLayout delegate) implements ArkivoVolumePathLayout {
        /// Returns the shared transaction staging directory.
        @Override
        public Path outputDirectory() {
            return delegate.outputDirectory();
        }

        /// Returns the same final path for every logical volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            return delegate.finalPath();
        }

        /// Returns existing paths from the successful layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
            return delegate.existingVolumePaths();
        }
    }

    /// Fails the second existing-volume discovery call made when publication begins.
    @NotNullByDefault
    private static final class FailingDiscoveryLayout implements ArkivoVolumePathLayout {
        /// Layout supplying valid output paths and initial discovery.
        private final TestLayout delegate;

        /// Unchecked failure thrown during publication-time discovery.
        private final Throwable failure;

        /// Number of completed or attempted discovery calls.
        private int discoveryCount;

        /// Creates a layout with one successful discovery followed by a stable runtime failure.
        private FailingDiscoveryLayout(TestLayout delegate, Throwable failure) {
            this.delegate = delegate;
            if (!(failure instanceof RuntimeException) && !(failure instanceof Error)) {
                throw new IllegalArgumentException("failure must be unchecked");
            }
            this.failure = failure;
        }

        /// Returns the delegate output directory.
        @Override
        public Path outputDirectory() {
            return delegate.outputDirectory();
        }

        /// Returns the delegate volume path.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            return delegate.volumePath(index, finalVolumeIndex);
        }

        /// Returns an empty initial discovery and fails the publication-time discovery.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            if (discoveryCount++ == 0) {
                return List.of();
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }
}
