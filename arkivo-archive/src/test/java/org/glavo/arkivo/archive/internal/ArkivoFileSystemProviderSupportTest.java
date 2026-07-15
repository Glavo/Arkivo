// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
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
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared archive file system provider URI and registration behavior.
@NotNullByDefault
final class ArkivoFileSystemProviderSupportTest {
    /// Temporary directory used to create portable nested file URIs.
    @TempDir
    private Path temporaryDirectory;

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
        Files.createDirectory(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("child.txt"), "child");
        ArkivoFileSystemProviderSupport.copy(sourceDirectory, targetDirectory);
        assertTrue(Files.isDirectory(targetDirectory));
        assertFalse(Files.exists(targetDirectory.resolve("child.txt")));
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
                                releaseFactories.await();
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

            factoriesReady.await();
            releaseFactories.countDown();
            int successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
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

        /// Whether this file system remains open.
        private boolean open = true;

        /// Creates one open test file system.
        private TestFileSystem(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        /// Returns the default provider because this test file system does not perform path operations.
        @Override
        public FileSystemProvider provider() {
            return FileSystems.getDefault().provider();
        }

        /// Closes this file system and unregisters it once.
        @Override
        public void close() {
            if (open) {
                open = false;
                closeAction.run();
            }
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
    private enum TestCopyOption implements CopyOption {
        /// An option unsupported by the shared copy implementation.
        UNSUPPORTED
    }
}
