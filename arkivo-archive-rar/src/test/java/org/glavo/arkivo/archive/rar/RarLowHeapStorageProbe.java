// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32;

/// Verifies that a RAR file system lazily caches a large stored entry outside the Java heap.
@NotNullByDefault
public final class RarLowHeapStorageProbe {
    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The stored entry size, deliberately larger than this probe's configured maximum heap.
    private static final long ENTRY_SIZE = 64L * 1024L * 1024L;

    /// The bounded source and CRC buffer size.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// Prevents instantiation of this probe.
    private RarLowHeapStorageProbe() {
    }

    /// Creates, indexes, reads, and cleans up one stored entry larger than the process heap.
    public static void main(String[] arguments) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "rar-low-heap-");
        Path archivePath = directory.resolve("large.rar");
        Path storageDirectory = directory.resolve("storage");
        try {
            createArchive(archivePath);
            verifyArchive(archivePath, storageDirectory);
            verifyStorageCleanup(storageDirectory);
        } finally {
            Files.deleteIfExists(archivePath);
            Files.deleteIfExists(storageDirectory);
            Files.deleteIfExists(directory);
        }
    }

    /// Creates a RAR4 archive containing one large stored file using bounded memory.
    private static void createArchive(Path archivePath) throws IOException {
        byte[] zeros = new byte[BUFFER_SIZE];
        CRC32 bodyCrc = new CRC32();
        long remaining = ENTRY_SIZE;
        while (remaining > 0L) {
            int count = (int) Math.min(remaining, zeros.length);
            bodyCrc.update(zeros, 0, count);
            remaining -= count;
        }

        byte[] name = "large.bin".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, ENTRY_SIZE);
        writeUInt32(fields, ENTRY_SIZE);
        fields.write(3);
        writeUInt32(fields, bodyCrc.getValue());
        writeUInt32(fields, 0L);
        fields.write(29);
        fields.write(0x30);
        writeUInt16(fields, name.length);
        writeUInt32(fields, 0100644L);
        fields.write(name);

        try (OutputStream output = Files.newOutputStream(archivePath)) {
            output.write(RAR4_SIGNATURE);
            writeBlockHeader(output, 0x73, 0L, new byte[6]);
            writeBlockHeader(output, 0x74, 0x8000L, fields.toByteArray());
            remaining = ENTRY_SIZE;
            while (remaining > 0L) {
                int count = (int) Math.min(remaining, zeros.length);
                output.write(zeros, 0, count);
                remaining -= count;
            }
            writeBlockHeader(output, 0x7b, 0L, new byte[0]);
        }
    }

    /// Opens the archive with explicit temporary storage and verifies random access to the large entry.
    private static void verifyArchive(Path archivePath, Path storageDirectory) throws IOException {
        RarArchiveOptions.Read options = RarArchiveOptions.READ_DEFAULTS.withCommon(
                ArchiveReadOptions.DEFAULT.withEditStorage(ArkivoEditStorage.temporaryFiles(storageDirectory))
        );
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath, options)) {
            Path entry = fileSystem.getPath("/large.bin");
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            if (attributes.size() != ENTRY_SIZE) {
                throw new AssertionError("Unexpected large RAR entry size: " + attributes.size());
            }
            requireStorageFileCount(storageDirectory, 0);
            try (SeekableByteChannel channel = Files.newByteChannel(entry)) {
                requireStorageFileCount(storageDirectory, 1);
                channel.position(ENTRY_SIZE - 1L);
                ByteBuffer value = ByteBuffer.allocate(1);
                if (channel.read(value) != 1 || value.array()[0] != 0) {
                    throw new AssertionError("Unexpected large RAR entry tail byte");
                }
            }
        }
    }

    /// Requires temporary storage to contain exactly the expected number of files.
    private static void requireStorageFileCount(Path storageDirectory, int expected) throws IOException {
        if (!Files.exists(storageDirectory)) {
            if (expected == 0) {
                return;
            }
            throw new AssertionError("RAR cached content storage directory is missing");
        }
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory)) {
            for (Path ignored : files) {
                count++;
            }
        }
        if (count != expected) {
            throw new AssertionError("Unexpected RAR cached content file count: " + count);
        }
    }

    /// Verifies that cached content storage leaves no temporary files after close.
    private static void verifyStorageCleanup(Path storageDirectory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory)) {
            if (files.iterator().hasNext()) {
                throw new AssertionError("RAR cached content storage retained temporary content");
            }
        }
    }

    /// Writes one RAR4 block header with its low 16-bit CRC32 checksum.
    private static void writeBlockHeader(OutputStream output, int type, long flags, byte @Unmodifiable [] fields)
            throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(type);
        writeUInt16(header, flags);
        writeUInt16(header, 7L + fields.length);
        header.write(fields);
        byte[] bytes = header.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        writeUInt16(output, crc32.getValue());
        output.write(bytes);
    }

    /// Writes an unsigned 16-bit little-endian integer.
    private static void writeUInt16(OutputStream output, long value) throws IOException {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    /// Writes an unsigned 32-bit little-endian integer.
    private static void writeUInt32(OutputStream output, long value) throws IOException {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }
}
