// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.internal.ByteArrayAccess;
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
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public RAR format descriptor.
@NotNullByDefault
public final class RarArkivoFormatTest {
    /// Verifies descriptor metadata, capabilities, and exact RAR4 and RAR5 signature detection.
    @Test
    public void describesAndDetectsRarArchives() {
        RarArkivoFormat format = RarArkivoFormat.instance();
        byte[] rar4 = new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};
        byte[] rar5 = new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

        assertSame(format, RarArkivoFormat.instance());
        assertEquals(RarArkivoFormat.NAME, format.name());
        assertEquals(List.of("rar"), format.fileExtensions());
        assertEquals(rar5.length, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.PathVolume);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeFileSystem);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingReadable);
        assertTrue(format.matches(ByteBuffer.wrap(rar4)));
        assertTrue(format.matches(ByteBuffer.wrap(rar5)));

        ByteBuffer prefix = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(3).put(rar5).limit(3 + rar5.length);
        prefix.position(2).mark().position(3);
        assertTrue(format.matches(prefix));
        assertEquals(3, prefix.position());
        assertEquals(11, prefix.limit());
        assertSame(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());

        assertFalse(format.matches(ByteBuffer.wrap(new byte[6])));
        for (int index = 0; index < 6; index++) {
            byte[] wrongSignature = rar5.clone();
            wrongSignature[index] ^= 1;
            assertFalse(format.matches(ByteBuffer.wrap(wrongSignature)), "index " + index);
        }
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x02, 0x00})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x01})));
    }

    /// Verifies generic streaming and indexed factories can consume a generated empty RAR4 archive.
    ///
    /// @param directory the temporary directory used for path-backed factories
    @Test
    public void opensEmptyRar4ThroughGenericFactories(@TempDir Path directory) throws IOException {
        RarArkivoFormat format = RarArkivoFormat.instance();
        byte[] archive = emptyRar4Archive();
        Path path = Files.write(directory.resolve("empty.rar"), archive);
        assertNull(format.discoverVolumePaths(path));

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
        try (var reader = format.openStreamingReader(path)) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(path, ArchiveReadOptions.DEFAULT)) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(ArkivoVolumeSource.of(List.of(path)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                ArkivoVolumeSource.of(List.of(path)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }

        try (var fileSystem = format.open(path)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(path, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }

        SeekableByteChannel defaultChannel = Files.newByteChannel(path, StandardOpenOption.READ);
        try (var fileSystem = format.open(defaultChannel)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(defaultChannel.isOpen());

        SeekableByteChannel configuredChannel = Files.newByteChannel(path, StandardOpenOption.READ);
        try (var fileSystem = format.open(configuredChannel, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(configuredChannel.isOpen());

        SeekableByteChannel repeatableBacking = Files.newByteChannel(path, StandardOpenOption.READ);
        ArkivoSeekableChannelSource repeatableSource = ArkivoSeekableChannelSource.of(repeatableBacking);
        try (var fileSystem = format.open(repeatableSource, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(repeatableBacking.isOpen());

        try (var fileSystem = format.open(ArkivoVolumeSource.of(List.of(path)))) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(
                ArkivoVolumeSource.of(List.of(path)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertTrue(fileSystem.isOpen());
        }
    }

    /// Verifies null prefixes fail at the public probing boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> RarArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Creates a minimal RAR4 archive containing only main and end headers.
    private static byte[] emptyRar4Archive() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00});
        output.writeBytes(rar4BlockHeader(0x73, 0, new byte[6]));
        output.writeBytes(rar4BlockHeader(0x7b, 0, new byte[0]));
        return output.toByteArray();
    }

    /// Encodes one complete RAR4 block header with its low CRC-32 bits stored as CRC-16.
    private static byte[] rar4BlockHeader(int type, int flags, byte[] fields) {
        byte[] headerData = new byte[5 + fields.length];
        headerData[0] = (byte) type;
        ByteArrayAccess.writeShortLittleEndian(headerData, 1, (short) flags);
        ByteArrayAccess.writeShortLittleEndian(headerData, 3, (short) (headerData.length + Short.BYTES));
        System.arraycopy(fields, 0, headerData, 5, fields.length);

        CRC32 checksum = new CRC32();
        checksum.update(headerData);
        byte[] header = new byte[Short.BYTES + headerData.length];
        ByteArrayAccess.writeShortLittleEndian(header, 0, (short) checksum.getValue());
        System.arraycopy(headerData, 0, header, Short.BYTES, headerData.length);
        return header;
    }
}
