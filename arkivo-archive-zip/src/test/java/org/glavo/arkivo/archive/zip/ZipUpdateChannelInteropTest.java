// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares bounded ZIP update sequences with a byte-array model and JDK ZIPFS file channels.
@NotNullByDefault
final class ZipUpdateChannelInteropTest {
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
                for (long position : new long[]{0, Long.MAX_VALUE}) {
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
