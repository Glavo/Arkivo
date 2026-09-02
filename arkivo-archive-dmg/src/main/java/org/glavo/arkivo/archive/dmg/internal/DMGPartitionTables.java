// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.dmg.DMGPartition;
import org.glavo.arkivo.archive.dmg.DMGPartitionScheme;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.Checksums;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Discovers GUID and Apple partition maps in a decoded DMG disk.
@NotNullByDefault
final class DMGPartitionTables {
    /// The GPT header signature encoded as two little-endian words.
    private static final byte @Unmodifiable [] GPT_SIGNATURE =
            "EFI PART".getBytes(StandardCharsets.US_ASCII);

    /// The maximum partition count accepted from one table.
    private static final long MAXIMUM_PARTITION_COUNT = 1_048_576L;

    /// The Macintosh Roman encoding used by Apple Partition Map strings.
    private static final Charset MAC_ROMAN = Charset.forName("x-MacRoman");

    /// Creates no instances.
    private DMGPartitionTables() {
    }

    /// Discovers the partition layout, falling back to one raw whole-disk partition.
    ///
    /// @param disk the decoded disk channel borrowed for parsing
    /// @param tracker the operation-wide metadata tracker
    /// @return immutable partitions in their table order
    /// @throws IOException if a recognized partition table is malformed or exceeds configured limits
    static @Unmodifiable List<DMGPartition> read(
            SeekableByteChannel disk,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        List<DMGPartition> gpt = readGPT(disk, tracker);
        if (gpt != null) {
            return gpt;
        }
        List<DMGPartition> apple = readApplePartitionMap(disk, tracker);
        if (apple != null) {
            return apple;
        }
        return List.of(new DMGPartition(0, 0L, disk.size(), null, null, DMGPartitionScheme.RAW));
    }

    /// Reads a GUID Partition Table or returns `null` when its signature is absent.
    private static @Nullable List<DMGPartition> readGPT(
            SeekableByteChannel disk,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        if (disk.size() < 2L * UDIFConstants.SECTOR_SIZE) {
            return null;
        }
        byte[] sector = ChannelIO.readBytes(disk, UDIFConstants.SECTOR_SIZE, UDIFConstants.SECTOR_SIZE);
        for (int index = 0; index < GPT_SIGNATURE.length; index++) {
            if (sector[index] != GPT_SIGNATURE[index]) {
                return null;
            }
        }

        long revision = uint32LE(sector, 8);
        long headerSize = uint32LE(sector, 12);
        if (revision != 0x0001_0000L || headerSize < 92L || headerSize > sector.length) {
            throw new IOException("Unsupported or malformed GPT header");
        }
        tracker.acceptMetadata(headerSize, null);
        int storedHeaderCRC = ByteArrayAccess.readIntLittleEndian(sector, 16);
        byte[] header = sector.clone();
        ByteArrayAccess.writeIntLittleEndian(header, 16, 0);
        int computedHeaderCRC = Checksums.CRC32.computeInt(header, 0, Math.toIntExact(headerSize));
        if (storedHeaderCRC != computedHeaderCRC) {
            throw new IOException("GPT header CRC32 mismatch");
        }

        long currentLBA = uint64LE(sector, 24, "GPT current LBA");
        long partitionEntryLBA = uint64LE(sector, 72, "GPT partition-entry LBA");
        long partitionCount = uint32LE(sector, 80);
        long entrySize = uint32LE(sector, 84);
        int storedTableCRC = ByteArrayAccess.readIntLittleEndian(sector, 88);
        if (currentLBA != 1L || partitionCount > MAXIMUM_PARTITION_COUNT
                || entrySize < 128L || entrySize > 4096L || (entrySize & 7L) != 0L) {
            throw new IOException("Unsupported or malformed GPT partition-entry array");
        }
        long tableOffset = ChannelIO.multiply(partitionEntryLBA, UDIFConstants.SECTOR_SIZE, "GPT table offset");
        long tableLength = ChannelIO.multiply(partitionCount, entrySize, "GPT table length");
        ChannelIO.requireRange(tableOffset, tableLength, disk.size(), "GPT partition table");
        tracker.acceptMetadata(tableLength, null);

        ChecksumAccumulator.Width32 checksum = Checksums.CRC32.newAccumulator();
        ArrayList<DMGPartition> partitions = new ArrayList<>();
        int encodedEntrySize = Math.toIntExact(entrySize);
        for (long slot = 0L; slot < partitionCount; slot++) {
            long entryOffset = ChannelIO.add(
                    tableOffset,
                    ChannelIO.multiply(slot, entrySize, "GPT entry offset"),
                    "GPT entry absolute offset"
            );
            byte[] entry = ChannelIO.readBytes(disk, entryOffset, encodedEntrySize);
            checksum.update(entry);
            if (allZero(entry, 0, 16)) {
                continue;
            }
            long firstLBA = uint64LE(entry, 32, "GPT partition first LBA");
            long lastLBA = uint64LE(entry, 40, "GPT partition last LBA");
            if (lastLBA < firstLBA) {
                throw new IOException("GPT partition has an inverted LBA range");
            }
            long offset = ChannelIO.multiply(firstLBA, UDIFConstants.SECTOR_SIZE, "GPT partition offset");
            long sectors = ChannelIO.add(lastLBA - firstLBA, 1L, "GPT partition sector count");
            long size = ChannelIO.multiply(sectors, UDIFConstants.SECTOR_SIZE, "GPT partition size");
            ChannelIO.requireRange(offset, size, disk.size(), "GPT partition");
            String name = decodeUTF16LE(entry, 56, Math.min(entry.length - 56, 72));
            partitions.add(new DMGPartition(
                    partitions.size(),
                    offset,
                    size,
                    name.isEmpty() ? null : name,
                    guid(entry),
                    DMGPartitionScheme.GUID_PARTITION_TABLE
            ));
        }
        if (checksum.finishInt() != storedTableCRC) {
            throw new IOException("GPT partition-table CRC32 mismatch");
        }
        return List.copyOf(partitions);
    }

