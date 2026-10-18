// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares bounded ZIP update sequences with a byte-array model and JDK ZIPFS file channels.
@NotNullByDefault
final class ZipUpdateChannelInteropTest {
    /// Exercises JDK-8229887 replacement metadata across shrinking, empty, and growing bodies.
    @ParameterizedTest
    @ValueSource(ints = {ZipEntry.STORED, ZipEntry.DEFLATED})
    void replacesEntryWithoutRetainingOldCompressionMetadata(int method, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("replacement-metadata.zip");
        createArchive(archive, method, new byte[]{11, 13, 17, 19, 23});
        for (int length : new int[]{2, 0, 8193, 1}) {
            byte[] replacement = new byte[length];
            new Random(8229887L + length).nextBytes(replacement);
            try (var fs = ZipArkivoFileSystem.update(archive)) {
                Files.write(fs.getPath("body.bin"), replacement);
                assertArrayEquals(replacement, Files.readAllBytes(fs.getPath("body.bin")));
            }
            var crc = new CRC32();
            crc.update(replacement);
            try (var reference = new ZipFile(archive.toFile()); var fs = ZipArkivoFileSystem.open(archive)) {
                assertEquals(2, reference.size());
                @Nullable var changed = reference.getEntry("body.bin");
                @Nullable var unchanged = reference.getEntry("untouched.bin");
                assertNotNull(changed);
                assertNotNull(unchanged);
                // Replacements use Arkivo's default method, including originally STORED entries.
                assertEquals(ZipEntry.DEFLATED, changed.getMethod());
                assertEquals(length, changed.getSize());
                assertEquals(crc.getValue(), changed.getCrc());
                assertEquals(method, unchanged.getMethod());
                try (var input = reference.getInputStream(changed)) {
                    assertArrayEquals(replacement, input.readAllBytes());
                }
                assertArrayEquals(replacement, Files.readAllBytes(fs.getPath("body.bin")));
                assertArrayEquals(new byte[]{3, 5, 7}, Files.readAllBytes(fs.getPath("untouched.bin")));
                var attributes = Files.readAttributes(fs.getPath("body.bin"), ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(length, attributes.size());
                assertEquals(changed.getCompressedSize(), attributes.compressedSize());
            }
        }
    }

    /// Prevents publication when a staged replacement cannot be opened, fully read, or closed.
    @ParameterizedTest
    @ValueSource(strings = {"open", "read", "close", "stall"})
    void failedReplacementDoesNotPublishPartialArchive(String failurePoint, @TempDir Path directory)
            throws IOException {
        Path archive = directory.resolve("archive.zip");
        createArchive(archive, ZipEntry.DEFLATED, new byte[]{1, 2, 3});
        byte[] original = Files.readAllBytes(archive);
        ReplacementFaultStorage storage = new ReplacementFaultStorage(failurePoint);
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(() -> storage));
        var fileSystem = ZipArkivoFileSystem.update(archive, options);
        try {
            Path entry = fileSystem.getPath("/body.bin");
            Files.write(entry, new byte[]{4, 5, 6});
            SeekableByteChannel channel = Files.newByteChannel(entry,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            channel.write(ByteBuffer.wrap(new byte[]{7, 8, 9}));
            storage.armed = true;
            assertThrows(IOException.class, channel::close);
            channel.close();
            assertThrows(IOException.class, fileSystem::close);
        } finally {
            storage.armed = false;
            fileSystem.close();
        }
        assertArrayEquals(original, Files.readAllBytes(archive));
    }

    /// Injects failures only into pending random-access replacement bodies during serialization.
    @NotNullByDefault
    private static final class ReplacementFaultStorage implements ArkivoEditStorage {
        /// The backing storage used for successful allocations and writes.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// The operation that fails once the test arms injection.
        private final String failurePoint;

        /// Whether replacement serialization should fail.
        private boolean armed;

        /// Selects the failing serialization operation.
        private ReplacementFaultStorage(String failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            ArkivoStoredContent content = delegate.createContent(path, expectedSize);
            if (!path.startsWith("zip-update-random-entry-")) {
                return content;
            }
            return new ArkivoStoredContent() {
                @Override
                public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                    if (!armed || !options.equals(Set.of(StandardOpenOption.READ))) {
                        return content.openChannel(options);
                    }
                    if (failurePoint.equals("open")) {
                        throw new IOException("Injected replacement read-open failure");
                    }
                    return new ReplacementFaultChannel(content.openChannel(options), failurePoint);
                }

                @Override
                public long size() throws IOException {
                    return content.size();
                }

                @Override
                public void close() throws IOException {
                    content.close();
                }
            };
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /// Fails a replacement read after partial progress, returns no progress, or fails its first close.
    @NotNullByDefault
    private static final class ReplacementFaultChannel implements SeekableByteChannel {
        /// The independently owned source channel.
        private final SeekableByteChannel delegate;

        /// The selected failure operation.
        private final String failurePoint;

        /// Whether the injected read prefix was delivered.
        private boolean read;

        /// Whether closure has been attempted.
        private boolean closed;

        /// Wraps one replacement source channel.
        private ReplacementFaultChannel(SeekableByteChannel delegate, String failurePoint) {
            this.delegate = delegate;
            this.failurePoint = failurePoint;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            if (failurePoint.equals("stall")) {
                return 0;
            }
            if (!failurePoint.equals("read")) {
                return delegate.read(target);
            }
            if (read) {
                throw new IOException("Injected replacement read failure");
            }
            read = true;
            int limit = target.limit();
            try {
                target.limit(target.position() + Math.min(2, target.remaining()));
                return delegate.read(target);
            } finally {
                target.limit(limit);
            }
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            if (!closed) {
                closed = true;
                if (failurePoint.equals("close")) {
                    throw new IOException("Injected replacement close failure");
                }
            }
        }
    }

    /// Tests append positioning at the numeric limit without involving a native file offset.
    @ParameterizedTest
    @ValueSource(ints = {ZipEntry.STORED, ZipEntry.DEFLATED})
    void memoryAppendIgnoresMaximumPosition(int method, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("memory-append.zip");
        createArchive(archive, method, new byte[]{1, 2, 3});
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(ArkivoEditStorageFactory.memory()));
        try (var fileSystem = ZipArkivoFileSystem.update(archive, options)) {
            Path entry = fileSystem.getPath("/body.bin");
            try (var channel = Files.newByteChannel(entry, Set.of(StandardOpenOption.APPEND))) {
                channel.position(Long.MAX_VALUE);
                assertEquals(Long.MAX_VALUE, channel.position());
                assertEquals(3, channel.size());
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{4})));
                assertEquals(4, channel.position());
                assertEquals(4, channel.size());
            }
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(entry));
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("body.bin"))) {
                assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
            }
        }
    }

    /// Combines original entry methods, staging backends, buffer kinds, and deterministic operation sequences.
    private static Stream<Arguments> configurations() {
        return Stream.of(ZipEntry.STORED, ZipEntry.DEFLATED).flatMap(method ->
                Stream.of(false, true).flatMap(memory -> Stream.of(false, true).flatMap(direct ->
                        Stream.of(31L, 89L).map(seed -> Arguments.of(method, memory, direct, seed)))));
    }

    /// Checks each operation's bytes and position, staged commits, and the complete persisted ZIP body.
    @ParameterizedTest(name = "method={0}, memory={1}, direct={2}, seed={3}")
    @MethodSource("configurations")
    void randomAccessUpdates(int method, boolean memory, boolean direct, long seed, @TempDir Path directory)
            throws IOException {
        var random = new Random(seed);
        byte[] expected = new byte[2048];
        random.nextBytes(expected);
        Path archive = directory.resolve("arkivo.zip");
        Path referenceArchive = directory.resolve("jdk.zip");
        createArchive(archive, method, expected);
        Files.copy(archive, referenceArchive);
        Path storage = Files.createDirectory(directory.resolve("storage"));
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(memory
                ? ArkivoEditStorageFactory.memory() : ArkivoEditStorageFactory.temporaryFiles(storage)));
        try (var fileSystem = ZipArkivoFileSystem.update(archive, options);
             var reference = FileSystems.newFileSystem(referenceArchive, Map.of())) {
            Path path = fileSystem.getPath("/body.bin");
            Path referencePath = reference.getPath("/body.bin");
            for (int round = 0; round < 4; round++) {
                try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
                     var other = FileChannel.open(referencePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    for (int step = 0; step < 32; step++) {
                        switch (random.nextInt(4)) {
                            case 0 -> {
                                int position = random.nextInt(expected.length + 1);
                                byte[] bytes = new byte[1 + random.nextInt(97)];
                                random.nextBytes(bytes);
                                channel.position(position);
                                other.position(position);
                                writeFully(channel, bytes, direct);
                                writeFully(other, bytes, direct);
                                expected = Arrays.copyOf(expected, Math.max(expected.length, position + bytes.length));
                                System.arraycopy(bytes, 0, expected, position, bytes.length);
                            }
                            case 1 -> {
                                int position = random.nextInt(expected.length + 17);
                                int length = 1 + random.nextInt(127);
                                assertRead(channel, expected, position, length, direct);
                                assertRead(other, expected, position, length, direct);
                            }
                            case 2 -> {
                                int position = random.nextInt(expected.length + 1);
                                int size = random.nextInt(expected.length + 33);
                                channel.position(position).truncate(size);
                                other.position(position).truncate(size);
                                expected = Arrays.copyOf(expected, Math.min(expected.length, size));
                                assertEquals(Math.min(position, size), channel.position());
                            }
                            case 3 -> {
                                int position = expected.length + 17;
                                channel.position(position);
                                other.position(position);
                                assertEquals(0, channel.write(ByteBuffer.allocate(0)));
                                assertEquals(0, other.write(ByteBuffer.allocate(0)));
                                assertEquals(position, channel.position());
                            }
                            default -> throw new AssertionError();
                        }
                        assertEquals(expected.length, channel.size(), "Arkivo size after step " + step);
                        assertEquals(expected.length, other.size(), "JDK size after step " + step);
                        assertEquals(other.position(), channel.position(), "Position after step " + step);
                    }
                    long position = channel.position();
                    assertThrows(IllegalArgumentException.class, () -> channel.position(-1));
                    assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1));
                    assertEquals(position, channel.position());
                    assertRead(channel, expected, 0, expected.length + 1, direct);
                    assertRead(other, expected, 0, expected.length + 1, direct);
                }
                assertArrayEquals(expected, Files.readAllBytes(path));
                assertArrayEquals(expected, Files.readAllBytes(referencePath));
            }
            // Append writes ignore explicit seeks, including a position well beyond the current end.
            try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.APPEND));
                 var other = FileChannel.open(referencePath, StandardOpenOption.APPEND)) {
                // Native file systems can reject offsets near Long.MAX_VALUE before an append write.
                for (long position : new long[]{0, expected.length + 1_048_576L}) {
                    byte[] suffix = new byte[]{19, 23, 29};
                    channel.position(position);
                    other.position(position);
                    writeFully(channel, suffix, direct);
                    writeFully(other, suffix, direct);
                    int previousSize = expected.length;
                    expected = Arrays.copyOf(expected, previousSize + suffix.length);
                    System.arraycopy(suffix, 0, expected, previousSize, suffix.length);
                    assertEquals(expected.length, channel.position());
                    assertEquals(expected.length, other.position());
                }
            }
            assertArrayEquals(expected, Files.readAllBytes(path));
            assertArrayEquals(expected, Files.readAllBytes(referencePath));
        }
        for (Path persisted : new Path[]{archive, referenceArchive}) {
            try (var fileSystem = ZipArkivoFileSystem.open(persisted);
                 var reference = new ZipFile(persisted.toFile())) {
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/body.bin")));
                try (var input = reference.getInputStream(reference.getEntry("body.bin"))) {
                    assertArrayEquals(expected, input.readAllBytes());
                }
                assertEquals(2, reference.size());
                assertArrayEquals(new byte[]{3, 5, 7}, Files.readAllBytes(fileSystem.getPath("/untouched.bin")));
            }
        }
        try (var files = Files.list(storage)) {
            assertEquals(0, files.count());
        }
    }

    /// Writes the complete selected region of a read-only source buffer without depending on array access.
    private static void writeFully(SeekableByteChannel channel, byte[] bytes, boolean direct) throws IOException {
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(bytes.length + 6) : ByteBuffer.allocate(bytes.length + 6);
        storage.position(3).put(bytes).limit(3 + bytes.length).position(3);
        ByteBuffer source = storage.asReadOnlyBuffer();
        while (source.hasRemaining()) {
            assertTrue(channel.write(source) > 0);
        }
        assertEquals(3 + bytes.length, source.position());
        assertEquals(3 + bytes.length, source.limit());
    }

    /// Checks bounded reads, EOF position, and untouched bytes outside the destination's selected region.
    private static void assertRead(SeekableByteChannel channel, byte[] content, int position, int length, boolean direct)
            throws IOException {
        byte[] expected = new byte[length + 6];
        Arrays.fill(expected, (byte) 0x5a);
        ByteBuffer destination = direct ? ByteBuffer.allocateDirect(expected.length) : ByteBuffer.allocate(expected.length);
        destination.put(expected).position(3).limit(3 + length);
        channel.position(position);
        int count = Math.min(length, Math.max(0, content.length - position));
        if (count != 0) {
            System.arraycopy(content, position, expected, 3, count);
        }
        while (destination.hasRemaining()) {
            int read = channel.read(destination);
            if (read < 0) {
                break;
            }
            assertTrue(read > 0);
        }
        assertEquals(3 + count, destination.position());
        assertEquals(3 + length, destination.limit());
        assertEquals(position + count, channel.position());
        byte[] actual = new byte[expected.length];
        destination.clear().get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Creates two entries with JDK ZIP output, including an unrelated entry that must survive every update.
    private static void createArchive(Path archive, int method, byte[] content) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : List.of(Map.entry("body.bin", content), Map.entry("untouched.bin", new byte[]{3, 5, 7}))) {
                var header = new ZipEntry(entry.getKey());
                var crc = new CRC32();
                crc.update(entry.getValue());
                header.setMethod(method);
                header.setSize(entry.getValue().length);
                header.setCrc(crc.getValue());
                output.putNextEntry(header);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
