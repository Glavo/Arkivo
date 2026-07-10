// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.ar.ArArkivoFileSystem;
import org.glavo.arkivo.ar.ArArkivoStreamingWriter;
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
import java.util.Objects;
import java.util.Set;

/// Verifies that indexed AR reads and complete updates keep large member bodies outside the Java heap.
@NotNullByDefault
public final class ArLowHeapStorageProbe {
    /// The source member size, deliberately larger than this probe's configured maximum heap.
    private static final long ENTRY_SIZE = 64L * 1024L * 1024L;

    /// The expanded member size used to verify zero-filled growth.
    private static final long UPDATED_SIZE = ENTRY_SIZE + 1024L;

    /// The bounded buffer used to generate the source member.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// The byte written near the end of the original member during the update.
    private static final byte UPDATED_BYTE = 0x5a;

    /// Prevents instantiation of this probe.
    private ArLowHeapStorageProbe() {
    }

    /// Creates, indexes, resizes, updates, and verifies one member larger than the process heap.
    public static void main(String[] arguments) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "ar-low-heap-");
        Path archivePath = directory.resolve("large.a");
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

    /// Creates one fixed-size AR member without retaining its complete body in memory.
    private static void createArchive(Path archivePath) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
            writer.beginFile("large.bin");
            ArArkivoEntryAttributeView attributes = Objects.requireNonNull(
                    writer.attributeView(ArArkivoEntryAttributeView.class)
            );
            attributes.setSize(ENTRY_SIZE);
            try (OutputStream output = writer.openOutputStream()) {
                long remaining = ENTRY_SIZE;
                while (remaining > 0L) {
                    int count = (int) Math.min(remaining, buffer.length);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }

    /// Expands and randomly modifies the large member through temporary-file indexed storage.
    private static void updateArchive(Path archivePath, Path storageDirectory) throws IOException {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.EDIT_STORAGE.key(),
                ArkivoEditStorage.temporaryFiles(storageDirectory)
        );
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment)) {
            Path entry = fileSystem.getPath("/large.bin");
            ArArkivoEntryAttributeView attributes = Objects.requireNonNull(
                    Files.getFileAttributeView(entry, ArArkivoEntryAttributeView.class)
            );
            attributes.setSize(UPDATED_SIZE);
            try (SeekableByteChannel channel = Files.newByteChannel(
                    entry,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            )) {
                channel.position(ENTRY_SIZE - 1L);
                int count = channel.write(ByteBuffer.wrap(new byte[]{UPDATED_BYTE}));
                if (count != 1 || channel.size() != UPDATED_SIZE) {
                    throw new AssertionError("Unexpected large-member update result");
                }
                channel.position(UPDATED_SIZE - 1L);
                ByteBuffer value = ByteBuffer.allocate(1);
                if (channel.read(value) != 1 || value.array()[0] != 0) {
                    throw new AssertionError("Expanded AR member region was not zero-filled");
                }
            }
        }
    }

    /// Reopens the committed archive and verifies its size, modified byte, and zero-filled tail.
    private static void verifyArchive(Path archivePath) throws IOException {
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath)) {
            Path entry = fileSystem.getPath("/large.bin");
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            if (attributes.size() != UPDATED_SIZE) {
                throw new AssertionError("Unexpected large-member size: " + attributes.size());
            }
            try (SeekableByteChannel channel = Files.newByteChannel(entry)) {
                channel.position(ENTRY_SIZE - 1L);
                ByteBuffer values = ByteBuffer.allocate(2);
                if (channel.read(values) != 2
                        || values.array()[0] != UPDATED_BYTE
                        || values.array()[1] != 0) {
                    throw new AssertionError("Large-member update bytes were not preserved");
                }
            }
        }
    }

    /// Verifies that indexed content storage leaves no temporary files after close.
    private static void verifyStorageCleanup(Path storageDirectory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageDirectory)) {
            if (files.iterator().hasNext()) {
                throw new AssertionError("AR indexed content storage retained temporary content");
            }
        }
    }
}
