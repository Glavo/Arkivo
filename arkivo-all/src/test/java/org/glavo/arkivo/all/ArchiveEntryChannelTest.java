// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ar.ArArchiveOptions;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies sequential file-entry channels through each writable archive file system's NIO provider.
@NotNullByDefault
final class ArchiveEntryChannelTest {
    /// Rejects read-only destinations before changing an entry's position, including empty entries and EOF.
    @ParameterizedTest
    @MethodSource("formats")
    void rejectsReadOnlyDestinations(Format format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        byte[] expected = {1, 2, 3};
        try (ArkivoFileSystem writer = format.create(archive, ArkivoFileSystemThreadSafety.STRICT)) {
            Files.write(writer.getPath("/entry"), expected);
            Files.write(writer.getPath("/empty"), new byte[0]);
        }
        for (boolean update : new boolean[]{false, true}) {
            try (ArkivoFileSystem fileSystem = update ? format.update(archive) : format.open(archive)) {
                for (String name : new String[]{"/entry", "/empty"}) {
                    try (SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath(name))) {
                        for (long position : new long[]{0L, channel.size(), channel.size() + 1L}) {
                            channel.position(position);
                            for (boolean direct : new boolean[]{false, true}) {
                                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
                                for (int end : new int[]{2, 7}) {
                                    ByteBuffer target = storage.asReadOnlyBuffer().position(2).limit(end).mark();
                                    assertThrows(IllegalArgumentException.class, () -> channel.read(target));
                                    assertEquals(position, channel.position());
                                    assertEquals(2, target.position());
                                    assertEquals(end, target.limit());
                                    assertEquals(2, target.reset().position());
                                }
                            }
                        }
                        channel.position(0L);
                        ByteBuffer decoded = ByteBuffer.allocate(8);
                        int count;
                        while ((count = channel.read(decoded)) >= 0) {
                            assertTrue(count > 0);
                        }
                        decoded.flip();
                        assertEquals(ByteBuffer.wrap(name.equals("/entry") ? expected : new byte[0]), decoded);
                    }
                }
            }
        }
    }

    /// Explicit write options require creation permission before any entry or implicit parent is added.
    @ParameterizedTest
    @MethodSource("formats")
    void rejectsMissingEntriesWithoutCreate(Format format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        try (ArkivoFileSystem writer = format.create(archive, ArkivoFileSystemThreadSafety.STRICT)) {
            for (boolean truncate : new boolean[]{false, true}) {
                Set<OpenOption> options = truncate
                        ? Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                        : Set.of(StandardOpenOption.WRITE);
                Path entry = writer.getPath("/missing/entry");
                assertThrows(NoSuchFileException.class, () -> {
                    try (var ignored = Files.newByteChannel(entry, options)) {
                        // An unexpected successful open must still release its entry.
                    }
                });
                assertThrows(NoSuchFileException.class, () -> {
                    try (var ignored = Files.newOutputStream(entry, options.toArray(OpenOption[]::new))) {
                        // An unexpected successful open must still release its entry.
                    }
                });
                assertFalse(Files.exists(entry));
                assertFalse(Files.exists(entry.getParent()));
            }
            assertThrows(NoSuchFileException.class, () -> {
                try (var ignored = Files.newOutputStream(writer.getPath("/missing/entry"),
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    // Truncation alone must not enable creation.
                }
            });
            try (var output = Files.newOutputStream(writer.getPath("/default"))) {
                output.write(1);
            }
            try (var output = Files.newOutputStream(writer.getPath("/created"), StandardOpenOption.CREATE)) {
                output.write(2);
            }
            try (var channel = Files.newByteChannel(writer.getPath("/new"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[]{3}));
            }
        }
        try (ArkivoFileSystem reader = format.open(archive)) {
            assertFalse(Files.exists(reader.getPath("/missing")));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(reader.getPath("/default")));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(reader.getPath("/created")));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(reader.getPath("/new")));
        }
    }

    /// Ignores creation and truncation options when neither write nor append access is requested.
    @ParameterizedTest
    @MethodSource("formats")
    void readChannelsIgnoreCreationOptions(Format format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        byte[] expected = {1, 2, 3};
        try (ArkivoFileSystem writer = format.create(archive, ArkivoFileSystemThreadSafety.STRICT)) {
            Files.write(writer.getPath("/entry"), expected);
        }
        for (boolean update : new boolean[]{false, true}) {
            try (ArkivoFileSystem fileSystem = update ? format.update(archive) : format.open(archive)) {
                for (StandardOpenOption option : new StandardOpenOption[]{StandardOpenOption.CREATE,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING}) {
                    for (boolean explicitRead : new boolean[]{false, true}) {
                        Set<OpenOption> options = explicitRead ? Set.of(StandardOpenOption.READ, option) : Set.of(option);
                        try (SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/entry"), options)) {
                            ByteBuffer result = ByteBuffer.allocate(expected.length);
                            while (result.hasRemaining()) {
                                if (channel.read(result) < 0) {
                                    break;
                                }
                            }
                            assertEquals(expected.length, result.position());
                            assertArrayEquals(expected, result.array());
                            assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.wrap(expected)));
                        }
                        assertThrows(NoSuchFileException.class,
                                () -> Files.newByteChannel(fileSystem.getPath("/missing"), options));
                        assertFalse(Files.exists(fileSystem.getPath("/missing")));
                    }
                }
            }
        }
        try (ArkivoFileSystem reader = format.open(archive)) {
            assertArrayEquals(expected, Files.readAllBytes(reader.getPath("/entry")));
            assertFalse(Files.exists(reader.getPath("/missing")));
        }
    }

    /// Verifies buffer ranges, cumulative positions, validation failures, and entry completion before reopening.
    @ParameterizedTest
    @MethodSource("entryBuffers")
    void writesEntryBuffers(Format format, ArkivoFileSystemThreadSafety threadSafety,
                           boolean direct, boolean readOnly, int size, @TempDir Path directory)
            throws IOException {
        Path archive = directory.resolve("archive");
        byte[] expected = new byte[size + 2];
        expected[0] = 7;
        expected[expected.length - 1] = 9;
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(size + 16) : ByteBuffer.allocate(size + 16);
        storage.position(5);
        ByteBuffer source = storage.slice();
        source.position(3).limit(size + 3);
        for (int index = 0; index < size; index++) {
            byte value = (byte) (index * 37);
            source.put(3 + index, value);
            expected[index + 1] = value;
        }
        if (readOnly) {
            source = source.asReadOnlyBuffer();
        }
        source.mark();
        try (ArkivoFileSystem fileSystem = format.create(archive, threadSafety)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/entry"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (channel) {
                assertEquals(0L, channel.position());
                assertSame(channel, channel.truncate(Long.MAX_VALUE));
                assertEquals(0L, channel.position());
                assertEquals(0L, channel.size());
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{7})));
                assertEquals(size, channel.write(source));
                assertEquals(size + 3, source.position());
                assertEquals(size + 3, source.limit());
                source.reset();
                assertEquals(3, source.position());
                assertEquals(size + 1L, channel.position());
                assertEquals(size + 1L, channel.size());
                assertSame(channel, channel.position(size + 1L));
                assertSame(channel, channel.truncate(size + 1L));
                assertSame(channel, channel.truncate(size + 2L));
                assertSame(channel, channel.truncate(Long.MAX_VALUE));
                assertEquals(size + 1L, channel.size());
                assertEquals(0, channel.write(ByteBuffer.allocateDirect(0).asReadOnlyBuffer()));
                assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
                assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
                assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
                assertThrows(UnsupportedOperationException.class, () -> channel.position(0L));
                assertThrows(UnsupportedOperationException.class, () -> channel.truncate(0L));
                assertEquals(size + 1L, channel.position());
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{9})));
            }
            channel.close();
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, channel::position);
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(0)));
            Files.write(fileSystem.getPath("/next"), new byte[]{11}, StandardOpenOption.CREATE_NEW);
        }
        try (ArkivoFileSystem fileSystem = format.open(archive)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/entry")));
            assertArrayEquals(new byte[]{11}, Files.readAllBytes(fileSystem.getPath("/next")));
        }
    }

    /// Verifies strict file-system closure completes an outstanding entry and invalidates its retained channel.
    @ParameterizedTest
    @MethodSource("formats")
    void closesOutstandingEntry(Format format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        byte @Unmodifiable [] expected = {1, 2, 3};
        ArkivoFileSystem fileSystem = format.create(archive, ArkivoFileSystemThreadSafety.STRICT);
        SeekableByteChannel body = Files.newByteChannel(fileSystem.getPath("/entry"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (fileSystem) {
            body.write(ByteBuffer.wrap(expected).asReadOnlyBuffer());
        }
        assertFalse(body.isOpen());
        assertThrows(ClosedChannelException.class, () -> body.write(ByteBuffer.allocate(1)));
        body.close();
        fileSystem.close();
        try (ArkivoFileSystem reader = format.open(archive)) {
            assertArrayEquals(expected, Files.readAllBytes(reader.getPath("/entry")));
        }
    }

    /// Supplies transfer boundaries and buffer representations with and without lifecycle coordination.
    private static Stream<Arguments> entryBuffers() {
        return formats().flatMap(format -> Stream.of(ArkivoFileSystemThreadSafety.values())
                .flatMap(threadSafety -> Stream.of(false, true)
                        .flatMap(direct -> Stream.of(false, true).flatMap(readOnly -> Stream.of(0, 8191, 8192, 20_000)
                                .map(size -> Arguments.of(format, threadSafety, direct, readOnly, size))))));
    }

    /// Supplies all formats with forward-only file-system creation channels.
    private static Stream<Format> formats() {
        return Stream.of(Format.values());
    }

    /// Writable archive file systems sharing sequential entry-channel behavior.
    @NotNullByDefault
    private enum Format {
        /// Unix archive members.
        AR,
        /// Tape archive entries.
        TAR,
        /// ZIP local records and central directory.
        ZIP,
        /// 7z streams and index.
        SEVEN_ZIP;

        /// Creates an archive using the public format factory.
        private ArkivoFileSystem create(Path path, ArkivoFileSystemThreadSafety threadSafety) throws IOException {
            ArchiveCreateOptions common = ArchiveCreateOptions.DEFAULT.withThreadSafety(threadSafety);
            return switch (this) {
                case AR -> ArArkivoFileSystem.create(path, ArArchiveOptions.CREATE_DEFAULTS.withCommon(common));
                case TAR -> TarArkivoFileSystem.create(path, TarArchiveOptions.CREATE_DEFAULTS.withCommon(common));
                case ZIP -> ZipArkivoFileSystem.create(path, ZipArchiveOptions.CREATE_DEFAULTS.withCommon(common));
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.create(path,
                        SevenZipArchiveOptions.CREATE_DEFAULTS.withCommon(common));
            };
        }

        /// Reopens the completed archive independently of its writer.
        private ArkivoFileSystem open(Path path) throws IOException {
            return switch (this) {
                case AR -> ArArkivoFileSystem.open(path);
                case TAR -> TarArkivoFileSystem.open(path);
                case ZIP -> ZipArkivoFileSystem.open(path);
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.open(path);
            };
        }

        /// Opens a staged update session for the completed archive.
        private ArkivoFileSystem update(Path path) throws IOException {
            return switch (this) {
                case AR -> ArArkivoFileSystem.update(path);
                case TAR -> TarArkivoFileSystem.update(path);
                case ZIP -> ZipArkivoFileSystem.update(path);
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.update(path);
            };
        }
    }
}
