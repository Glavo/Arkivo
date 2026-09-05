// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies metadata available before a lazily opened ZIP archive is parsed.
@NotNullByDefault
final class ZipArkivoFileSystemMetadataTest {
    /// Provides isolated paths that are guaranteed not to identify an existing archive.
    @TempDir
    Path temporaryDirectory;

    /// Verifies opening a path defers archive access while exposing stable file-system metadata.
    @Test
    void defersArchiveAccessUntilEntriesAreNeeded() throws IOException {
        Path archive = temporaryDirectory.resolve("missing.zip");
        assertFalse(Files.exists(archive));

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertSame(ZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertSame(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            assertTrue(fileSystem.isOpen());
            assertTrue(fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(fileSystem.getPath("/"), roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertEquals("zip", store.name());
            assertEquals("zip", store.type());
            assertTrue(store.isReadOnly());

            assertTrue(fileSystem.supportedFileAttributeViews().contains("zip"));
            assertTrue(fileSystem.supportedFileAttributeViews().contains("owner"));
            assertTrue(fileSystem.supportedFileAttributeViews().contains("posix"));
            assertFalse(Files.exists(archive));
        }
    }

    /// Verifies ZIP file systems expose synthesized owner and group principal lookup before archive parsing.
    @Test
    void providesSyntheticPrincipalLookup() throws IOException {
        Path archive = temporaryDirectory.resolve("missing.zip");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            UserPrincipalLookupService lookupService = fileSystem.getUserPrincipalLookupService();
            UserPrincipal owner = lookupService.lookupPrincipalByName("owner");
            GroupPrincipal group = lookupService.lookupPrincipalByGroupName("group");

            assertEquals("owner", owner.getName());
            assertEquals("group", group.getName());
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByName("missing")
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByGroupName("missing")
            );
        }
    }
}
