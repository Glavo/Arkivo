// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.dmg.DMGPartition;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Parses one HFS Plus or HFSX volume contained in a DMG partition.
@NotNullByDefault
final class HFSPlusVolume {
    /// The HFS Plus volume-header offset from the partition start.
    private static final long VOLUME_HEADER_OFFSET = 1024L;

    /// The fixed HFS Plus volume-header size.
    private static final int VOLUME_HEADER_SIZE = 512;

    /// The signature-and-version prefix used to identify a supported volume header.
    private static final int VOLUME_IDENTIFICATION_SIZE = 4;

    /// The HFS Plus volume signature.
    private static final int HFS_PLUS_SIGNATURE = 0x482b;

    /// The HFSX volume signature.
    private static final int HFSX_SIGNATURE = 0x4858;

    /// The catalog node identifier of the root folder.
    private static final long ROOT_FOLDER_ID = 2L;

    /// The catalog node identifier of the catalog file.
    private static final long CATALOG_FILE_ID = 4L;

    /// The HFS epoch's offset from the Unix epoch, in seconds.
    private static final long HFS_EPOCH_OFFSET = 2_082_844_800L;

    /// The data-fork discriminator in an extents overflow key.
    private static final int DATA_FORK = 0;

    /// The resource-fork discriminator in an extents overflow key.
    private static final int RESOURCE_FORK = 0xff;

    /// The owning image used to open independent partition channels.
    private final UDIFImage image;

    /// The selected HFS Plus partition.
    private final DMGPartition partition;

    /// The allocation-block size.
    private final int blockSize;

    /// The volume's logical capacity.
    private final long capacity;

    /// The volume's recorded unallocated byte count.
    private final long unallocatedSpace;

    /// Indexed nodes keyed by normalized archive-local path.
    private final @Unmodifiable Map<String, HFSPlusNode> nodes;

    /// Creates one parsed HFS Plus volume.
    private HFSPlusVolume(
            UDIFImage image,
            DMGPartition partition,
            int blockSize,
            long capacity,
            long unallocatedSpace,
            Map<String, HFSPlusNode> nodes
    ) {
        this.image = image;
        this.partition = partition;
        this.blockSize = blockSize;
        this.capacity = capacity;
        this.unallocatedSpace = unallocatedSpace;
        this.nodes = Map.copyOf(nodes);
    }

