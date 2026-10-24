// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
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

    /// Reads, writes, and deletes the original temporary file after its parent link changes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void retainsTemporaryContentAfterParentLinkChanges(boolean hybrid) throws IOException {
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        Path alias = Files.createSymbolicLink(temporaryDirectory.resolve("alias"), first);
        Path staged;
        Path unrelated;
        try (ArkivoEditStorage storage = hybrid ? ArkivoEditStorage.hybrid(128, alias)
                : ArkivoEditStorage.temporaryFiles(alias);
             ArkivoStoredContent content = storage.createContent("entry", ArkivoEditStorage.UNKNOWN_SIZE)) {
            try (var writer = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            }
            try (var files = Files.list(first)) {
                staged = files.toList().get(0);
            }
            unrelated = Files.write(second.resolve(staged.getFileName()), new byte[]{4, 5});
            Files.delete(alias);
            Files.createSymbolicLink(alias, second);
            assertEquals(3, content.size());
            try (var reader = content.openChannel(Set.of(StandardOpenOption.READ))) {
                assertArrayEquals(new byte[]{1, 2, 3}, readRemaining(reader));
            }
            try (var writer = content.openChannel(Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                writer.write(ByteBuffer.wrap(new byte[]{6}));
            }
            assertArrayEquals(new byte[]{6}, Files.readAllBytes(staged));
        }
        assertFalse(Files.exists(staged));
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(unrelated));
    }

    /// Rejects read-only destinations consistently for memory and temporary-file content.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void rejectsReadOnlyDestinations(boolean temporaryFiles) throws IOException {
        try (ArkivoEditStorage storage = openStorage(temporaryFiles);
             ArkivoStoredContent stored = storage.createContent("entry.bin", 3)) {
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            }
            try (SeekableByteChannel reader = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                for (long position : new long[]{0L, 3L, 8L}) {
                    reader.position(position);
                    for (int size : new int[]{0, 8}) {
                        ByteBuffer target = ByteBuffer.allocateDirect(size).asReadOnlyBuffer();
                        assertThrows(IllegalArgumentException.class, () -> reader.read(target));
                        assertEquals(position, reader.position());
                        assertEquals(0, target.position());
                    }
                }
                reader.position(0L);
                ByteBuffer result = ByteBuffer.allocate(3);
                while (result.hasRemaining()) {
                    assertTrue(reader.read(result) > 0);
                }
                assertArrayEquals(new byte[]{1, 2, 3}, result.array());
            }
        }
    }

    /// Verifies creation and truncation options alone never enable writes or discard retained bytes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void readOnlyOptionsPreserveContent(boolean temporaryFiles) throws IOException {
        try (ArkivoEditStorage storage = openStorage(temporaryFiles);
             ArkivoStoredContent stored = storage.createContent("entry.bin", 3)) {
            byte[] expected = {1, 2, 3};
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(expected));
            }
            for (Set<StandardOpenOption> options : List.of(
                    Set.of(StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING),
                    Set.of(StandardOpenOption.TRUNCATE_EXISTING),
                    Set.of(StandardOpenOption.CREATE),
                    Set.of(StandardOpenOption.CREATE_NEW))) {
                try (SeekableByteChannel reader = stored.openChannel(options)) {
                    assertEquals(3, stored.size(), options.toString());
                    assertArrayEquals(expected, readRemaining(reader));
                    assertThrows(NonWritableChannelException.class,
                            () -> reader.write(ByteBuffer.wrap(new byte[]{9})));
                    assertThrows(NonWritableChannelException.class, () -> reader.truncate(0));
                }
            }
        }
    }

    /// Verifies append opens start at the end and each write uses the shared content's latest end.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void appendUsesCurrentEndAfterPositionChanges(boolean temporaryFiles) throws IOException {
        try (ArkivoEditStorage storage = openStorage(temporaryFiles);
             ArkivoStoredContent stored = storage.createContent("entry.bin", 3)) {
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(new byte[]{1}));
            }
            try (SeekableByteChannel first = stored.openChannel(Set.of(StandardOpenOption.APPEND));
                 SeekableByteChannel second = stored.openChannel(Set.of(StandardOpenOption.APPEND))) {
                assertEquals(1, first.position());
                assertThrows(NonReadableChannelException.class, () -> first.read(ByteBuffer.allocate(1)));
                first.position(0).write(ByteBuffer.wrap(new byte[]{2}));
                second.position(0).write(ByteBuffer.wrap(new byte[]{3}));
                assertEquals(3, second.position());
            }
            try (SeekableByteChannel reader = stored.openChannel(Set.of())) {
                assertArrayEquals(new byte[]{1, 2, 3}, readRemaining(reader));
            }
        }
    }

    /// Verifies conflicting append options fail before truncating existing content.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void invalidAppendOptionsDoNotChangeContent(boolean temporaryFiles) throws IOException {
        try (ArkivoEditStorage storage = openStorage(temporaryFiles);
             ArkivoStoredContent stored = storage.createContent("entry.bin", 1)) {
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(new byte[]{1}));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> stored.openChannel(Set.of(StandardOpenOption.READ, StandardOpenOption.APPEND)));
            assertThrows(IllegalArgumentException.class, () -> stored.openChannel(Set.of(
                    StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)));
            assertEquals(1, stored.size());
        }
    }

    /// Creates one backend for common stored-content channel assertions.
    private ArkivoEditStorage openStorage(boolean temporaryFiles) {
        return temporaryFiles ? ArkivoEditStorage.temporaryFiles(temporaryDirectory) : ArkivoEditStorage.memory();
    }

    /// Deletes the content handle without invalidating readers opened before the deleting channel closes.
    @ParameterizedTest
    @ValueSource(strings = {"memory", "file", "hybrid-memory", "hybrid-file"})
    public void deleteOnClosePreservesExistingReaders(String backend) throws IOException {
        try (ArkivoEditStorage storage = switch (backend) {
            case "memory" -> ArkivoEditStorage.memory();
            case "file" -> ArkivoEditStorage.temporaryFiles(temporaryDirectory);
            case "hybrid-memory" -> ArkivoEditStorage.hybrid(3, temporaryDirectory);
            case "hybrid-file" -> ArkivoEditStorage.hybrid(0, temporaryDirectory);
            default -> throw new AssertionError(backend);
        }; ArkivoStoredContent stored = storage.createContent("entry.bin", 3)) {
            byte[] expected = {1, 2, 3};
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(expected));
            }
            try (SeekableByteChannel reader = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                try (SeekableByteChannel deleting = stored.openChannel(Set.of(
                        StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE))) {
                    assertArrayEquals(expected, readRemaining(deleting));
                    deleting.close();
                    deleting.close();
                }
                assertArrayEquals(expected, readRemaining(reader));
                assertThrows(IOException.class, () -> {
                    try (SeekableByteChannel ignored = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                    }
                });
            }
            assertThrows(IOException.class, stored::size);
            stored.close();
            stored.close();
        }
    }

    /// Rejects unknown and null options before applying truncation to retained content.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("DataFlowIssue")
    public void invalidOpenOptionsPreserveContent(boolean temporaryFiles) throws IOException {
        try (ArkivoEditStorage storage = openStorage(temporaryFiles);
             ArkivoStoredContent stored = storage.createContent("entry.bin", 3)) {
            byte[] expected = {1, 2, 3};
            try (SeekableByteChannel writer = stored.openChannel(Set.of(StandardOpenOption.WRITE))) {
                writer.write(ByteBuffer.wrap(expected));
            }
            Set<@Nullable OpenOption> options = new HashSet<>(Set.of(
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            options.add(UnknownOpenOption.INSTANCE);
            assertThrows(UnsupportedOperationException.class, () -> {
                try (SeekableByteChannel ignored = stored.openChannel(options)) {
                    // Closing an unexpectedly accepted channel keeps the test resource-safe.
                }
            });
            options.remove(UnknownOpenOption.INSTANCE);
            options.add(null);
            assertThrows(NullPointerException.class, () -> {
                try (SeekableByteChannel ignored = stored.openChannel(options)) {
                    // Closing an unexpectedly accepted channel keeps the test resource-safe.
                }
            });
            try (SeekableByteChannel reader = stored.openChannel(Set.of(LinkOption.NOFOLLOW_LINKS))) {
                assertArrayEquals(expected, readRemaining(reader));
            }
        }
    }

    /// An option unsupported by both built-in storage backends.
    @NotNullByDefault
    private enum UnknownOpenOption implements OpenOption {
        /// The unsupported option used to exercise validation before mutation.
        INSTANCE
    }

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

    /// Verifies failed temporary-file cleanup closes the handle and remains retryable.
    @Test
    public void temporaryFileCleanupRetriesAfterDeleteFailure() throws IOException {
        Path directory = temporaryDirectory.resolve("retry-content");
        try (ArkivoEditStorage storage = ArkivoEditStorage.temporaryFiles(directory)) {
            ArkivoStoredContent stored = storage.createContent(
                    "entry.bin",
                    ArkivoEditStorage.UNKNOWN_SIZE
            );
            List<Path> stagedPaths;
            try (Stream<Path> paths = Files.list(directory)) {
                stagedPaths = paths.toList();
            }
            assertEquals(1, stagedPaths.size());
            Path stagedPath = stagedPaths.get(0);
            Files.delete(stagedPath);
            Files.createDirectory(stagedPath);
            Path blockingChild = Files.write(stagedPath.resolve("blocking-entry"), new byte[]{10});

            IOException failure = assertThrows(IOException.class, stored::close);
            assertEquals(DirectoryNotEmptyException.class, failure.getClass());
            assertThrows(IOException.class, stored::size);
            assertThrows(
                    IOException.class,
                    () -> stored.openChannel(Set.of(StandardOpenOption.READ))
            );

            Files.delete(blockingChild);
            stored.close();
            stored.close();

            assertFalse(Files.exists(stagedPath));
        }
    }

    /// Verifies hybrid storage selects memory at the threshold and files above it or for unknown sizes.
    @Test
    public void hybridStorageSelectsBackingFromExpectedSize() throws IOException {
        Path directory = temporaryDirectory.resolve("hybrid-content");
        assertThrows(IllegalArgumentException.class, () -> ArkivoEditStorage.hybrid(-1L, directory));

        try (ArkivoEditStorage storage = ArkivoEditStorage.hybrid(4L, directory)) {
            try (ArkivoStoredContent empty = storage.createContent("empty.bin", 0L);
                 ArkivoStoredContent small = storage.createContent("small.bin", 3L);
                 ArkivoStoredContent memory = storage.createContent("threshold.bin", 4L)) {
                assertEquals(0L, empty.size());
                assertEquals(0L, small.size());
                assertFalse(Files.exists(directory));
                byte[] content = new byte[]{1, 2, 3, 4, 5, 6};
                try (SeekableByteChannel channel = memory.openChannel(Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                ))) {
                    assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
                    assertEquals(content.length, memory.size());
                    channel.position(0L);
                    assertArrayEquals(content, readRemaining(channel));
                }
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
