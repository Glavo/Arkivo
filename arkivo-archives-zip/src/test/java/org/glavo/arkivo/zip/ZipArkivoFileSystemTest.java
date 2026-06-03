// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoStorageAccess;
import org.glavo.arkivo.ArkivoStorageAccessSet;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic ZIP Arkivo file system behavior.
@NotNullByDefault
public final class ZipArkivoFileSystemTest {
    /// Verifies that a ZIP file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = Path.of("sample.zip");
        Map<String, Object> environment = Map.of(ArkivoFileSystem.STORAGE_ACCESS.key(), "random-read,stream-read");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, environment)) {
            assertEquals(archivePath, fileSystem.archivePath());
            assertEquals(null, fileSystem.volumes());
            assertEquals(ZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertEquals(ArkivoStorageAccessSet.of(
                    ArkivoStorageAccess.RANDOM_READ,
                    ArkivoStorageAccess.STREAM_READ
            ), fileSystem.storageAccess());
            assertEquals(true, fileSystem.isOpen());
            assertEquals(true, fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("zip"));
        }
    }

    /// Verifies that write-capable storage access makes the file system writable.
    @Test
    public void writableStorageAccess() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                Path.of("sample.zip"),
                Map.of(ArkivoFileSystem.STORAGE_ACCESS.key(), "stream-write")
        )) {
            assertEquals(false, fileSystem.isReadOnly());
        }
    }

    /// Verifies basic ZIP path operations.
    @Test
    public void paths() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            Path path = fileSystem.getPath("/a/b/../c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals("/a/c.txt", path.normalize().toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
        }
    }

    /// Verifies that closing a ZIP file system closes an owned volume source.
    @Test
    public void closeVolumeSource() throws IOException {
        TestVolumeSource volumes = new TestVolumeSource();
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes);

        fileSystem.close();

        assertEquals(false, fileSystem.isOpen());
        assertEquals(true, volumes.closed);
        assertThrows(IllegalStateException.class, () -> fileSystem.getPath("/"));
    }

    /// Test volume source that records close calls.
    @NotNullByDefault
    private static final class TestVolumeSource implements ArkivoVolumeSource {
        /// Whether this source has been closed.
        private boolean closed;

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closed = true;
        }
    }
}
