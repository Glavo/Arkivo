// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared archive file system provider URI and registration behavior.
@NotNullByDefault
final class ArkivoFileSystemProviderSupportTest {
    /// Maximum time allowed for cooperating registry test tasks to make progress.
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10L;

    /// Temporary directory used to create portable nested file URIs.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies every read adapter resolves only pure following reads to a real path.
    @Test
    void resolvesReadPathsAccordingToOptions() throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("paths"));
        Path nestedDirectory = Files.createDirectory(directory.resolve("nested"));
        Path file = Files.writeString(directory.resolve("file.txt"), "payload");
        Path lexicalPath = nestedDirectory.resolve("..").resolve("file.txt");
        Path realPath = file.toRealPath();

        assertEquals(realPath, ArkivoFileSystemProviderSupport.resolveReadPath(lexicalPath));
        assertEquals(
                realPath,
                ArkivoFileSystemProviderSupport.resolveReadPath(lexicalPath, StandardOpenOption.READ)
        );
        assertSame(
                lexicalPath,
                ArkivoFileSystemProviderSupport.resolveReadPath(lexicalPath, StandardOpenOption.WRITE)
        );
        assertSame(
                lexicalPath,
                ArkivoFileSystemProviderSupport.resolveReadPath(
                        lexicalPath,
                        new OpenOption[]{StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS}
                )
        );

        assertEquals(
                realPath,
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(lexicalPath, Set.of())
        );
        assertEquals(
                realPath,
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(
                        lexicalPath,
                        Set.of(StandardOpenOption.READ)
                )
        );
        assertSame(
                lexicalPath,
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(
                        lexicalPath,
                        Set.of(LinkOption.NOFOLLOW_LINKS)
                )
        );
        assertSame(
                lexicalPath,
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(
                        lexicalPath,
                        Set.of(StandardOpenOption.WRITE)
                )
        );

        assertEquals(
                realPath,
                ArkivoFileSystemProviderSupport.resolveReadPath(lexicalPath, new LinkOption[0])
        );
        assertSame(
                lexicalPath,
                ArkivoFileSystemProviderSupport.resolveReadPath(
                        lexicalPath,
                        new LinkOption[]{LinkOption.NOFOLLOW_LINKS}
                )
        );

        ArkivoFileSystemProviderSupport.AttributeViewPath followingView =
                ArkivoFileSystemProviderSupport.attributeViewPath(lexicalPath);
        assertSame(lexicalPath, followingView.path());
        assertTrue(followingView.followLinks());
        assertEquals(realPath, followingView.resolve());

        ArkivoFileSystemProviderSupport.AttributeViewPath lexicalView =
                ArkivoFileSystemProviderSupport.attributeViewPath(
                        lexicalPath,
                        LinkOption.NOFOLLOW_LINKS
                );
        assertSame(lexicalPath, lexicalView.path());
        assertFalse(lexicalView.followLinks());
        assertSame(lexicalPath, lexicalView.resolve());
    }

    /// Verifies path-resolution helpers reject null paths, option arrays, and option elements.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesReadPathArguments() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("arguments.txt"), "payload");

        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadPath(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadPath(file, (OpenOption[]) null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadPath(file, new OpenOption[]{null})
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadChannelPath(file, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadPath(file, (LinkOption[]) null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.resolveReadPath(file, new LinkOption[]{null})
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.attributeViewPath(file, (LinkOption[]) null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.attributeViewPath(file, new LinkOption[]{null})
        );
        assertThrows(
                NullPointerException.class,
                () -> new ArkivoFileSystemProviderSupport.AttributeViewPath(null, true)
        );
    }

    /// Verifies regular files, empty directories, replacement, and basic timestamp copying.
    @Test
    void copiesPathsWithStandardOptions() throws IOException {
        Path sourceFile = temporaryDirectory.resolve("source.txt");
        Path targetFile = temporaryDirectory.resolve("target.txt");
        FileTime modifiedTime = FileTime.from(Instant.parse("2020-02-03T04:05:06Z"));
        Files.writeString(sourceFile, "source");
        Files.setLastModifiedTime(sourceFile, modifiedTime);

        ArkivoFileSystemProviderSupport.copy(
                sourceFile,
                targetFile,
                StandardCopyOption.COPY_ATTRIBUTES
        );
        assertEquals("source", Files.readString(targetFile));
        assertEquals(modifiedTime, Files.getLastModifiedTime(targetFile));
        assertThrows(
                FileAlreadyExistsException.class,
                () -> ArkivoFileSystemProviderSupport.copy(sourceFile, targetFile)
        );

        Files.writeString(sourceFile, "replacement");
        ArkivoFileSystemProviderSupport.copy(
                sourceFile,
                targetFile,
                StandardCopyOption.REPLACE_EXISTING
        );
        assertEquals("replacement", Files.readString(targetFile));
        ArkivoFileSystemProviderSupport.copy(sourceFile, sourceFile);

        Path sourceDirectory = temporaryDirectory.resolve("source-directory");
        Path targetDirectory = temporaryDirectory.resolve("target-directory");
        FileTime directoryModifiedTime = FileTime.from(Instant.parse("2021-03-04T05:06:08Z"));
        Files.createDirectory(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("child.txt"), "child");
        Files.setLastModifiedTime(sourceDirectory, directoryModifiedTime);
        ArkivoFileSystemProviderSupport.copy(
                sourceDirectory,
                targetDirectory,
                StandardCopyOption.COPY_ATTRIBUTES
        );
        assertTrue(Files.isDirectory(targetDirectory));
        assertFalse(Files.exists(targetDirectory.resolve("child.txt")));
        assertEquals(directoryModifiedTime, Files.getLastModifiedTime(targetDirectory));
    }

    /// Verifies directory replacement remains non-recursive and preserves a compatible target directory.
    @Test
    void replacesCopyTargetsAccordingToTheirKinds() throws IOException {
        Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source-directory"));

        Path existingDirectory = Files.createDirectory(temporaryDirectory.resolve("existing-directory"));
        Path existingChild = Files.writeString(existingDirectory.resolve("existing.txt"), "existing");
        ArkivoFileSystemProviderSupport.copy(
                sourceDirectory,
                existingDirectory,
                StandardCopyOption.REPLACE_EXISTING
        );
        assertTrue(Files.isDirectory(existingDirectory));
        assertEquals("existing", Files.readString(existingChild));

        Path occupiedTarget = Files.writeString(temporaryDirectory.resolve("occupied"), "old");
        ArkivoFileSystemProviderSupport.copy(
                sourceDirectory,
                occupiedTarget,
                StandardCopyOption.REPLACE_EXISTING
        );
        assertTrue(Files.isDirectory(occupiedTarget));
        assertFalse(Files.exists(occupiedTarget.resolve("existing.txt")));

        Path conflictingDirectory = Files.createDirectory(temporaryDirectory.resolve("conflicting-directory"));
        assertThrows(
                FileAlreadyExistsException.class,
                () -> ArkivoFileSystemProviderSupport.copy(sourceDirectory, conflictingDirectory)
        );
    }

    /// Verifies unsupported copy options are rejected before an existing target is modified.
    @Test
    void rejectsUnsupportedCopyOptionsBeforeMutation() throws IOException {
        Path source = temporaryDirectory.resolve("unsupported-source.txt");
        Path target = temporaryDirectory.resolve("unsupported-target.txt");
        Files.writeString(source, "source");
        Files.writeString(target, "target");

        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFileSystemProviderSupport.copy(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        TestCopyOption.UNSUPPORTED
                )
        );
        assertEquals("target", Files.readString(target));

        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.copy(source, target, (CopyOption[]) null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.copy(source, target, new CopyOption[]{null})
        );
        assertEquals("target", Files.readString(target));
    }

    /// Verifies move and identity checks distinguish file-system instances and resolve lexical aliases.
    @Test
    void checksMoveAndFileIdentityWithinOneFileSystem() throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("identity"));
        Path nestedDirectory = Files.createDirectory(directory.resolve("nested"));
        Path file = Files.writeString(directory.resolve("file.txt"), "payload");
        Path lexicalAlias = nestedDirectory.resolve("..").resolve("file.txt");
        Path otherFile = Files.writeString(directory.resolve("other.txt"), "payload");

        assertDoesNotThrow(() -> ArkivoFileSystemProviderSupport.requireSameFileSystemMove(file, otherFile));
        assertTrue(ArkivoFileSystemProviderSupport.isSameFile(file, file));
        assertTrue(ArkivoFileSystemProviderSupport.isSameFile(file, lexicalAlias));
        assertFalse(ArkivoFileSystemProviderSupport.isSameFile(file, otherFile));

        Path zipPath = temporaryDirectory.resolve("other-file-system.zip");
        URI zipUri = URI.create("jar:" + zipPath.toUri());
        try (FileSystem otherFileSystem = FileSystems.newFileSystem(zipUri, Map.of("create", "true"))) {
            Path foreignPath = otherFileSystem.getPath("/foreign.txt");
            assertThrows(
                    ProviderMismatchException.class,
                    () -> ArkivoFileSystemProviderSupport.requireSameFileSystemMove(file, foreignPath)
            );
            assertFalse(ArkivoFileSystemProviderSupport.isSameFile(file, foreignPath));
        }
    }

    /// Verifies path-format validation falls back to case-insensitive extensions for absent or unknown archives.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArchivePathsByContentOrExtension() throws IOException {
        ArkivoFormat expectedFormat = TestArchiveFormat.EXPECTED;

        assertDoesNotThrow(() -> ArkivoFileSystemProviderSupport.requirePathFormat(
                temporaryDirectory.resolve("missing.TeSt"),
                expectedFormat
        ));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFileSystemProviderSupport.requirePathFormat(
                        temporaryDirectory.resolve("missing.bin"),
                        expectedFormat
                )
        );

        Path matchingExtension = Files.writeString(temporaryDirectory.resolve("unknown.ArK"), "not an archive");
        assertDoesNotThrow(() -> ArkivoFileSystemProviderSupport.requirePathFormat(
                matchingExtension,
                expectedFormat
        ));
        Path unknownExtension = Files.writeString(temporaryDirectory.resolve("unknown.bin"), "not an archive");
        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFileSystemProviderSupport.requirePathFormat(unknownExtension, expectedFormat)
        );

        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.requirePathFormat(null, expectedFormat)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFileSystemProviderSupport.requirePathFormat(matchingExtension, null)
        );
    }

    /// Verifies archive-only, entry, root-entry, and percent-decoded provider URIs.
    @Test
    void parsesProviderUris() {
        URI archiveUri = temporaryDirectory.resolve("archive with spaces.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.ParsedUri archive = ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri),
                "arkivo+test",
                "TEST",
                false
        );
        assertEquals(archiveUri, archive.archiveUri());
        assertEquals(Path.of(archiveUri), archive.archivePath());
        assertNull(archive.entryPath());

        ArkivoFileSystemProviderSupport.ParsedUri entry = ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri + "!/directory/a%20b.txt"),
                "arkivo+test",
                "TEST",
                true
        );
        assertEquals("/directory/a b.txt", entry.entryPath());

        ArkivoFileSystemProviderSupport.ParsedUri root = ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri + "!/"),
                "ARKIVO+TEST",
                "TEST",
                true
        );
        assertEquals("/", root.entryPath());
    }

    /// Verifies invalid schemes, missing entry suffixes, empty archives, queries, and fragments are rejected.
    @Test
    void rejectsInvalidProviderUris() {
        URI archiveUri = temporaryDirectory.resolve("archive.bin").toUri().normalize();
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+other:" + archiveUri),
                "arkivo+test",
                "TEST",
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri),
                "arkivo+test",
                "TEST",
                true
        ));
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:!/entry"),
                "arkivo+test",
                "TEST",
                true
        ));
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri + "!/entry?query"),
                "arkivo+test",
                "TEST",
                true
        ));
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemProviderSupport.parseUri(
                URI.create("arkivo+test:" + archiveUri + "!/entry#fragment"),
                "arkivo+test",
                "TEST",
                true
        ));
    }

    /// Verifies registration, duplicate rejection, close-driven removal, and reopening.
    @Test
    @SuppressWarnings("resource")
    void managesRegisteredFileSystemLifecycle() throws IOException {
        URI archiveUri = temporaryDirectory.resolve("archive.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.Registry<TestFileSystem> registry =
                new ArkivoFileSystemProviderSupport.Registry<>();
        AtomicInteger openCount = new AtomicInteger();

        TestFileSystem first = registry.open(archiveUri, closeAction -> {
            openCount.incrementAndGet();
            return new TestFileSystem(closeAction);
        });
        assertSame(first, registry.require(archiveUri));
        assertThrows(FileSystemAlreadyExistsException.class, () -> registry.open(archiveUri, closeAction -> {
            openCount.incrementAndGet();
            return new TestFileSystem(closeAction);
        }));
        assertEquals(1, openCount.get());

        first.close();
        assertFalse(first.isOpen());
        assertThrows(FileSystemNotFoundException.class, () -> registry.require(archiveUri));

        TestFileSystem second = registry.open(archiveUri, closeAction -> {
            openCount.incrementAndGet();
            return new TestFileSystem(closeAction);
        });
        assertTrue(second.isOpen());
        assertEquals(2, openCount.get());
        second.close();
    }

    /// Verifies stale closed registrations are removed by both lookup and subsequent open operations.
    @Test
    @SuppressWarnings("resource")
    void removesStaleClosedRegistrations() throws IOException {
        URI firstUri = temporaryDirectory.resolve("stale-require.bin").toUri().normalize();
        URI secondUri = temporaryDirectory.resolve("stale-open.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.Registry<TestFileSystem> registry =
                new ArkivoFileSystemProviderSupport.Registry<>();

        TestFileSystem staleLookup = registry.open(firstUri, TestFileSystem::new);
        staleLookup.closeWithoutUnregistering();
        assertThrows(FileSystemNotFoundException.class, () -> registry.require(firstUri));
        TestFileSystem lookupReplacement = registry.open(firstUri, TestFileSystem::new);
        assertSame(lookupReplacement, registry.require(firstUri));
        lookupReplacement.close();

        TestFileSystem staleOpen = registry.open(secondUri, TestFileSystem::new);
        staleOpen.closeWithoutUnregistering();
        TestFileSystem openReplacement = registry.open(secondUri, TestFileSystem::new);
        assertSame(openReplacement, registry.require(secondUri));
        openReplacement.close();
    }

    /// Verifies factory failures leave no registration and allow a later successful open.
    @Test
    @SuppressWarnings("resource")
    void leavesRegistryEmptyAfterFactoryFailure() throws IOException {
        URI archiveUri = temporaryDirectory.resolve("factory-failure.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.Registry<TestFileSystem> registry =
                new ArkivoFileSystemProviderSupport.Registry<>();
        IOException failure = new IOException("factory failure");

        IOException thrown = assertThrows(IOException.class, () -> registry.open(archiveUri, closeAction -> {
            throw failure;
        }));
        assertSame(failure, thrown);
        assertThrows(FileSystemNotFoundException.class, () -> registry.require(archiveUri));

        TestFileSystem fileSystem = registry.open(archiveUri, TestFileSystem::new);
        assertSame(fileSystem, registry.require(archiveUri));
        fileSystem.close();
    }

    /// Verifies a losing concurrent candidate propagates its close failure without replacing the winner.
    @Test
    @SuppressWarnings("resource")
    void propagatesLosingCandidateCloseFailure() throws Exception {
        URI archiveUri = temporaryDirectory.resolve("close-failure.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.Registry<TestFileSystem> registry =
                new ArkivoFileSystemProviderSupport.Registry<>();
        CountDownLatch delayedFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseDelayedFactory = new CountDownLatch(1);
        IOException closeFailure = new IOException("candidate close failure");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<IOException> losingResult = executor.submit(() -> assertThrows(
                    IOException.class,
                    () -> registry.open(archiveUri, closeAction -> {
                        delayedFactoryEntered.countDown();
                        try {
                            if (!releaseDelayedFactory.await(
                                    CONCURRENCY_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IOException("Timed out while delaying a registry candidate");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while delaying a registry candidate", exception);
                        }
                        return new TestFileSystem(closeAction, closeFailure);
                    })
            ));

            assertTrue(delayedFactoryEntered.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            TestFileSystem winner = registry.open(archiveUri, TestFileSystem::new);
            releaseDelayedFactory.countDown();

            assertSame(closeFailure, losingResult.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(winner, registry.require(archiveUri));
            winner.close();
        } finally {
            releaseDelayedFactory.countDown();
            executor.shutdownNow();
        }
    }

    /// Verifies concurrent candidates publish exactly one open file system and close every losing candidate.
    @Test
    @SuppressWarnings("resource")
    void publishesOneConcurrentFileSystem() throws Exception {
        int taskCount = 12;
        URI archiveUri = temporaryDirectory.resolve("concurrent.bin").toUri().normalize();
        ArkivoFileSystemProviderSupport.Registry<TestFileSystem> registry =
                new ArkivoFileSystemProviderSupport.Registry<>();
        List<TestFileSystem> created = new CopyOnWriteArrayList<>();
        List<TestFileSystem> opened = new CopyOnWriteArrayList<>();
        CountDownLatch factoriesReady = new CountDownLatch(taskCount);
        CountDownLatch releaseFactories = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);

        try {
            List<Future<Boolean>> results = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                results.add(executor.submit(() -> {
                    try {
                        TestFileSystem fileSystem = registry.open(archiveUri, closeAction -> {
                            factoriesReady.countDown();
                            try {
                                if (!releaseFactories.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    throw new IOException("Timed out while synchronizing test candidates");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted while synchronizing test candidates", exception);
                            }
                            TestFileSystem candidate = new TestFileSystem(closeAction);
                            created.add(candidate);
                            return candidate;
                        });
                        opened.add(fileSystem);
                        return true;
                    } catch (FileSystemAlreadyExistsException exception) {
                        return false;
                    }
                }));
            }

            assertTrue(factoriesReady.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            releaseFactories.countDown();
            int successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertEquals(1, successCount);
            assertEquals(taskCount, created.size());
            assertEquals(1, opened.size());
            TestFileSystem winner = opened.get(0);
            assertSame(winner, registry.require(archiveUri));
            assertEquals(taskCount - 1, created.stream().filter(fileSystem -> !fileSystem.isOpen()).count());
            winner.close();
            assertThrows(FileSystemNotFoundException.class, () -> registry.require(archiveUri));
        } finally {
            releaseFactories.countDown();
            executor.shutdownNow();
        }
    }

    /// Implements the minimum file system contract needed to exercise the registry.
    @NotNullByDefault
    private static final class TestFileSystem extends FileSystem {
        /// Action that removes this file system from its registry.
        private final Runnable closeAction;

        /// Exception thrown after this file system closes, or `null` for normal closure.
        private final @Nullable IOException closeFailure;

        /// Whether this file system remains open.
        private boolean open = true;

        /// Creates one open test file system.
        private TestFileSystem(Runnable closeAction) {
            this(closeAction, null);
        }

        /// Creates one open test file system with an optional close failure.
        private TestFileSystem(Runnable closeAction, @Nullable IOException closeFailure) {
            this.closeAction = closeAction;
            this.closeFailure = closeFailure;
        }

        /// Returns the default provider because this test file system does not perform path operations.
        @Override
        public FileSystemProvider provider() {
            return FileSystems.getDefault().provider();
        }

        /// Closes this file system and unregisters it once.
        @Override
        public void close() throws IOException {
            if (open) {
                open = false;
                closeAction.run();
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }

        /// Marks this file system closed without invoking its registry removal action.
        private void closeWithoutUnregistering() {
            open = false;
        }

        /// Returns whether this file system remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Returns whether this test file system is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the default separator.
        @Override
        public String getSeparator() {
            return "/";
        }

        /// Returns no root directories.
        @Override
        public Iterable<Path> getRootDirectories() {
            return List.of();
        }

        /// Returns no file stores.
        @Override
        public Iterable<FileStore> getFileStores() {
            return List.of();
        }

        /// Returns no supported attribute views.
        @Override
        public Set<String> supportedFileAttributeViews() {
            return Set.of();
        }

        /// Rejects path construction, which is outside this test's scope.
        @Override
        public Path getPath(String first, String... more) {
            throw new UnsupportedOperationException();
        }

        /// Rejects path matcher construction, which is outside this test's scope.
        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            throw new UnsupportedOperationException();
        }

        /// Rejects principal lookup, which is outside this test's scope.
        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            throw new UnsupportedOperationException();
        }

        /// Rejects watch services, which are outside this test's scope.
        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException();
        }
    }

    /// Provides an unsupported copy option for validation tests.
    @NotNullByDefault
    private enum TestCopyOption implements CopyOption {
        /// An option unsupported by the shared copy implementation.
        UNSUPPORTED
    }

    /// Provides a deterministic expected format for extension-based path validation.
    @NotNullByDefault
    private enum TestArchiveFormat implements ArkivoFormat {
        /// The expected test format.
        EXPECTED;

        /// Returns the two extensions accepted by the test format.
        @Override
        public List<String> fileExtensions() {
            return List.of("test", "ark");
        }
    }
}
