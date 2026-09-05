// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests installed-provider discovery, lifecycle, and URI mapping for DMG file systems.
@NotNullByDefault
public final class DMGArkivoFileSystemProviderTest {
    /// The isolated directory used for generated image fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies URI registration, entry resolution, duplicate detection, deregistration, and reopening.
    @Test
    public void managesUriBackedFileSystemLifecycle() throws IOException {
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("provider-uri.dmg"),
                createHFSPlusDisk()
        ).toAbsolutePath().normalize();
        URI fileSystemUri = URI.create(DMGArkivoFormat.instance().uriScheme() + ":" + imagePath.toUri());
        URI rootUri = URI.create(fileSystemUri + "!/");
        URI entryUri = URI.create(fileSystemUri + "!/hello.txt");

        try (FileSystem fileSystem = FileSystems.newFileSystem(
                fileSystemUri,
                Map.of("arkivo.dmg.partitionIndex", 0)
        )) {
            assertEquals(fileSystem, FileSystems.getFileSystem(fileSystemUri));
            Path entry = Path.of(entryUri);
            assertEquals(entryUri, entry.toUri());
            assertEquals(rootUri, fileSystem.getPath("/").toUri());
            assertEquals("hello", Files.readString(entry));
            assertThrows(
                    FileSystemAlreadyExistsException.class,
                    () -> FileSystems.newFileSystem(fileSystemUri, Map.of())
            );
        }

        assertThrows(FileSystemNotFoundException.class, () -> FileSystems.getFileSystem(fileSystemUri));

        try (FileSystem fileSystem = FileSystems.newFileSystem(fileSystemUri, Map.of())) {
            assertEquals(fileSystem, FileSystems.getFileSystem(fileSystemUri));
            assertEquals("hello", Files.readString(Path.of(entryUri)));
        }

        assertThrows(FileSystemNotFoundException.class, () -> FileSystems.getFileSystem(fileSystemUri));
    }

    /// Verifies provider options, NIO infrastructure, principals, matchers, and read-only mutations.
    @Test
    public void exposesProviderAndFileSystemContracts() throws IOException {
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("provider-contract.dmg"),
                createHFSPlusDisk()
        );
        DMGArkivoFileSystemProvider provider = new DMGArkivoFileSystemProvider();
        Map<String, Object> environment = Map.of(
                "arkivo.dmg.partitionIndex", 0,
                "arkivo.threadSafety", "strict"
        );

        try (DMGArkivoFileSystem fileSystem = provider.newFileSystem(imagePath, environment)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            assertSame(provider, fileSystem.provider());
            assertEquals(DMGArkivoFileSystemProvider.SCHEME, provider.getScheme());
            assertEquals(ArkivoFileSystemThreadSafety.STRICT, fileSystem.threadSafety());
            assertEquals("/", fileSystem.getSeparator());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());
            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertFalse(fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/hello\\.txt").matches(file));
            assertEquals(
                    "501",
                    fileSystem.getUserPrincipalLookupService().lookupPrincipalByName("501").getName()
            );
            assertEquals(
                    "20",
                    fileSystem.getUserPrincipalLookupService().lookupPrincipalByGroupName("20").getName()
            );
            UnsupportedOperationException watchFailure = assertThrows(
                    UnsupportedOperationException.class,
                    fileSystem::newWatchService
            );
            assertEquals("DMG watch services are not supported", watchFailure.getMessage());

            assertThrows(ReadOnlyFileSystemException.class, () -> Files.newOutputStream(file));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.createDirectory(fileSystem.getPath("/created"))
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.move(file, fileSystem.getPath("/renamed.txt"))
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", FileTime.fromMillis(1L))
            );
        }

        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        imagePath,
                        Map.of("arkivo.openOptions", Set.of(StandardOpenOption.WRITE))
                )
        );
    }
}
