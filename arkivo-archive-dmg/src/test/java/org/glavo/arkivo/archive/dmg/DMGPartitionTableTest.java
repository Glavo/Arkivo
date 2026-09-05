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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.concatenate;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises generated GUID and Apple partition maps without external fixtures.
@NotNullByDefault
final class DMGPartitionTableTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Verifies partition descriptors reject negative geometry and require a partitioning scheme.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesPartitionDescriptors() {
        DMGPartition partition = new DMGPartition(0, 0L, 0L, null, null, DMGPartitionScheme.RAW);
        assertEquals(0, partition.index());
        assertEquals(DMGPartitionScheme.RAW, partition.scheme());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(-1, 0L, 0L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(0, -1L, 0L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(0, 0L, -1L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                NullPointerException.class,
                () -> new DMGPartition(0, 0L, 0L, null, null, null)
        );
    }

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

    /// Rejects unsupported GPT revisions and malformed header geometry before reading entries.
    @Test
    void rejectsMalformedGUIDPartitionTableHeader() throws IOException {
        byte[] unsupportedRevision = createGPTDisk();
        ByteArrayAccess.writeIntLittleEndian(unsupportedRevision, SECTOR_SIZE + 8, 0x0002_0000);
        rewriteGPTHeaderChecksum(unsupportedRevision);
        assertRejected(
                unsupportedRevision,
                "gpt-revision.dmg",
                "Unsupported or malformed GPT header"
        );

        byte[] shortHeader = createGPTDisk();
        ByteArrayAccess.writeIntLittleEndian(shortHeader, SECTOR_SIZE + 12, 91);
        assertRejected(
                shortHeader,
                "gpt-short-header.dmg",
                "Unsupported or malformed GPT header"
        );

        byte[] wrongCurrentLBA = createGPTDisk();
        ByteArrayAccess.writeLongLittleEndian(wrongCurrentLBA, SECTOR_SIZE + 24, 2L);
        rewriteGPTHeaderChecksum(wrongCurrentLBA);
        assertRejected(
                wrongCurrentLBA,
                "gpt-current-lba.dmg",
                "Unsupported or malformed GPT partition-entry array"
        );
    }

    /// Rejects unsupported GPT entry counts, entry sizes, and table locations.
    @Test
    void rejectsMalformedGUIDPartitionEntryArray() throws IOException {
        byte[] excessiveCount = createGPTDisk();
        ByteArrayAccess.writeIntLittleEndian(excessiveCount, SECTOR_SIZE + 80, 1_048_577);
        rewriteGPTHeaderChecksum(excessiveCount);
        assertRejected(
                excessiveCount,
                "gpt-entry-count.dmg",
                "Unsupported or malformed GPT partition-entry array"
        );

        byte[] shortEntry = createGPTDisk();
        ByteArrayAccess.writeIntLittleEndian(shortEntry, SECTOR_SIZE + 84, 120);
        rewriteGPTHeaderChecksum(shortEntry);
        assertRejected(
                shortEntry,
                "gpt-entry-size.dmg",
                "Unsupported or malformed GPT partition-entry array"
        );

        byte[] tableOutsideDisk = createGPTDisk();
        ByteArrayAccess.writeLongLittleEndian(tableOutsideDisk, SECTOR_SIZE + 72, 6L);
        rewriteGPTHeaderChecksum(tableOutsideDisk);
        assertRejected(
                tableOutsideDisk,
                "gpt-table-range.dmg",
                "GPT partition table range exceeds the disk image"
        );
    }

    /// Rejects inverted, unsupported unsigned, and out-of-disk GPT partition ranges.
    @Test
    void rejectsInvalidGUIDPartitionRanges() throws IOException {
        byte[] inverted = createGPTDisk();
        ByteArrayAccess.writeLongLittleEndian(inverted, 2 * SECTOR_SIZE + 32, 4L);
        ByteArrayAccess.writeLongLittleEndian(inverted, 2 * SECTOR_SIZE + 40, 3L);
        rewriteGPTChecksums(inverted);
        assertRejected(inverted, "gpt-inverted-range.dmg", "GPT partition has an inverted LBA range");

        byte[] unsigned = createGPTDisk();
        ByteArrayAccess.writeLongLittleEndian(unsigned, 2 * SECTOR_SIZE + 32, Long.MIN_VALUE);
        rewriteGPTChecksums(unsigned);
        assertRejected(
                unsigned,
                "gpt-unsigned-range.dmg",
                "GPT partition first LBA exceeds the supported signed 64-bit range"
        );

        byte[] outsideDisk = createGPTDisk();
        ByteArrayAccess.writeLongLittleEndian(outsideDisk, 2 * SECTOR_SIZE + 32, 5L);
        ByteArrayAccess.writeLongLittleEndian(outsideDisk, 2 * SECTOR_SIZE + 40, 6L);
        rewriteGPTChecksums(outsideDisk);
        assertRejected(outsideDisk, "gpt-partition-range.dmg", "GPT partition range exceeds the disk image");
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

    /// Rejects invalid APM driver block sizes, entry counts, and table ranges.
    @Test
    void rejectsInvalidApplePartitionMapGeometry() throws IOException {
        byte[] smallBlock = createApplePartitionMapDisk();
        ByteArrayAccess.writeShortBigEndian(smallBlock, 2, (short) 256);
        assertRejected(smallBlock, "apm-small-block.dmg", "Invalid Apple Partition Map block size: 256");

        byte[] nonPowerOfTwoBlock = createApplePartitionMapDisk();
        ByteArrayAccess.writeShortBigEndian(nonPowerOfTwoBlock, 2, (short) 768);
        assertRejected(
                nonPowerOfTwoBlock,
                "apm-non-power-of-two-block.dmg",
                "Invalid Apple Partition Map block size: 768"
        );

        byte[] emptyMap = createApplePartitionMapDisk();
        ByteArrayAccess.writeIntBigEndian(emptyMap, SECTOR_SIZE + 4, 0);
        assertRejected(emptyMap, "apm-empty.dmg", "Invalid Apple Partition Map entry count: 0");

        byte[] tableOutsideDisk = createApplePartitionMapDisk();
        ByteArrayAccess.writeIntBigEndian(tableOutsideDisk, SECTOR_SIZE + 4, 7);
        assertRejected(
                tableOutsideDisk,
                "apm-table-range.dmg",
                "Apple partition map range exceeds the disk image"
        );
    }

    /// Rejects an APM partition whose declared block range extends beyond the decoded disk.
    @Test
    void rejectsApplePartitionOutsideDisk() throws IOException {
        byte[] disk = createApplePartitionMapDisk();
        ByteArrayAccess.writeIntBigEndian(disk, 2 * SECTOR_SIZE + 8, 5);
        ByteArrayAccess.writeIntBigEndian(disk, 2 * SECTOR_SIZE + 12, 2);

        assertRejected(disk, "apm-partition-range.dmg", "Apple partition range exceeds the disk image");
    }

    /// Maps empty APM name and type fields to absent partition metadata.
    @Test
    void mapsEmptyApplePartitionMapStringsToNull() throws IOException {
        byte[] disk = createApplePartitionMapDisk();
        Arrays.fill(disk, 2 * SECTOR_SIZE + 16, 2 * SECTOR_SIZE + 80, (byte) 0);
        Path path = writeRawImage(temporaryDirectory.resolve("apm-empty-strings.dmg"), disk);

        try (DMGImage image = DMGImage.open(path)) {
            DMGPartition partition = image.partitions().get(1);
            assertNull(partition.name());
            assertNull(partition.type());
        }
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
        return DMGTestFixtures.createApplePartitionMapDisk(List.of(
                new DMGTestFixtures.ApplePartition(
                        "Café",
                        "Apple_HFS",
                        concatenate(new byte[][]{sector(51), sector(52)})
                ),
                new DMGTestFixtures.ApplePartition("Unused", "Apple_Free", new byte[0])
        ));
    }

    /// Returns the CRC-32 of an array range as its raw 32-bit representation.
    private static int crc32(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    /// Rewrites the GPT entry-array and header checksums after mutating an entry.
    private static void rewriteGPTChecksums(byte[] disk) {
        int tableOffset = 2 * SECTOR_SIZE;
        int tableLength = 2 * 128;
        ByteArrayAccess.writeIntLittleEndian(
                disk,
                SECTOR_SIZE + 88,
                crc32(disk, tableOffset, tableLength)
        );
        rewriteGPTHeaderChecksum(disk);
    }

    /// Rewrites the checksum of the generated 92-byte GPT header.
    private static void rewriteGPTHeaderChecksum(byte[] disk) {
        int headerOffset = SECTOR_SIZE;
        ByteArrayAccess.writeIntLittleEndian(disk, headerOffset + 16, 0);
        ByteArrayAccess.writeIntLittleEndian(
                disk,
                headerOffset + 16,
                crc32(disk, headerOffset, 92)
        );
    }

    /// Writes a mutated decoded disk and verifies that opening rejects it with the expected diagnostic.
    private void assertRejected(byte[] disk, String fileName, String expectedMessage) throws IOException {
        Path path = writeRawImage(temporaryDirectory.resolve(fileName), disk);
        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));
        assertTrue(exception.getMessage().contains(expectedMessage), exception::getMessage);
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