    /// Returns whether a partition starts with a direct supported HFS Plus or HFSX volume header.
    ///
    /// @param image the open image
    /// @param partition the partition to probe
    /// @return {@code true} when the direct volume signature is recognized
    /// @throws IOException if the partition cannot be read
    static boolean matches(
            UDIFImage image,
            DMGPartition partition,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(tracker, "tracker");
        if (partition.size() < VOLUME_HEADER_OFFSET + VOLUME_IDENTIFICATION_SIZE) {
            return false;
        }
        tracker.acceptMetadata(VOLUME_IDENTIFICATION_SIZE, null);
        try (SeekableByteChannel channel = image.openPartition(partition)) {
            byte[] identification = ChannelIO.readBytes(
                    channel,
                    VOLUME_HEADER_OFFSET,
                    VOLUME_IDENTIFICATION_SIZE
            );
            int signature = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(identification, 0));
            int version = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(identification, 2));
            return signature == HFS_PLUS_SIGNATURE && version == 4
                    || signature == HFSX_SIGNATURE && version == 5;
        }
    }

    /// Parses the selected direct HFS Plus or HFSX volume.
    ///
    /// @param image the owning open DMG image
    /// @param partition the selected HFS Plus partition
    /// @param tracker the tracker shared by every metadata layer in the enclosing operation
    /// @return the indexed read-only volume
    /// @throws IOException if volume metadata is malformed, unsupported, or exceeds configured limits
    static HFSPlusVolume open(
            UDIFImage image,
            DMGPartition partition,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(tracker, "tracker");
        tracker.acceptMetadata(VOLUME_HEADER_SIZE, null);

        byte[] header;
        try (SeekableByteChannel channel = image.openPartition(partition)) {
            header = ChannelIO.readBytes(channel, VOLUME_HEADER_OFFSET, VOLUME_HEADER_SIZE);
        }
        int signature = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(header, 0));
        int version = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(header, 2));
        if (signature != HFS_PLUS_SIGNATURE || version != 4) {
            if (signature != HFSX_SIGNATURE || version != 5) {
                throw new IOException("Selected DMG partition is not a supported direct HFS Plus or HFSX volume");
            }
        }
        long blockSizeValue = uint32(header, 40);
        if (blockSizeValue < 512L || blockSizeValue > Integer.MAX_VALUE
                || (blockSizeValue & (blockSizeValue - 1L)) != 0L) {
            throw new IOException("Invalid HFS Plus allocation-block size: " + blockSizeValue);
        }
        int blockSize = Math.toIntExact(blockSizeValue);
        long totalBlocks = uint32(header, 44);
        long freeBlocks = uint32(header, 48);
        if (freeBlocks > totalBlocks) {
            throw new IOException("HFS Plus free-block count exceeds the volume size");
        }
        long capacity = ChannelIO.multiply(totalBlocks, blockSize, "HFS Plus volume capacity");
        if (capacity > partition.size()) {
            throw new IOException("HFS Plus volume capacity exceeds its DMG partition");
        }

        HFSPlusFork extentsFork = parseFork(header, 192, totalBlocks, blockSize);
        requireCompleteInitialFork(extentsFork, "HFS Plus extents overflow file");
        Map<ExtentKey, List<HFSPlusExtent>> overflowExtents = readOverflowExtents(
                image,
                partition,
                extentsFork,
                blockSize,
                totalBlocks,
                tracker
        );
        HFSPlusFork catalogFork = resolveFork(
                parseFork(header, 272, totalBlocks, blockSize),
                CATALOG_FILE_ID,
                DATA_FORK,
                overflowExtents,
                totalBlocks,
                blockSize
        );
        Map<String, HFSPlusNode> nodes = readCatalog(
                image,
                partition,
                catalogFork,
                blockSize,
                totalBlocks,
                overflowExtents,
                tracker
        );
        return new HFSPlusVolume(
                image,
                partition,
                blockSize,
                capacity,
                ChannelIO.multiply(freeBlocks, blockSize, "HFS Plus free space"),
                nodes
        );
    }

    /// Returns the selected partition.
    DMGPartition partition() {
        return partition;
    }

    /// Returns the volume capacity.
    long capacity() {
        return capacity;
    }

    /// Returns the recorded unallocated byte count.
    long unallocatedSpace() {
        return unallocatedSpace;
    }

    /// Returns an indexed node by normalized path, or `null` if absent.
    @Nullable HFSPlusNode node(String path) {
        return nodes.get(path);
    }

    /// Opens an owning channel over one node's data fork.
    SeekableByteChannel openDataFork(HFSPlusNode node) throws IOException {
        HFSPlusFork fork = node.fork();
        if (fork == null) {
            throw new IOException("HFS Plus directory has no data fork: " + node.path());
        }
        SeekableByteChannel partitionChannel = image.openPartition(partition);
        try {
            return HFSPlusForkChannel.open(partitionChannel, fork, blockSize);
        } catch (RuntimeException | Error exception) {
            try {
                partitionChannel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Reads all extents-overflow leaf records into an exact-key map.
    private static Map<ExtentKey, List<HFSPlusExtent>> readOverflowExtents(
            UDIFImage image,
            DMGPartition partition,
            HFSPlusFork extentsFork,
            int blockSize,
            long totalBlocks,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        HashMap<ExtentKey, List<HFSPlusExtent>> extents = new HashMap<>();
        try (SeekableByteChannel tree = HFSPlusForkChannel.open(
                image.openPartition(partition),
                extentsFork,
                blockSize
        )) {
            HFSPlusBTree.Header treeHeader = HFSPlusBTree.readHeader(tree, tracker);
            HFSPlusBTree.visitLeafRecords(tree, treeHeader, tracker, record -> {
                if (record.length < 12 + 8 * 8) {
                    throw new IOException("Truncated HFS Plus extents-overflow record");
                }
                int keyLength = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(record, 0));
                if (keyLength != 10) {
                    throw new IOException("Invalid HFS Plus extents-overflow key length");
                }
                int forkType = Byte.toUnsignedInt(record[2]);
                if (forkType != DATA_FORK && forkType != RESOURCE_FORK) {
                    throw new IOException("Invalid HFS Plus extents-overflow fork type");
                }
                ExtentKey key = new ExtentKey(forkType, uint32(record, 4), uint32(record, 8));
                List<HFSPlusExtent> value = parseExtentRecord(record, 12, totalBlocks);
                if (value.isEmpty() || extents.putIfAbsent(key, value) != null) {
                    throw new IOException("Duplicate or empty HFS Plus extents-overflow record");
                }
            });
        }
        return Map.copyOf(extents);
    }

    /// Reads catalog leaf records, resolves file extents, and builds normalized paths.
    private static Map<String, HFSPlusNode> readCatalog(
            UDIFImage image,
            DMGPartition partition,
            HFSPlusFork catalogFork,
            int blockSize,
            long totalBlocks,
            Map<ExtentKey, List<HFSPlusExtent>> overflowExtents,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        LinkedHashMap<Long, HFSPlusNode> byId = new LinkedHashMap<>();
        try (SeekableByteChannel tree = HFSPlusForkChannel.open(
                image.openPartition(partition),
                catalogFork,
                blockSize
        )) {
            HFSPlusBTree.Header treeHeader = HFSPlusBTree.readHeader(tree, tracker);
            HFSPlusBTree.visitLeafRecords(tree, treeHeader, tracker, record -> {
                HFSPlusNode node = parseCatalogRecord(
                        record,
                        totalBlocks,
                        blockSize,
                        overflowExtents
                );
                if (node != null && byId.putIfAbsent(node.id(), node) != null) {
                    throw new IOException("Duplicate HFS Plus catalog node ID " + node.id());
                }
            });
        }
        HFSPlusNode root = byId.get(ROOT_FOLDER_ID);
        if (root == null || !root.isDirectory()) {
            throw new IOException("HFS Plus catalog has no root folder record");
        }
        root.setPath("");
        LinkedHashMap<String, HFSPlusNode> byPath = new LinkedHashMap<>();
        byPath.put("", root);
        HashSet<Long> resolving = new HashSet<>();
        for (HFSPlusNode node : byId.values()) {
            if (node.id() != ROOT_FOLDER_ID) {
                resolvePath(node, byId, byPath, resolving);
            }
        }
        for (HFSPlusNode node : byPath.values()) {
            if (!node.path().isEmpty()) {
                tracker.acceptEntry(node.path(), node.size());
            }
        }
        return Map.copyOf(byPath);
    }

    /// Parses one catalog folder or file record, ignoring catalog thread records.
    private static @Nullable HFSPlusNode parseCatalogRecord(
            byte[] record,
            long totalBlocks,
            int blockSize,
            Map<ExtentKey, List<HFSPlusExtent>> overflowExtents
    ) throws IOException {
        if (record.length < 10) {
            throw new IOException("Truncated HFS Plus catalog record");
        }
        int keyLength = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(record, 0));
        if (keyLength < 6 || keyLength > record.length - 4 || (keyLength & 1) != 0) {
            throw new IOException("Invalid HFS Plus catalog key length");
        }
        long parentId = uint32(record, 2);
        int nameLength = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(record, 6));
        if (nameLength > 255 || keyLength != 6 + nameLength * 2) {
            throw new IOException("Invalid HFS Plus catalog name length");
        }
        int dataOffset = 2 + keyLength;
        int recordType = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(record, dataOffset));
        if (recordType == 3 || recordType == 4) {
            return null;
        }
        String name = mappedName(new String(record, 8, nameLength * 2, StandardCharsets.UTF_16BE));
        int required = recordType == 1 ? 88 : recordType == 2 ? 248 : -1;
        if (required < 0) {
            throw new IOException("Unknown HFS Plus catalog record type " + recordType);
        }
        if (record.length - dataOffset < required) {
            throw new IOException("Truncated HFS Plus catalog data record");
        }
        long id = uint32(record, dataOffset + 8);
        long ownerId = uint32(record, dataOffset + 32);
        long groupId = uint32(record, dataOffset + 36);
        int mode = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(record, dataOffset + 42));
        boolean directory = recordType == 1;
        boolean symbolicLink = !directory && PosixModes.isSymbolicLink(mode);
        boolean other = !directory && PosixModes.isOther(mode);
        HFSPlusFork fork = null;
        if (!directory) {
            fork = resolveFork(
                    parseFork(record, dataOffset + 88, totalBlocks, blockSize),
                    id,
                    DATA_FORK,
                    overflowExtents,
                    totalBlocks,
                    blockSize
            );
        }
        return new HFSPlusNode(
                id,
                parentId,
                name,
                directory,
                symbolicLink,
                other,
                fork,
                mode,
                ownerId,
                groupId,
                hfsTime(record, dataOffset + 16),
                hfsTime(record, dataOffset + 24),
                hfsTime(record, dataOffset + 12)
        );
    }

    /// Resolves and indexes one catalog node path recursively through its parent IDs.
    private static void resolvePath(
            HFSPlusNode node,
            Map<Long, HFSPlusNode> byId,
            Map<String, HFSPlusNode> byPath,
            Set<Long> resolving
    ) throws IOException {
        if (!node.path().isEmpty()) {
            return;
        }
        if (!resolving.add(node.id())) {
            throw new IOException("Cyclic HFS Plus catalog hierarchy at node " + node.id());
        }
        HFSPlusNode parent = byId.get(node.parentId());
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("HFS Plus catalog node has no directory parent: " + node.id());
        }
        if (parent.id() != ROOT_FOLDER_ID && parent.path().isEmpty()) {
            resolvePath(parent, byId, byPath, resolving);
        }
        String path = parent.path().isEmpty() ? node.name() : parent.path() + "/" + node.name();
        node.setPath(path);
        if (byPath.putIfAbsent(path, node) != null
                || parent.mutableChildren().putIfAbsent(node.name(), path) != null) {
            throw new IOException("Duplicate HFS Plus catalog path: " + path);
        }
        resolving.remove(node.id());
    }

    /// Parses one 80-byte HFS Plus fork record and validates its initial extents.
    private static HFSPlusFork parseFork(
            byte[] bytes,
            int offset,
            long volumeBlocks,
            int blockSize
    ) throws IOException {
        if (offset < 0 || offset > bytes.length - 80) {
            throw new IOException("Truncated HFS Plus fork data");
        }
        long logicalSize = uint64(bytes, offset, "HFS Plus fork logical size");
        long totalBlocks = uint32(bytes, offset + 12);
        if (totalBlocks > volumeBlocks
                || logicalSize > ChannelIO.multiply(totalBlocks, blockSize, "HFS Plus fork allocation size")) {
            throw new IOException("Invalid HFS Plus fork size");
        }
        List<HFSPlusExtent> extents = parseExtentRecord(bytes, offset + 16, volumeBlocks);
        long coveredBlocks = extentBlockCount(extents);
        if (coveredBlocks > totalBlocks) {
            throw new IOException("HFS Plus initial extents exceed the fork's block count");
        }
        return new HFSPlusFork(logicalSize, totalBlocks, extents);
    }

    /// Parses the eight descriptors in one HFS Plus extent record.
    private static List<HFSPlusExtent> parseExtentRecord(
            byte[] bytes,
            int offset,
            long volumeBlocks
    ) throws IOException {
        if (offset < 0 || offset > bytes.length - 64) {
            throw new IOException("Truncated HFS Plus extent record");
        }
        ArrayList<HFSPlusExtent> extents = new ArrayList<>();
        boolean sawEmpty = false;
        for (int index = 0; index < 8; index++) {
            long startBlock = uint32(bytes, offset + index * 8);
            long blockCount = uint32(bytes, offset + index * 8 + 4);
            if (blockCount == 0L) {
                sawEmpty = true;
                continue;
            }
            if (sawEmpty || ChannelIO.add(startBlock, blockCount, "HFS Plus extent range") > volumeBlocks) {
                throw new IOException("Invalid HFS Plus extent descriptor");
            }
            extents.add(new HFSPlusExtent(startBlock, blockCount));
        }
        return List.copyOf(extents);
    }

    /// Resolves a fork's continuation records from the extents overflow map.
    private static HFSPlusFork resolveFork(
            HFSPlusFork fork,
            long fileId,
            int forkType,
            Map<ExtentKey, List<HFSPlusExtent>> overflowExtents,
            long volumeBlocks,
            int blockSize
    ) throws IOException {
        ArrayList<HFSPlusExtent> extents = new ArrayList<>(fork.extents());
        long coveredBlocks = extentBlockCount(extents);
        while (coveredBlocks < fork.totalBlocks()) {
            List<HFSPlusExtent> continuation = overflowExtents.get(new ExtentKey(forkType, fileId, coveredBlocks));
            if (continuation == null || continuation.isEmpty()) {
                throw new IOException("Missing HFS Plus extents-overflow record for catalog node " + fileId);
            }
            long added = extentBlockCount(continuation);
            if (added == 0L || coveredBlocks > fork.totalBlocks() - added) {
                throw new IOException("HFS Plus extents-overflow records exceed the fork size");
            }
            for (HFSPlusExtent extent : continuation) {
                if (extent.endBlock() > volumeBlocks) {
                    throw new IOException("HFS Plus overflow extent exceeds the volume");
                }
                extents.add(extent);
            }
            coveredBlocks += added;
        }
        if (fork.logicalSize() > ChannelIO.multiply(coveredBlocks, blockSize, "HFS Plus resolved fork size")) {
            throw new IOException("HFS Plus extents do not cover the fork's logical size");
        }
        return fork.withExtents(extents);
    }

    /// Requires a special-file fork to be completely described by its initial extent record.
    private static void requireCompleteInitialFork(HFSPlusFork fork, String description) throws IOException {
        if (extentBlockCount(fork.extents()) != fork.totalBlocks()) {
            throw new IOException(description + " uses self-referential overflow extents, which are not supported");
        }
    }

    /// Returns the exact number of allocation blocks in an extent sequence.
    private static long extentBlockCount(List<HFSPlusExtent> extents) throws IOException {
        long count = 0L;
        for (HFSPlusExtent extent : extents) {
            count = ChannelIO.add(count, extent.blockCount(), "HFS Plus extent block count");
        }
        return count;
    }

    /// Maps an HFS Plus catalog name into one safe Arkivo path element.
    private static String mappedName(String name) throws IOException {
        String mapped = name.replace('/', ':').replace('\0', '\u2400');
        if (mapped.isEmpty() || ".".equals(mapped) || "..".equals(mapped)) {
            throw new IOException("HFS Plus catalog contains an unusable path name");
        }
        return mapped;
    }

    /// Converts an unsigned HFS timestamp into a Java file time.
    private static FileTime hfsTime(byte[] bytes, int offset) {
        return FileTime.from(Instant.ofEpochSecond(uint32(bytes, offset) - HFS_EPOCH_OFFSET));
    }

    /// Reads an unsigned big-endian 32-bit field.
    private static long uint32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, offset));
    }

    /// Reads an unsigned big-endian 64-bit field and rejects values above Java's signed range.
    private static long uint64(byte[] bytes, int offset, String description) throws IOException {
        long value = ByteArrayAccess.readLongBigEndian(bytes, offset);
        if (value < 0L) {
            throw new IOException(description + " exceeds the supported signed 64-bit range");
        }
        return value;
    }

    /// Stores one exact extents-overflow lookup key.
    ///
    /// @param forkType the data- or resource-fork discriminator
    /// @param fileId the catalog node identifier
    /// @param startBlock the fork-relative continuation block
    private record ExtentKey(int forkType, long fileId, long startBlock) {
    }
}
