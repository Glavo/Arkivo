// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises generated GUID and Apple partition maps without external fixtures.
@NotNullByDefault
final class DMGPartitionTableTest {
    /// The Macintosh Roman encoding used by Apple Partition Map names.
    private static final Charset MAC_ROMAN = Charset.forName("x-MacRoman");

    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Reads a GPT partition, canonicalizes its type GUID, and omits unused entries.
    @Test
    void readsGUIDPartitionTable() throws IOException {
        byte[] disk = createGPTDisk();
        Path path = writeRawImage(temporaryDirectory.resolve("gpt.dmg"), disk);

        DMGPartition expected = new DMGPartition(
                0,
                3L * SECTOR_SIZE,
                2L * SECTOR_SIZE,
                "Data",
                "48465300-0000-11AA-AA11-00306543ECAC",
                DMGPartitionScheme.GUID_PARTITION_TABLE
        );
        try (DMGImage image = DMGImage.open(path)) {
            assertEquals(List.of(expected), image.partitions());
            assertPartitionBytes(
                    image,
                    expected,
                    Arrays.copyOfRange(disk, 3 * SECTOR_SIZE, 5 * SECTOR_SIZE)
            );
        }
    }

    /// Rejects a recognized GPT whose header or entry-array checksum is invalid.
    @Test
    void rejectsGUIDPartitionTableChecksumMismatch() throws IOException {
        byte[] invalidHeader = createGPTDisk();
        invalidHeader[SECTOR_SIZE + 16] ^= 1;
        Path invalidHeaderPath = writeRawImage(temporaryDirectory.resolve("gpt-header-crc.dmg"), invalidHeader);
        IOException headerException = assertThrows(IOException.class, () -> DMGImage.open(invalidHeaderPath));
        assertTrue(headerException.getMessage().contains("GPT header CRC32 mismatch"));

        byte[] invalidTable = createGPTDisk();
        invalidTable[2 * SECTOR_SIZE + 100] ^= 1;
        Path invalidTablePath = writeRawImage(temporaryDirectory.resolve("gpt-table-crc.dmg"), invalidTable);
        IOException tableException = assertThrows(IOException.class, () -> DMGImage.open(invalidTablePath));
        assertTrue(tableException.getMessage().contains("GPT partition-table CRC32 mismatch"));
    }

    /// Reads APM metadata using Macintosh Roman and omits zero-length map slots.
    @Test
    void readsApplePartitionMap() throws IOException {
        byte[] disk = createApplePartitionMapDisk();
        Path path = writeRawImage(temporaryDirectory.resolve("apm.dmg"), disk);

        List<DMGPartition> expected = List.of(
                new DMGPartition(
                        0,
                        SECTOR_SIZE,
                        3L * SECTOR_SIZE,
                        "Partition Map",
                        "Apple_partition_map",
                        DMGPartitionScheme.APPLE_PARTITION_MAP
                ),
                new DMGPartition(
                        1,
                        4L * SECTOR_SIZE,
                        2L * SECTOR_SIZE,
                        "Café",
                        "Apple_HFS",
                        DMGPartitionScheme.APPLE_PARTITION_MAP
                )
        );
        try (DMGImage image = DMGImage.open(path)) {
            assertEquals(expected, image.partitions());
            assertPartitionBytes(
                    image,
                    expected.get(1),
                    Arrays.copyOfRange(disk, 4 * SECTOR_SIZE, 6 * SECTOR_SIZE)
            );
        }
    }

    /// Uses the standard 512-byte APM block size when no Driver Descriptor Map is present.
    @Test
    void readsApplePartitionMapWithoutDriverDescriptor() throws IOException {
        byte[] disk = createApplePartitionMapDisk();
        ByteArrayAccess.writeShortBigEndian(disk, 0, (short) 0);
        Path path = writeRawImage(temporaryDirectory.resolve("apm-without-driver-map.dmg"), disk);

        try (DMGImage image = DMGImage.open(path)) {
            assertEquals(2, image.partitions().size());
            assertEquals(DMGPartitionScheme.APPLE_PARTITION_MAP, image.partitions().get(1).scheme());
            assertEquals("Café", image.partitions().get(1).name());
        }
    }