    /// Reads an Apple Partition Map or returns `null` when its signature is absent.
    private static @Nullable List<DMGPartition> readApplePartitionMap(
            SeekableByteChannel disk,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        if (disk.size() < 1024L) {
            return null;
        }
        byte[] driverDescriptor = ChannelIO.readBytes(disk, 0L, UDIFConstants.SECTOR_SIZE);
        int blockSize = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(driverDescriptor, 2));
        if (Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(driverDescriptor, 0)) != 0x4552) {
            blockSize = UDIFConstants.SECTOR_SIZE;
        } else if (blockSize < UDIFConstants.SECTOR_SIZE || (blockSize & (blockSize - 1)) != 0) {
            throw new IOException("Invalid Apple Partition Map block size: " + blockSize);
        }
        if (disk.size() < 2L * blockSize) {
            return null;
        }
        byte[] first = ChannelIO.readBytes(disk, blockSize, blockSize);
        if (Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(first, 0)) != 0x504d) {
            return null;
        }
        long partitionCount = uint32BE(first, 4);
        if (partitionCount == 0L || partitionCount > MAXIMUM_PARTITION_COUNT) {
            throw new IOException("Invalid Apple Partition Map entry count: " + partitionCount);
        }
        long tableLength = ChannelIO.multiply(partitionCount, blockSize, "Apple partition-map length");
        ChannelIO.requireRange(blockSize, tableLength, disk.size(), "Apple partition map");
        tracker.acceptMetadata(tableLength, null);

        ArrayList<DMGPartition> partitions = new ArrayList<>();
        for (long slot = 0L; slot < partitionCount; slot++) {
            byte[] entry = slot == 0L
                    ? first
                    : ChannelIO.readBytes(disk, ChannelIO.multiply(slot + 1L, blockSize, "Apple map offset"), blockSize);
            if (Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(entry, 0)) != 0x504d
                    || uint32BE(entry, 4) != partitionCount) {
                throw new IOException("Malformed Apple Partition Map entry " + slot);
            }
            long startBlock = uint32BE(entry, 8);
            long blockCount = uint32BE(entry, 12);
            if (blockCount == 0L) {
                continue;
            }
            long offset = ChannelIO.multiply(startBlock, blockSize, "Apple partition offset");
            long size = ChannelIO.multiply(blockCount, blockSize, "Apple partition size");
            ChannelIO.requireRange(offset, size, disk.size(), "Apple partition");
            String name = decodeCString(entry, 16, 32);
            String type = decodeCString(entry, 48, 32);
            partitions.add(new DMGPartition(
                    partitions.size(),
                    offset,
                    size,
                    name.isEmpty() ? null : name,
                    type.isEmpty() ? null : type,
                    DMGPartitionScheme.APPLE_PARTITION_MAP
            ));
        }
        return List.copyOf(partitions);
    }

    /// Returns the canonical text representation of a GPT type GUID.
    private static String guid(byte[] entry) {
        long first = uint32LE(entry, 0);
        int second = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(entry, 4));
        int third = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(entry, 6));
        return String.format(
                Locale.ROOT,
                "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                first,
                second,
                third,
                Byte.toUnsignedInt(entry[8]),
                Byte.toUnsignedInt(entry[9]),
                Byte.toUnsignedInt(entry[10]),
                Byte.toUnsignedInt(entry[11]),
                Byte.toUnsignedInt(entry[12]),
                Byte.toUnsignedInt(entry[13]),
                Byte.toUnsignedInt(entry[14]),
                Byte.toUnsignedInt(entry[15])
        );
    }

    /// Decodes a fixed-size UTF-16LE string and removes its NUL suffix.
    private static String decodeUTF16LE(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = offset + (length & ~1);
        while (end + 1 < limit && (bytes[end] != 0 || bytes[end + 1] != 0)) {
            end += 2;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_16LE);
    }

    /// Decodes a Macintosh Roman NUL-terminated partition-map string.
    private static String decodeCString(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, MAC_ROMAN);
    }

    /// Returns whether an array range contains only zero bytes.
    private static boolean allZero(byte[] bytes, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /// Reads an unsigned little-endian 32-bit field.
    private static long uint32LE(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(bytes, offset));
    }

    /// Reads an unsigned big-endian 32-bit field.
    private static long uint32BE(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, offset));
    }

    /// Reads a little-endian unsigned 64-bit field and rejects values above Java's signed range.
    private static long uint64LE(byte[] bytes, int offset, String description) throws IOException {
        long value = ByteArrayAccess.readLongLittleEndian(bytes, offset);
        if (value < 0L) {
            throw new IOException(description + " exceeds the supported signed 64-bit range");
        }
        return value;
    }
}
