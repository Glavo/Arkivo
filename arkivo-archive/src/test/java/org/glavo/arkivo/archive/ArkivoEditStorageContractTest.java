// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the channel, ownership, and backing-selection contracts of built-in edit storage.
@NotNullByDefault
public final class ArkivoEditStorageContractTest {
    /// Temporary storage used by file-backed strategy tests.
    @TempDir
    public Path temporaryDirectory;

    /// Verifies memory channels enforce access modes and maintain independent positions over shared content.
    @Test
    public void memoryChannelsHonorAccessModesAndIndependentPositions() throws IOException {
        byte[] content = new byte[]{1, 2, 3, 4};

        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent stored = storage.createContent("entry.bin", content.length)) {
            try (SeekableByteChannel writer = stored.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ))) {
                ByteBuffer source = ByteBuffer.wrap(content);
                assertEquals(content.length, writer.write(source));
                assertEquals(source.limit(), source.position());
                assertThrows(NonReadableChannelException.class, () -> writer.read(ByteBuffer.allocate(1)));
            }

            try (SeekableByteChannel first = stored.openChannel(Set.of());
                 SeekableByteChannel second = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                assertThrows(NonWritableChannelException.class, () -> first.write(ByteBuffer.wrap(new byte[]{9})));

                ByteBuffer prefix = ByteBuffer.allocate(2);
                assertEquals(2, first.read(prefix));
                assertEquals(2L, first.position());
                assertEquals(0L, second.position());

                ByteBuffer complete = ByteBuffer.allocate(content.length);
                assertEquals(content.length, second.read(complete));
                assertArrayEquals(content, complete.array());
            }
        }
    }

    /// Verifies closing storage or a content handle does not invalidate channels that already retain the bytes.
    @Test
    public void openMemoryChannelsOutliveTheirOwners() throws IOException {
        byte[] content = new byte[]{5, 6, 7};
        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent stored = storage.createContent(
                     "entry.bin",
                     ArkivoEditStorage.UNKNOWN_SIZE
             )) {
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                assertEquals(content.length, writer.write(ByteBuffer.wrap(content)));
            }
            try (SeekableByteChannel reader = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                storage.close();
                assertEquals(content.length, stored.size());
                stored.close();

                assertThrows(IOException.class, stored::size);
                assertThrows(
                        IOException.class,
                        () -> stored.openChannel(Set.of(StandardOpenOption.READ))
                );
                assertArrayEquals(content, readRemaining(reader));
            }
        }
    }

    /// Verifies memory channels implement truncation, large-position rejection, and closed-state checks.
    @Test
    public void memoryChannelsEnforceSeekAndTruncateBoundaries() throws IOException {
        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent stored = storage.createContent("entry.bin", 4L)) {
            SeekableByteChannel channel = stored.openChannel(Set.of(
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            ));
            assertEquals(4, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
            channel.position(4L);
            assertSame(channel, channel.truncate(2L));
            assertEquals(2L, channel.size());
            assertEquals(2L, channel.position());

            assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
            assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));

            ByteBuffer rejected = ByteBuffer.wrap(new byte[]{9});
            channel.position(Integer.MAX_VALUE);
            IOException overflow = assertThrows(IOException.class, () -> channel.write(rejected));
            assertEquals("Memory stored content exceeds the maximum array size", overflow.getMessage());
            assertEquals(0, rejected.position());
            assertEquals(2L, channel.size());

            channel.close();
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, channel::position);
            assertThrows(ClosedChannelException.class, channel::size);
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.truncate(0L));
            channel.close();
        }
    }

    /// Verifies temporary-file storage creates its directory, persists bytes, and removes closed content.
    @Test
    public void temporaryFileContentSupportsRoundTripsAndCleanup() throws IOException {
        Path directory = temporaryDirectory.resolve("temporary-content");

        try (ArkivoEditStorage storage = ArkivoEditStorage.temporaryFiles(directory)) {
            ArkivoStoredContent stored = storage.createContent("entry.bin", 3L);
            try {
                assertTrue(Files.isDirectory(directory));
                assertEquals(1L, regularFileCount(directory));

                try (SeekableByteChannel channel = stored.openChannel(Set.of(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                ))) {
                    assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{7, 8, 9})));
                }
                assertEquals(3L, stored.size());
                try (SeekableByteChannel channel = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                    assertArrayEquals(new byte[]{7, 8, 9}, readRemaining(channel));
                }
            } finally {
                stored.close();
            }

            assertEquals(0L, regularFileCount(directory));
            assertThrows(IOException.class, stored::size);
            assertThrows(IOException.class, () -> stored.openChannel(Set.of(StandardOpenOption.READ)));
            stored.close();
        }
    }

    /// Verifies hybrid storage selects memory at the threshold and files above it or for unknown sizes.
    @Test
    public void hybridStorageSelectsBackingFromExpectedSize() throws IOException {
        Path directory = temporaryDirectory.resolve("hybrid-content");
        assertThrows(IllegalArgumentException.class, () -> ArkivoEditStorage.hybrid(-1L, directory));

        try (ArkivoEditStorage storage = ArkivoEditStorage.hybrid(4L, directory)) {
            try (ArkivoStoredContent memory = storage.createContent("small.bin", 4L)) {
                assertFalse(Files.exists(directory));
            }

            try (ArkivoStoredContent large = storage.createContent("large.bin", 5L)) {
                assertEquals(1L, regularFileCount(directory));
                try (ArkivoStoredContent unknown = storage.createContent(
                        "unknown.bin",
                        ArkivoEditStorage.UNKNOWN_SIZE
                )) {
                    assertEquals(2L, regularFileCount(directory));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> storage.createContent("invalid.bin", ArkivoEditStorage.UNKNOWN_SIZE - 1L)
                    );
                    assertEquals(2L, regularFileCount(directory));
                }
                assertEquals(1L, regularFileCount(directory));
            }
            assertEquals(0L, regularFileCount(directory));
        }
    }

    /// Verifies storage factories validate configuration and create independent usable storage instances.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void factoriesCreateIndependentConfiguredStorage() throws IOException {
        ArkivoEditStorageFactory memoryFactory = ArkivoEditStorageFactory.memory();
        try (ArkivoEditStorage first = memoryFactory.open();
             ArkivoEditStorage second = memoryFactory.open()) {
            assertNotSame(first, second);
            first.close();
            try (ArkivoStoredContent content = second.createContent("memory.bin", 1L);
                 SeekableByteChannel channel = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{1})));
            }
        }

        Path fileDirectory = temporaryDirectory.resolve("factory-files");
        ArkivoEditStorageFactory fileFactory = ArkivoEditStorageFactory.temporaryFiles(fileDirectory);
        try (ArkivoEditStorage storage = fileFactory.open();
             ArkivoStoredContent content = storage.createContent("file.bin", 1L)) {
            assertTrue(Files.isDirectory(fileDirectory));
            assertEquals(1L, regularFileCount(fileDirectory));
        }
        assertEquals(0L, regularFileCount(fileDirectory));

        Path hybridDirectory = temporaryDirectory.resolve("factory-hybrid");
        ArkivoEditStorageFactory hybridFactory = ArkivoEditStorageFactory.hybrid(1L, hybridDirectory);
        try (ArkivoEditStorage storage = hybridFactory.open();
             ArkivoStoredContent content = storage.createContent("large.bin", 2L)) {
            assertEquals(1L, regularFileCount(hybridDirectory));
        }
        assertEquals(0L, regularFileCount(hybridDirectory));

        assertThrows(NullPointerException.class, () -> ArkivoEditStorageFactory.temporaryFiles(null));
        assertThrows(IllegalArgumentException.class, () -> ArkivoEditStorageFactory.hybrid(-1L, hybridDirectory));
        assertThrows(NullPointerException.class, () -> ArkivoEditStorageFactory.hybrid(0L, null));
    }

    /// Reads all bytes from a channel's current position to its logical end.
    private static byte[] readRemaining(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size() - channel.position()));
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("Test channel made no progress");
            }
        }
        return buffer.array();
    }

    /// Counts regular files directly contained by a staging directory.
    private static long regularFileCount(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).count();
        }
    }
}
