// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;

/// Verifies that complete 7z updates stream entry bodies larger than the available Java heap.
@NotNullByDefault
public final class SevenZipLowHeapUpdateProbe {
    /// The entry size, deliberately larger than this probe's configured maximum heap.
    private static final long ENTRY_SIZE = 64L * 1024L * 1024L;

    /// The bounded buffer used to generate the source entry.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// The byte written near the end of the entry during the update.
    private static final byte UPDATED_BYTE = 0x5a;

    /// Prevents instantiation of this probe.
    private SevenZipLowHeapUpdateProbe() {
    }

    /// Creates, updates, and verifies one entry larger than the process heap.
    public static void main(String[] arguments) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "sevenzip-low-heap-");
        Path archivePath = directory.resolve("large.7z");
        Path storageDirectory = directory.resolve("storage");
        try {
            createArchive(archivePath);
            updateArchive(archivePath, storageDirectory);
            verifyArchive(archivePath);
            verifyStorageCleanup(storageDirectory);
        } finally {
            Files.deleteIfExists(archivePath);
            Files.deleteIfExists(storageDirectory);
            Files.deleteIfExists(directory);
        }
    }

    /// Creates one uncompressed 7z entry without retaining its complete body in memory.
    private static void createArchive(Path archivePath) throws IOException {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );
        byte[] buffer = new byte[BUFFER_SIZE];
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath, environment);
             OutputStream output = Files.newOutputStream(fileSystem.getPath("/large.bin"))) {
            long remaining = ENTRY_SIZE;
            while (remaining > 0L) {
                int count = (int) Math.min(remaining, buffer.length);
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    /// Randomly modifies the large entry through temporary-file edit storage and commits a complete rewrite.
    private static void updateArchive(Path archivePath, Path storageDirectory) throws IOException {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.EDIT_STORAGE.key(),
                ArkivoEditStorage.temporaryFiles(storageDirectory)
        );
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath, environment);
             SeekableByteChannel channel = Files.newByteChannel(
                     fileSystem.getPath("/large.bin"),
                     Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
             )) {
            channel.position(ENTRY_SIZE - 1L);
            int count = channel.write(ByteBuffer.wrap(new byte[]{UPDATED_BYTE}));
            if (count != 1 || channel.size() != ENTRY_SIZE) {
                throw new AssertionError("Unexpected large-entry update result");
            }
        }
    }

    /// Reopens the committed archive and verifies its size and modified tail byte.
    private static void verifyArchive(Path archivePath) throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
            Path entry = fileSystem.getPath("/large.bin");
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            if (attributes.size() != ENTRY_SIZE) {
                throw new AssertionError("Unexpected large-entry size: " + attributes.size());
            }
            try (SeekableByteChannel channel = Files.newByteChannel(entry)) {
                channel.position(ENTRY_SIZE - 1L);
                ByteBuffer value = ByteBuffer.allocate(1);
                if (channel.read(value) != 1 || value.array()[0] != UPDATED_BYTE) {
                    throw new AssertionError("Large-entry update byte was not preserved");
                }
            }
        }
    }

    /// Verifies that update storage leaves no temporary content files after commit.
    private static void verifyStorageCleanup(Path storageDirectory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory)) {
            if (files.iterator().hasNext()) {
                throw new AssertionError("7z update storage retained temporary content");
            }
        }
    }
}
