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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies sequential file-entry channels through each writable archive file system's NIO provider.
@NotNullByDefault
final class ArchiveEntryChannelTest {
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
    }
}
