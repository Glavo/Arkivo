// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoCommitTarget;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the NIO provider lifecycle and public ZIP file-system factory preconditions.
@NotNullByDefault
public final class ZipArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

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
