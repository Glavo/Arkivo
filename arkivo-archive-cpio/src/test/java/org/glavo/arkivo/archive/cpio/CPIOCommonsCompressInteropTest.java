// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bidirectional CPIO interoperability with Apache Commons Compress 1.28.
@NotNullByDefault
public final class CPIOCommonsCompressInteropTest {
    /// The modification timestamp shared by independently encoded fixtures.
    private static final long MODIFICATION_TIME_SECONDS = 1_700_000_321L;

    /// Verifies Commons Compress reads Arkivo output for every dialect and both old-binary byte orders.
    @Test
    public void commonsCompressReadsArkivoOutput() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            CPIOBinaryByteOrder[] byteOrders = dialect == CPIODialect.OLD_BINARY
                    ? CPIOBinaryByteOrder.values()
                    : new CPIOBinaryByteOrder[]{CPIOBinaryByteOrder.BIG_ENDIAN};
            for (CPIOBinaryByteOrder byteOrder : byteOrders) {
                byte[] content = content();
                byte[] archive = writeWithArkivo(dialect, byteOrder, content);
                try (CpioArchiveInputStream input = new CpioArchiveInputStream(
                        new ByteArrayInputStream(archive),
                        CpioConstants.BLOCK_SIZE,
                        StandardCharsets.UTF_8.name()
                )) {
                    CpioArchiveEntry directory = Objects.requireNonNull(input.getNextEntry());
                    assertEquals(commonsFormat(dialect), directory.getFormat(), dialect.name());
                    assertEquals("dir", directory.getName());
                    assertTrue(directory.isDirectory());
                    assertArrayEquals(new byte[0], input.readAllBytes());

                    CpioArchiveEntry file = Objects.requireNonNull(input.getNextEntry());
                    assertEquals(commonsFormat(dialect), file.getFormat(), dialect.name());
                    assertEquals("dir/data.bin", file.getName());
                    assertEquals(0x2345L, file.getInode());
                    assertEquals(123L, file.getUID());
                    assertEquals(456L, file.getGID());
                    assertEquals(3L, file.getNumberOfLinks());
                    assertEquals(0100640, file.getMode());
                    assertEquals(MODIFICATION_TIME_SECONDS, file.getTime());
                    assertEquals(content.length, file.getSize());
                    assertCommonsDialectFields(file, dialect, checksum(content));
                    assertArrayEquals(content, input.readAllBytes());

                    CpioArchiveEntry link = Objects.requireNonNull(input.getNextEntry());
                    assertEquals("dir/link", link.getName());
                    assertTrue(link.isSymbolicLink());
                    assertArrayEquals("data.bin".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                    assertNull(input.getNextEntry());
                }
            }
        }
    }

    /// Verifies Arkivo reads independently generated Commons Compress output for every dialect.
    @Test
    public void arkivoReadsCommonsCompressOutput() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            byte[] content = content();
            byte[] archive = writeWithCommonsCompress(dialect, content);
            try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next(), dialect.name());
                CPIOArkivoEntryAttributes directory = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                assertEquals("dir", directory.path());
                assertEquals(dialect, directory.dialect());
                assertTrue(directory.isDirectory());
                try (InputStream body = reader.openInputStream()) {
                    assertArrayEquals(new byte[0], body.readAllBytes());
                }

                assertTrue(reader.next(), dialect.name());
                CPIOArkivoEntryAttributes file = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                assertEquals("dir/data.bin", file.path());
                assertEquals(dialect, file.dialect());
                assertEquals(
                        dialect == CPIODialect.OLD_BINARY ? CPIOBinaryByteOrder.BIG_ENDIAN : null,
                        file.binaryByteOrder()
                );
                assertEquals(0x2345L, file.inode());
                assertEquals(123L, file.userId());
                assertEquals(456L, file.groupId());
                assertEquals(3L, file.linkCount());
                assertEquals(0100640, file.mode());
                assertEquals(FileTime.from(Instant.ofEpochSecond(MODIFICATION_TIME_SECONDS)), file.lastModifiedTime());
                assertEquals(content.length, file.size());
                assertArkivoDialectFields(file, dialect, checksum(content));
                try (InputStream body = reader.openInputStream()) {
                    assertArrayEquals(content, body.readAllBytes());
                }

                assertTrue(reader.next(), dialect.name());
                CPIOArkivoEntryAttributes link = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                assertEquals("dir/link", link.path());
                assertTrue(link.isSymbolicLink());
                try (InputStream body = reader.openInputStream()) {
                    assertArrayEquals("data.bin".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
                }
                assertFalse(reader.next(), dialect.name());
            }
        }
    }

    /// Writes representative entries using the Arkivo implementation.
    private static byte[] writeWithArkivo(
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder,
            byte[] content
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(dialect)
                .withBinaryByteOrder(byteOrder);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(output, options)) {
            var directory = writer.beginDirectory("dir");
            CPIOArkivoEntryAttributeView directoryAttributes = Objects.requireNonNull(
                    directory.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            directoryAttributes.setInode(0x2344L);
            directory.close();

            var file = writer.beginFile("dir/data.bin");
            CPIOArkivoEntryAttributeView attributes = Objects.requireNonNull(
                    file.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            configureArkivoAttributes(attributes, content.length);
            try (OutputStream body = file.openOutputStream()) {
                body.write(content);
            }

            var link = writer.beginSymbolicLink("dir/link", "data.bin");
            CPIOArkivoEntryAttributeView linkAttributes = Objects.requireNonNull(
                    link.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            linkAttributes.setInode(0x2346L);
            link.close();
        }
        return output.toByteArray();
    }

    /// Configures metadata shared by Arkivo-produced interoperability entries.
    private static void configureArkivoAttributes(CPIOArkivoEntryAttributeView attributes, long size)
            throws IOException {
        attributes.setTimes(FileTime.from(Instant.ofEpochSecond(MODIFICATION_TIME_SECONDS)), null, null);
        attributes.setInode(0x2345L);
        attributes.setUserId(123L);
        attributes.setGroupId(456L);
        attributes.setLinkCount(3L);
        attributes.setMode(0100640);
        attributes.setDevice(21L);
        attributes.setRemoteDevice(22L);
        attributes.setDeviceNumbers(23L, 24L);
        attributes.setRemoteDeviceNumbers(25L, 26L);
        attributes.setSize(size);
    }

    /// Writes representative entries using Apache Commons Compress.
    private static byte[] writeWithCommonsCompress(CPIODialect dialect, byte[] content) throws IOException {
        short format = commonsFormat(dialect);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CpioArchiveOutputStream archive = new CpioArchiveOutputStream(
                output,
                format,
                CpioConstants.BLOCK_SIZE,
                StandardCharsets.UTF_8.name()
        )) {
            CpioArchiveEntry directory = commonsEntry(format, "dir", 0L, 040750, 0x2344L, dialect);
            archive.putArchiveEntry(directory);
            archive.closeArchiveEntry();

            CpioArchiveEntry file = commonsEntry(
                    format,
                    "dir/data.bin",
                    content.length,
                    0100640,
                    0x2345L,
                    dialect
            );
            file.setUID(123L);
            file.setGID(456L);
            file.setNumberOfLinks(3L);
            if (dialect == CPIODialect.NEW_ASCII_CRC) {
                file.setChksum(checksum(content));
            }
            archive.putArchiveEntry(file);
            archive.write(content);
            archive.closeArchiveEntry();

            byte[] target = "data.bin".getBytes(StandardCharsets.UTF_8);
            CpioArchiveEntry link = commonsEntry(
                    format,
                    "dir/link",
                    target.length,
                    0120777,
                    0x2346L,
                    dialect
            );
            if (dialect == CPIODialect.NEW_ASCII_CRC) {
                link.setChksum(checksum(target));
            }
            archive.putArchiveEntry(link);
            archive.write(target);
            archive.closeArchiveEntry();
        }
        return output.toByteArray();
    }

    /// Creates and configures one Commons Compress entry for the selected dialect.
    private static CpioArchiveEntry commonsEntry(
            short format,
            String name,
            long size,
            int mode,
            long inode,
            CPIODialect dialect
    ) {
        CpioArchiveEntry entry = new CpioArchiveEntry(format, name, size);
        entry.setMode(mode);
        entry.setInode(inode);
        entry.setTime(MODIFICATION_TIME_SECONDS);
        if (dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC) {
            entry.setDeviceMaj(23L);
            entry.setDeviceMin(24L);
            entry.setRemoteDeviceMaj(25L);
            entry.setRemoteDeviceMin(26L);
        } else {
            entry.setDevice(21L);
            entry.setRemoteDevice(22L);
        }
        return entry;
    }

    /// Verifies dialect-specific metadata read by Commons Compress.
    private static void assertCommonsDialectFields(
            CpioArchiveEntry entry,
            CPIODialect dialect,
            long expectedChecksum
    ) {
        if (dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC) {
            assertEquals(23L, entry.getDeviceMaj());
            assertEquals(24L, entry.getDeviceMin());
            assertEquals(25L, entry.getRemoteDeviceMaj());
            assertEquals(26L, entry.getRemoteDeviceMin());
        } else {
            assertEquals(21L, entry.getDevice());
            assertEquals(22L, entry.getRemoteDevice());
        }
        if (dialect == CPIODialect.NEW_ASCII_CRC) {
            assertEquals(expectedChecksum, entry.getChksum());
        }
    }

    /// Verifies dialect-specific metadata read by Arkivo.
    private static void assertArkivoDialectFields(
            CPIOArkivoEntryAttributes entry,
            CPIODialect dialect,
            long expectedChecksum
    ) {
        if (dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC) {
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.device());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.remoteDevice());
            assertEquals(23L, entry.deviceMajor());
            assertEquals(24L, entry.deviceMinor());
            assertEquals(25L, entry.remoteDeviceMajor());
            assertEquals(26L, entry.remoteDeviceMinor());
        } else {
            assertEquals(21L, entry.device());
            assertEquals(22L, entry.remoteDevice());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.deviceMajor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.deviceMinor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.remoteDeviceMajor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, entry.remoteDeviceMinor());
        }
        assertEquals(
                dialect == CPIODialect.NEW_ASCII_CRC
                        ? expectedChecksum : CPIOArkivoEntryAttributes.NOT_STORED,
                entry.checksum()
        );
    }

    /// Maps Arkivo's public dialect enum to the Commons Compress wire-format identifier.
    private static short commonsFormat(CPIODialect dialect) {
        return switch (dialect) {
            case NEW_ASCII -> CpioConstants.FORMAT_NEW;
            case NEW_ASCII_CRC -> CpioConstants.FORMAT_NEW_CRC;
            case OLD_ASCII -> CpioConstants.FORMAT_OLD_ASCII;
            case OLD_BINARY -> CpioConstants.FORMAT_OLD_BINARY;
        };
    }

    /// Creates deterministic cross-implementation entry content.
    private static byte[] content() {
        byte[] content = new byte[1027];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 29 + index / 7);
        }
        return content;
    }

    /// Calculates the unsigned 32-bit additive checksum used by the CRC dialect.
    private static long checksum(byte[] content) {
        long checksum = 0L;
        for (byte value : content) {
            checksum = checksum + Byte.toUnsignedInt(value) & 0xffff_ffffL;
        }
        return checksum;
    }
}
