// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar.internal;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.tar.TarArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;

/// Verifies that indexed TAR reads and complete updates keep large entry bodies outside the Java heap.
@NotNullByDefault
public final class TarLowHeapStorageProbe {
    /// The entry size, deliberately larger than this probe's configured maximum heap.
    private static final long ENTRY_SIZE = 64L * 1024L * 1024L;

    /// The byte written near the end of the entry during the update.
    private static final byte UPDATED_BYTE = 0x5a;

    /// Prevents instantiation of this probe.
    private TarLowHeapStorageProbe() {
    }

    /// Creates, indexes, updates, and verifies one entry larger than the process heap.
    public static void main(String[] arguments) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "tar-low-heap-");
        Path archivePath = directory.resolve("large.tar");
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

    /// Creates one TAR entry from a bounded zero-producing channel.
    private static void createArchive(Path archivePath) throws IOException {
        TarEntryAttributes attributes = new TarEntryAttributes(
                "large.bin",
                TarEntryAttributes.REGULAR_TYPE,
                0644,
                0L,
                0L,
                null,
                null,
                null,
                ENTRY_SIZE,
                FileTime.fromMillis(0L),
                FileTime.fromMillis(0L),
                FileTime.fromMillis(0L)
        );
        try (OutputStream output = Files.newOutputStream(archivePath);
             TarArkivoStreamingWriterImpl writer = new TarArkivoStreamingWriterImpl(output);
             ZeroReadableByteChannel body = new ZeroReadableByteChannel(ENTRY_SIZE)) {
            writer.writeSnapshot(attributes, body, ENTRY_SIZE);
        }
    }

    /// Randomly modifies the large entry through temporary-file storage and commits a complete rewrite.
    private static void updateArchive(Path archivePath, Path storageDirectory) throws IOException {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.EDIT_STORAGE.key(),
                ArkivoEditStorage.temporaryFiles(storageDirectory)
        );
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, environment);
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
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
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

    /// Verifies that indexed content storage leaves no temporary files after close.
    private static void verifyStorageCleanup(Path storageDirectory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory)) {
            if (files.iterator().hasNext()) {
                throw new AssertionError("TAR indexed content storage retained temporary content");
            }
        }
    }

    /// Produces a fixed number of zero bytes without allocating the complete body.
    @NotNullByDefault
    private static final class ZeroReadableByteChannel implements ReadableByteChannel {
        /// The bounded source block.
        private final byte[] zeros = new byte[64 * 1024];

        /// The number of bytes still available.
        private long remaining;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a zero-producing channel with the given size.
        private ZeroReadableByteChannel(long size) {
            this.remaining = size;
        }

        /// Reads zero bytes into the destination.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (!open) {
                throw new IOException("Zero channel is closed");
            }
            if (remaining == 0L) {
                return -1;
            }
            int count = (int) Math.min(Math.min(remaining, destination.remaining()), zeros.length);
            destination.put(zeros, 0, count);
            remaining -= count;
            return count;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }
}
