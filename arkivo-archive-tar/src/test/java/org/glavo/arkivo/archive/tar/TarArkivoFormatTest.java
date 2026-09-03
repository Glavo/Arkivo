// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public TAR format descriptor.
@NotNullByDefault
public final class TarArkivoFormatTest {
    /// Verifies descriptor metadata, capabilities, and empty-archive detection without buffer mutation.
    @Test
    public void describesAndDetectsTarArchives() {
        TarArkivoFormat format = TarArkivoFormat.instance();

        assertSame(format, TarArkivoFormat.instance());
        assertEquals(TarArkivoFormat.NAME, format.name());
        assertEquals(List.of(
                "tar", "tar.gz", "tgz", "tar.bz2", "tbz2", "tbz", "tar.Z", "taz",
                "tar.xz", "txz", "tar.lzma", "tar.lz", "tlz", "tar.lz4", "tlz4", "tar.zst", "tzst"
        ), format.fileExtensions());
        assertEquals(1024, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.FileSystem.Writable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.FileSystem.OuterCompressed);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingReader);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingWriter);

        ByteBuffer prefix = ByteBuffer.allocate(1032).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(3).put(new byte[1024]).limit(1027);
        prefix.position(2).mark().position(3);
        assertTrue(format.matches(prefix));
        assertEquals(3, prefix.position());
        assertEquals(1027, prefix.limit());
        assertSame(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());

        assertFalse(format.matches(ByteBuffer.wrap(new byte[511])));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[512])));
        byte[] nonzeroSecondBlock = new byte[1024];
        nonzeroSecondBlock[700] = 1;
        assertFalse(format.matches(ByteBuffer.wrap(nonzeroSecondBlock)));
    }

    /// Verifies malformed and non-matching checksum fields cannot identify arbitrary blocks as TAR headers.
    @Test
    public void rejectsInvalidChecksumFields() {
        TarArkivoFormat format = TarArkivoFormat.instance();
        byte[] header = new byte[512];
        header[0] = 'x';

        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        assertFalse(format.matches(ByteBuffer.wrap(header)));

        java.util.Arrays.fill(header, 148, 156, (byte) 0);
        assertFalse(format.matches(ByteBuffer.wrap(header)));

        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[148] = '/';
        assertFalse(format.matches(ByteBuffer.wrap(header)));

        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[148] = '8';
        assertFalse(format.matches(ByteBuffer.wrap(header)));
    }

    /// Verifies every generic streaming factory can produce or consume a valid empty TAR archive.
    @Test
    public void roundTripsEmptyArchiveThroughStreamingFactories() throws IOException {
        TarArkivoFormat format = TarArkivoFormat.instance();

        ByteArrayOutputStream streamDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamDefault)) {
            // Closing emits the required zero end blocks.
        }
        assertEmptyArchiveThroughReaders(format, streamDefault.toByteArray());

        ByteArrayOutputStream streamConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamConfigured, ArchiveCreateOptions.DEFAULT)) {
            // Closing emits the required zero end blocks.
        }
        assertEmptyArchiveThroughReaders(format, streamConfigured.toByteArray());

        ByteArrayOutputStream channelDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(Channels.newChannel(channelDefault))) {
            // Closing emits the required zero end blocks.
        }
        assertEmptyArchiveThroughReaders(format, channelDefault.toByteArray());

        ByteArrayOutputStream channelConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(
                Channels.newChannel(channelConfigured),
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing emits the required zero end blocks.
        }
        assertEmptyArchiveThroughReaders(format, channelConfigured.toByteArray());
    }

    /// Verifies generic path, direct-channel, and repeatable-source file-system factories.
    ///
    /// @param directory the temporary directory used for the path-backed archive
    @Test
    public void opensFileSystemsThroughGenericFactories(@TempDir Path directory) throws IOException {
        TarArkivoFormat format = TarArkivoFormat.instance();
        Path archive = directory.resolve("empty.tar");

        try (var fileSystem = format.create(archive, ArchiveCreateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(archive)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(archive, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.update(archive, ArchiveUpdateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }

        SeekableByteChannel defaultChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(defaultChannel)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(defaultChannel.isOpen());

        SeekableByteChannel configuredChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(configuredChannel, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(configuredChannel.isOpen());

        SeekableByteChannel repeatableBacking = Files.newByteChannel(archive, StandardOpenOption.READ);
        ArkivoSeekableChannelSource repeatableSource = ArkivoSeekableChannelSource.of(repeatableBacking);
        try (var fileSystem = format.open(repeatableSource, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(repeatableBacking.isOpen());
    }

    /// Verifies null prefixes fail at the public probing boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> TarArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Verifies every generic streaming reader overload observes an empty archive.
    private static void assertEmptyArchiveThroughReaders(TarArkivoFormat format, byte[] archive) throws IOException {
        assertTrue(archive.length >= format.probeSize());
        assertTrue(format.matches(ByteBuffer.wrap(archive)));
        try (var reader = format.openStreamingReader(new ByteArrayInputStream(archive))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                new ByteArrayInputStream(archive),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(Channels.newChannel(new ByteArrayInputStream(archive)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
    }
}
