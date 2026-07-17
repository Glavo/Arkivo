// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies storage and lifecycle behavior for decoded seekable ZIP entry channels.
@NotNullByDefault
final class ZipDecodedEntryStorageTest {
    /// Verifies compressed channels use configured storage while direct stored channels bypass it.
    @Test
    void stagesCompressedChannelsAndDefersStorageClose(@TempDir Path directory) throws IOException {
        byte[] compressedBytes = "compressed decoded entry".repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] storedBytes = "stored entry".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("storage.zip");
        createArchive(archive, compressedBytes, storedBytes);
        TrackingStorage storage = new TrackingStorage(directory.resolve("staging"), false);
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive, readOptions(storage));

        try (SeekableByteChannel compressed = Files.newByteChannel(fileSystem.getPath("/compressed.bin"))) {
            assertEquals(1, storage.createCount);
            assertEquals(compressedBytes.length, storage.lastExpectedSize);
            compressed.position(57L);
            ByteBuffer actual = ByteBuffer.allocate(91);
            assertEquals(91, compressed.read(actual));
            assertArrayEquals(
                    java.util.Arrays.copyOfRange(compressedBytes, 57, 148),
                    actual.array()
            );
        }
        assertEquals(1, storage.contentCloseCount);

        try (SeekableByteChannel stored = Files.newByteChannel(fileSystem.getPath("/stored.bin"))) {
            assertEquals(1, storage.createCount);
            assertEquals(storedBytes.length, stored.size());
        }

        SeekableByteChannel deferred = Files.newByteChannel(fileSystem.getPath("/compressed.bin"));
        assertEquals(2, storage.createCount);
        fileSystem.close();
        assertEquals(0, storage.storageCloseCount);
        deferred.close();
        assertEquals(2, storage.contentCloseCount);
        assertEquals(1, storage.storageCloseCount);
    }

    /// Verifies failed decoded-content cleanup is retained and retried during file system close.
    @Test
    void retriesDecodedContentCleanup(@TempDir Path directory) throws IOException {
        byte[] compressedBytes = "retry decoded entry".repeat(32).getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("retry.zip");
        createArchive(archive, compressedBytes, new byte[]{1, 2, 3});
        TrackingStorage storage = new TrackingStorage(directory.resolve("retry-staging"), true);
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive, readOptions(storage));
        SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/compressed.bin"));

        assertThrows(IOException.class, channel::close);
        assertEquals(1, storage.contentCloseAttempts);
        assertEquals(0, storage.storageCloseCount);

        fileSystem.close();
        assertEquals(2, storage.contentCloseAttempts);
        assertEquals(1, storage.contentCloseCount);
        assertEquals(1, storage.storageCloseCount);
    }

    /// Creates a ZIP with one Deflate entry and one directly addressable stored entry.
    private static void createArchive(Path archive, byte[] compressedBytes, byte[] storedBytes) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("compressed.bin"));
            output.write(compressedBytes);
            output.closeEntry();

            CRC32 crc32 = new CRC32();
            crc32.update(storedBytes);
            ZipEntry stored = new ZipEntry("stored.bin");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(storedBytes.length);
            stored.setCompressedSize(storedBytes.length);
            stored.setCrc(crc32.getValue());
            output.putNextEntry(stored);
            output.write(storedBytes);
            output.closeEntry();
        }
    }

    /// Returns typed ZIP read options using the given decoded-entry storage.
    private static ZipArchiveOptions.Read readOptions(ArkivoEditStorage storage) {
        return new ZipArchiveOptions.Read(
                ArchiveReadOptions.DEFAULT.withEditStorage(storage),
                null,
                ZipArchiveOptions.DEFAULT_LEGACY_CHARSET_DETECTOR
        );
    }

    /// Records storage allocation and close behavior while delegating bytes to temporary files.
    @NotNullByDefault
    private static final class TrackingStorage implements ArkivoEditStorage {
        /// The temporary-file storage delegate.
        private final ArkivoEditStorage delegate;

        /// Whether the first content close should fail.
        private final boolean failFirstContentClose;

        /// The number of allocated content objects.
        private int createCount;

        /// The last expected decoded size.
        private long lastExpectedSize = UNKNOWN_SIZE;

        /// The number of content close attempts.
        private int contentCloseAttempts;

        /// The number of successful content closes.
        private int contentCloseCount;

        /// The number of storage close calls.
        private int storageCloseCount;

        /// Creates tracking storage backed by temporary files.
        private TrackingStorage(Path directory, boolean failFirstContentClose) {
            this.delegate = ArkivoEditStorage.temporaryFiles(directory);
            this.failFirstContentClose = failFirstContentClose;
        }

        /// Allocates one tracked content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createCount++;
            lastExpectedSize = expectedSize;
            return new TrackingContent(delegate.createContent(path, expectedSize), this);
        }

        /// Closes the delegated storage.
        @Override
        public void close() throws IOException {
            storageCloseCount++;
            delegate.close();
        }
    }

    /// Tracks one decoded content body and optionally fails its first close attempt.
    @NotNullByDefault
    private static final class TrackingContent implements ArkivoStoredContent {
        /// The stored-content delegate.
        private final ArkivoStoredContent delegate;

        /// The storage receiving lifecycle counters.
        private final TrackingStorage storage;

        /// Creates tracked stored content.
        private TrackingContent(ArkivoStoredContent delegate, TrackingStorage storage) {
            this.delegate = delegate;
            this.storage = storage;
        }

        /// Opens a delegated channel.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return delegate.openChannel(options);
        }

        /// Returns the delegated content size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Closes the content or raises the configured first-attempt failure.
        @Override
        public void close() throws IOException {
            storage.contentCloseAttempts++;
            if (storage.failFirstContentClose && storage.contentCloseAttempts == 1) {
                throw new IOException("Injected decoded content close failure");
            }
            delegate.close();
            storage.contentCloseCount++;
        }
    }
}
