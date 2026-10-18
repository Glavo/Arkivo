// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the NIO provider lifecycle and public ZIP file-system factory preconditions.
@NotNullByDefault
public final class ZipArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Uses the source provider's path equality when comparing differently cased archive URIs.
    @Test
    public void registersArchiveUsingSourcePathIdentity() throws IOException {
        Path archive = temporaryDirectory.resolve("MixedCase.zip");
        Path alias = temporaryDirectory.resolve("mixedcase.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI uri = URI.create(provider.getScheme() + ":" + archive.toUri());
        URI aliasUri = URI.create(provider.getScheme() + ":" + alias.toUri());
        Map<String, Object> environment = Map.of("arkivo.openOptions", Set.of(StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        try (var fileSystem = provider.newFileSystem(uri, environment)) {
            Files.writeString(fileSystem.getPath("/entry"), "original");
            if (archive.equals(alias)) {
                assertSame(fileSystem, provider.getFileSystem(aliasUri));
                assertSame(fileSystem, provider.getPath(URI.create(aliasUri + "!/entry")).getFileSystem());
                assertThrows(FileSystemAlreadyExistsException.class, () -> {
                    try (var unexpected = provider.newFileSystem(aliasUri, environment)) {
                        Files.writeString(unexpected.getPath("/entry"), "replacement");
                    }
                });
            } else {
                assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(aliasUri));
            }
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("entry"))) {
                assertEquals("original", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(uri));
        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(aliasUri));
    }

    /// Keeps the URI reserved until failed split publication can no longer remove destination volumes.
    @Test
    public void reservesUriUntilSplitRollbackCompletes() throws IOException {
        Path archive = temporaryDirectory.resolve("retry.zip");
        Path firstVolume = temporaryDirectory.resolve("retry.z01");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI uri = URI.create(provider.getScheme() + ":" + archive.toUri());
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions", Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING),
                "arkivo.zip.splitSize", 65_536L);
        var fileSystem = provider.newFileSystem(uri, environment);
        byte[] content = new byte[150_000];
        new Random(17).nextBytes(content);
        Files.write(fileSystem.getPath("/entry"), content);
        Path staging;
        try (var paths = Files.list(temporaryDirectory)) {
            var directories = paths.filter(path -> path.getFileName().toString().startsWith(".arkivo-volumes-"))
                    .toList();
            assertEquals(1, directories.size());
            staging = directories.get(0);
        }
        // The first two volumes are closed before the final volume is published.
        Files.delete(staging.resolve("volume-1"));
        Path stagedFirst = staging.resolve("volume-0");
        Files.delete(stagedFirst);
        Files.createDirectory(stagedFirst);
        Files.writeString(stagedFirst.resolve("blocker"), "blocker");
        try {
            assertThrows(IOException.class, fileSystem::close);
            assertFalse(fileSystem.isOpen());
            assertTrue(Files.isDirectory(firstVolume));
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(uri));
            assertThrows(FileSystemAlreadyExistsException.class, () -> {
                try (var unexpected = provider.newFileSystem(uri, environment)) {
                    Files.writeString(unexpected.getPath("/unexpected"), "unexpected");
                }
            });
        } finally {
            Files.deleteIfExists(firstVolume.resolve("blocker"));
            fileSystem.close();
        }
        assertFalse(Files.exists(staging));
        try (var replacement = provider.newFileSystem(uri, environment)) {
            Files.writeString(replacement.getPath("/entry"), "new-payload");
        }
        fileSystem.close();
        try (var reopened = provider.newFileSystem(uri, Map.of())) {
            assertEquals("new-payload", Files.readString(reopened.getPath("/entry")));
        }
    }

    /// Reserves a writable URI before its target callback can modify the archive.
    @Test
    public void rejectsDuplicateWhileOpeningWritableFileSystem() throws Exception {
        Path archive = temporaryDirectory.resolve("opening.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI uri = URI.create(provider.getScheme() + ":" + archive.toUri());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ArkivoCommitTarget target = source -> {
            calls.incrementAndGet();
            entered.countDown();
            awaitRelease(release);
            return ArkivoCommitTarget.writeTo(archive).openOutput(source);
        };
        Map<String, Object> environment = writableEnvironment(target);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var opening = executor.submit(() -> provider.newFileSystem(uri, environment));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(uri));
                assertThrows(FileSystemAlreadyExistsException.class, () -> {
                    try (var unexpected = provider.newFileSystem(uri, writableEnvironment(
                            ArkivoCommitTarget.writeTo(archive)))) {
                        Files.writeString(unexpected.getPath("/unexpected"), "unexpected");
                    }
                });
            } finally {
                release.countDown();
                try (var fileSystem = opening.get(10, TimeUnit.SECONDS)) {
                    Files.writeString(fileSystem.getPath("/entry"), "preserved");
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, calls.get());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(1, zip.size());
            try (var input = zip.getInputStream(zip.getEntry("entry"))) {
                assertEquals("preserved", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /// Retains a writable URI while a logically closed file system is still publishing its archive.
    @Test
    public void rejectsDuplicateWhileCommittingWritableFileSystem() throws Exception {
        Path archive = temporaryDirectory.resolve("committing.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI uri = URI.create(provider.getScheme() + ":" + archive.toUri());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ArkivoCommitTarget target = source -> new GatedCommitOutput(
                ArkivoCommitTarget.writeTo(archive).openOutput(source), entered, release);
        Map<String, Object> environment = new HashMap<>(writableEnvironment(target));
        // The latch publishes the closing state without relying on the file-system operation lock.
        environment.put("arkivo.threadSafety", "none");
        var executor = Executors.newSingleThreadExecutor();
        try (var fileSystem = provider.newFileSystem(uri, environment)) {
            Files.writeString(fileSystem.getPath("/entry"), "preserved");
            var closing = executor.submit(() -> {
                fileSystem.close();
                return null;
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(fileSystem.isOpen());
                assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(uri));
                assertThrows(FileSystemAlreadyExistsException.class, () -> {
                    try (var unexpected = provider.newFileSystem(uri,
                            writableEnvironment(ArkivoCommitTarget.writeTo(archive)))) {
                        Files.writeString(unexpected.getPath("/unexpected"), "unexpected");
                    }
                });
            } finally {
                release.countDown();
                closing.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(1, zip.size());
            try (var input = zip.getInputStream(zip.getEntry("entry"))) {
                assertEquals("preserved", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        try (var reopened = provider.newFileSystem(uri, Map.of())) {
            assertEquals("preserved", Files.readString(reopened.getPath("/entry")));
        }
    }

    /// Returns NIO options for creating an archive through a supplied commit target.
    private static Map<String, Object> writableEnvironment(ArkivoCommitTarget target) {
        return Map.of("arkivo.openOptions", Set.of(StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), "arkivo.commitTarget", target);
    }

    /// Waits for the test thread to finish checking an in-progress factory or commit.
    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for registry checks");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for registry checks", exception);
        }
    }

    /// Delays publication after the file system becomes logically closed.
    ///
    /// @param delegate the output that receives the archive
    /// @param entered signals entry to commit
    /// @param release permits commit to finish
    @NotNullByDefault
    private record GatedCommitOutput(ArkivoCommitOutput delegate, CountDownLatch entered,
                                    CountDownLatch release) implements ArkivoCommitOutput {
        @Override
        public Path path() {
            return delegate.path();
        }

        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return delegate.openChannel(options);
        }

        @Override
        public void commit() throws IOException {
            entered.countDown();
            awaitRelease(release);
            delegate.commit();
        }

        @Override
        public void rollback() throws IOException {
            delegate.rollback();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /// Verifies URI registration, entry resolution, duplicate detection, and deregistration.
    @Test
    public void managesReadOnlyUriFileSystemLifecycle() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("archive.zip")
        );
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI entryUri = URI.create(fileSystemUri + "!/dir/hello.txt");

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
            Path entry = provider.getPath(entryUri);
            assertEquals(entryUri, entry.toUri());
            assertEquals("hello", Files.readString(entry, StandardCharsets.UTF_8));
            assertThrows(
                    FileSystemAlreadyExistsException.class,
                    () -> provider.newFileSystem(fileSystemUri, Map.of())
            );
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
    }

    /// Verifies that closing a writable URI file system deregisters it immediately.
    @Test
    public void deregistersWritableFileSystemOnClose() throws IOException {
        Path archivePath = temporaryDirectory.resolve("writable.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, environment)) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
    }

    /// Verifies that raw NIO environments accept open-option arrays.
    @Test
    public void acceptsOpenOptionArrays() throws IOException {
        Path archivePath = temporaryDirectory.resolve("array-options.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();

        try (ZipArkivoFileSystem fileSystem = provider.newFileSystem(
                archivePath,
                Map.of(
                        "arkivo.openOptions",
                        new StandardOpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE}
                )
        )) {
            Files.writeString(fileSystem.getPath("/hello.txt"), "hello", StandardCharsets.UTF_8);
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8));
        }
    }

    /// Verifies that unsafe writable open-option combinations fail before creating the destination.
    @Test
    public void rejectsUnsafeWritableOpenOptions() {
        Path archivePath = temporaryDirectory.resolve("unsafe-options.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.newFileSystem(
                        archivePath,
                        Map.of(
                                "arkivo.openOptions",
                                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        )
                )
        );
        assertFalse(Files.exists(archivePath));
    }

    /// Verifies volume factories reject invalid bounds and conflicting options before opening either endpoint.
    @Test
    public void rejectsInvalidVolumeFactoryConfigurationBeforeOpeningEndpoints() {
        ArkivoVolumeSource source = index -> {
            throw new AssertionError("Volume source must not be opened");
        };
        ArkivoVolumeTarget target = () -> {
            throw new AssertionError("Volume target must not be opened");
        };

        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.create(target, ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.update(
                        source,
                        target,
                        ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE + 1L
                )
        );

        ZipArchiveOptions.Create createWithEditStorage = ZipArchiveOptions.CREATE_DEFAULTS.withCommon(
                ZipArchiveOptions.CREATE_DEFAULTS.common()
                        .withEditStorageFactory(ArkivoEditStorageFactory.memory())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.create(
                        target,
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE,
                        createWithEditStorage
                )
        );

        ZipArchiveOptions.Update updateWithCommitTarget = ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(
                ZipArchiveOptions.UPDATE_DEFAULTS.common()
                        .withCommitTarget(ArkivoCommitTarget.writeTo(temporaryDirectory.resolve("unused.zip")))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.update(
                        source,
                        target,
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE,
                        updateWithCommitTarget
                )
        );
    }
}