    /// Rejects a malformed recognized APM instead of treating the disk as unpartitioned.
    @Test
    void rejectsMalformedApplePartitionMap() throws IOException {
        byte[] disk = createApplePartitionMapDisk();
        ByteArrayAccess.writeIntBigEndian(disk, 2 * SECTOR_SIZE + 4, 2);
        Path path = writeRawImage(temporaryDirectory.resolve("malformed-apm.dmg"), disk);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));
        assertTrue(exception.getMessage().contains("Malformed Apple Partition Map entry 1"));
    }

    /// Creates a six-sector disk containing one present and one unused GPT entry.
    private static byte[] createGPTDisk() {
        byte[] disk = new byte[6 * SECTOR_SIZE];
        System.arraycopy(sector(41), 0, disk, 3 * SECTOR_SIZE, SECTOR_SIZE);
        System.arraycopy(sector(42), 0, disk, 4 * SECTOR_SIZE, SECTOR_SIZE);

        byte[] entries = new byte[2 * 128];
        ByteArrayAccess.writeIntLittleEndian(entries, 0, 0x4846_5300);
        ByteArrayAccess.writeShortLittleEndian(entries, 4, (short) 0x0000);
        ByteArrayAccess.writeShortLittleEndian(entries, 6, (short) 0x11aa);
        entries[8] = (byte) 0xaa;
        entries[9] = 0x11;
        entries[10] = 0x00;
        entries[11] = 0x30;
        entries[12] = 0x65;
        entries[13] = 0x43;
        entries[14] = (byte) 0xec;
        entries[15] = (byte) 0xac;
        entries[16] = 1;
        ByteArrayAccess.writeLongLittleEndian(entries, 32, 3L);
        ByteArrayAccess.writeLongLittleEndian(entries, 40, 4L);
        byte[] name = "Data".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, entries, 56, name.length);
        System.arraycopy(entries, 0, disk, 2 * SECTOR_SIZE, entries.length);

        int headerOffset = SECTOR_SIZE;
        byte[] signature = "EFI PART".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, disk, headerOffset, signature.length);
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 8, 0x0001_0000);
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 12, 92);
        ByteArrayAccess.writeLongLittleEndian(disk, headerOffset + 24, 1L);
        ByteArrayAccess.writeLongLittleEndian(disk, headerOffset + 32, 5L);
        ByteArrayAccess.writeLongLittleEndian(disk, headerOffset + 40, 3L);
        ByteArrayAccess.writeLongLittleEndian(disk, headerOffset + 48, 4L);
        disk[headerOffset + 56] = 1;
        ByteArrayAccess.writeLongLittleEndian(disk, headerOffset + 72, 2L);
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 80, 2);
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 84, 128);
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 88, crc32(entries, 0, entries.length));
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 16, crc32(disk, headerOffset, 92));
        return disk;
    }

    /// Creates a six-sector disk containing two present and one unused APM entries.
    private static byte[] createApplePartitionMapDisk() {
        byte[] disk = new byte[6 * SECTOR_SIZE];
        ByteArrayAccess.writeShortBigEndian(disk, 0, (short) 0x4552);
        ByteArrayAccess.writeShortBigEndian(disk, 2, (short) SECTOR_SIZE);
        writeApplePartitionEntry(disk, 1, 3, 1, 3, "Partition Map", "Apple_partition_map");
        writeApplePartitionEntry(disk, 2, 3, 4, 2, "Café", "Apple_HFS");
        writeApplePartitionEntry(disk, 3, 3, 0, 0, "Unused", "Apple_Free");
        System.arraycopy(sector(51), 0, disk, 4 * SECTOR_SIZE, SECTOR_SIZE);
        System.arraycopy(sector(52), 0, disk, 5 * SECTOR_SIZE, SECTOR_SIZE);
        return disk;
    }

    /// Writes one Apple Partition Map entry into a decoded disk.
    private static void writeApplePartitionEntry(
            byte[] disk,
            int slot,
            int entryCount,
            int startBlock,
            int blockCount,
            String name,
            String type
    ) {
        int offset = slot * SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, offset, (short) 0x504d);
        ByteArrayAccess.writeIntBigEndian(disk, offset + 4, entryCount);
        ByteArrayAccess.writeIntBigEndian(disk, offset + 8, startBlock);
        ByteArrayAccess.writeIntBigEndian(disk, offset + 12, blockCount);
        writeCString(disk, offset + 16, 32, name);
        writeCString(disk, offset + 48, 32, type);
    }

    /// Writes a Macintosh Roman string followed by zero padding.
    private static void writeCString(byte[] target, int offset, int length, String value) {
        byte[] encoded = value.getBytes(MAC_ROMAN);
        if (encoded.length >= length) {
            throw new IllegalArgumentException("fixture string does not fit its fixed field");
        }
        System.arraycopy(encoded, 0, target, offset, encoded.length);
    }

    /// Returns the CRC-32 of an array range as its raw 32-bit representation.
    private static int crc32(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    /// Verifies the complete bytes exposed by one discovered partition.
    private static void assertPartitionBytes(
            DMGImage image,
            DMGPartition partition,
            byte[] expected
    ) throws IOException {
        byte[] actual = new byte[expected.length];
        try (SeekableByteChannel channel = image.openPartition(partition)) {
            readFully(channel, ByteBuffer.wrap(actual));
        }
        assertArrayEquals(expected, actual);
    }
}
