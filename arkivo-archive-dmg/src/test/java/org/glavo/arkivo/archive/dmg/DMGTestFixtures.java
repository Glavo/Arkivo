// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/// Creates deterministic binary-free UDIF fixtures shared by DMG tests.
@NotNullByDefault
final class DMGTestFixtures {
    /// The UDIF raw run type.
    static final int RAW_RUN = 0x0000_0001;

    /// The UDIF sparse run type.
    static final int SPARSE_RUN = 0x0000_0000;

    /// The UDIF ignored zero-filled run type.
    static final int IGNORE_RUN = 0x0000_0002;

    /// The UDIF ADC run type.
    static final int ADC_RUN = 0x8000_0004;

    /// The UDIF zlib run type.
    static final int ZLIB_RUN = 0x8000_0005;

    /// The UDIF BZip2 run type.
    static final int BZIP2_RUN = 0x8000_0006;

    /// The historical UDIF XZ run type.
    static final int XZ_RUN = 0x8000_0008;

    /// The decoded UDIF sector size.
    static final int SECTOR_SIZE = 512;

    /// Prevents utility-class construction.
    private DMGTestFixtures() {
    }

    /// Writes one flattened UDIF image containing the supplied single-sector runs.
    ///
    /// @param path the output path
    /// @param runs the encoded runs in logical sector order
    /// @return `path`
    /// @throws IOException if the fixture cannot be written
    static Path writeImage(Path path, List<Run> runs) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] table = new byte[204 + (runs.size() + 1) * 40];
        ByteArrayAccess.writeIntBigEndian(table, 0, 0x6d69_7368);
        ByteArrayAccess.writeIntBigEndian(table, 4, 1);
        ByteArrayAccess.writeLongBigEndian(table, 8, 0L);
        ByteArrayAccess.writeLongBigEndian(table, 16, runs.size());
        ByteArrayAccess.writeLongBigEndian(table, 24, 0L);
        ByteArrayAccess.writeIntBigEndian(table, 200, runs.size() + 1);
        for (int index = 0; index < runs.size(); index++) {
            Run run = runs.get(index);
            int offset = 204 + index * 40;
            byte[] encoded = run.bytes();
            ByteArrayAccess.writeIntBigEndian(table, offset, run.type());
            ByteArrayAccess.writeLongBigEndian(table, offset + 8, index);
            ByteArrayAccess.writeLongBigEndian(table, offset + 16, 1L);
            ByteArrayAccess.writeLongBigEndian(table, offset + 24, data.size());
            ByteArrayAccess.writeLongBigEndian(table, offset + 32, encoded.length);
            data.write(encoded);
        }
        int terminator = 204 + runs.size() * 40;
        ByteArrayAccess.writeIntBigEndian(table, terminator, 0xffff_ffff);
        ByteArrayAccess.writeLongBigEndian(table, terminator + 8, runs.size());

        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<plist version=\"1.0\"><dict><key>resource-fork</key><dict>"
                + "<key>blkx</key><array><dict><key>Data</key><data>"
                + Base64.getEncoder().encodeToString(table)
                + "</data></dict></array></dict></dict></plist>";
        byte[] xml = plist.getBytes(StandardCharsets.UTF_8);
        byte[] trailer = new byte[SECTOR_SIZE];
        ByteArrayAccess.writeIntBigEndian(trailer, 0, 0x6b6f_6c79);
        ByteArrayAccess.writeIntBigEndian(trailer, 4, 4);
        ByteArrayAccess.writeIntBigEndian(trailer, 8, SECTOR_SIZE);
        ByteArrayAccess.writeIntBigEndian(trailer, 12, 1);
        ByteArrayAccess.writeLongBigEndian(trailer, 24, 0L);
        ByteArrayAccess.writeLongBigEndian(trailer, 32, data.size());
        ByteArrayAccess.writeLongBigEndian(trailer, 216, data.size());
        ByteArrayAccess.writeLongBigEndian(trailer, 224, xml.length);
        ByteArrayAccess.writeLongBigEndian(trailer, 492, runs.size());

        ByteArrayOutputStream image = new ByteArrayOutputStream();
        data.writeTo(image);
        image.write(xml);
        image.write(trailer);
        Files.write(path, image.toByteArray());
        return path;
    }

    /// Writes a raw-sector UDIF fixture for one decoded disk.
    ///
    /// @param path the output path
    /// @param disk the decoded disk whose length is a multiple of [#SECTOR_SIZE]
    /// @return `path`
    /// @throws IllegalArgumentException if the disk is not sector aligned
    /// @throws IOException if the fixture cannot be written
    static Path writeRawImage(Path path, byte[] disk) throws IOException {
        if (disk.length % SECTOR_SIZE != 0) {
            throw new IllegalArgumentException("disk length must be a multiple of 512 bytes");
        }
        ArrayList<Run> runs = new ArrayList<>(disk.length / SECTOR_SIZE);
        for (int offset = 0; offset < disk.length; offset += SECTOR_SIZE) {
            byte[] sector = new byte[SECTOR_SIZE];
            System.arraycopy(disk, offset, sector, 0, sector.length);
            runs.add(new Run(RAW_RUN, sector));
        }
        return writeImage(path, runs);
    }

    /// Returns a deterministic sector for the supplied seed.
    ///
    /// @param seed the byte-pattern seed
    /// @return a new sector
    static byte[] sector(int seed) {
        byte[] bytes = new byte[SECTOR_SIZE];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed * 31 + index * 17);
        }
        return bytes;
    }

    /// Encodes one repeated-byte sector with literal, short-reference, and long-reference ADC chunks.
    ///
    /// @return the encoded ADC bytes
    static byte[] adcRepeatedByte() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x80);
        output.write('A');
        output.write(0x3c);
        output.write(0);
        for (int index = 0; index < 7; index++) {
            output.write(0x7f);
            output.write(0);
            output.write(0);
        }
        output.write(0x54);
        output.write(0);
        output.write(0);
        return output.toByteArray();
    }

    /// Compresses one complete source array with an Arkivo codec.
    ///
    /// @param codec the codec used to encode the fixture
    /// @param source the uncompressed bytes
    /// @return the encoded bytes
    /// @throws IOException if encoding fails
    static byte[] compress(CompressionCodec<?> codec, byte[] source) throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(source));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Concatenates byte arrays in order.
    ///
    /// @param arrays the arrays to concatenate
    /// @return one newly allocated concatenation
    static byte[] concatenate(byte[][] arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    /// Creates a minimal HFS Plus disk with one regular file and one symbolic link.
    ///
    /// The volume contains an empty extents-overflow tree and a catalog whose three records span two linked leaf nodes.
    ///
    /// @return a new sixteen-sector HFS Plus disk
    static byte[] createHFSPlusDisk() {
        byte[] disk = new byte[16 * SECTOR_SIZE];
        int volumeHeader = 2 * SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader, (short) 0x482b);
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader + 2, (short) 4);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 40, SECTOR_SIZE);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 44, 16);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 48, 7);
        writeFork(disk, volumeHeader + 192, SECTOR_SIZE, 1, 3, 1);
        writeFork(disk, volumeHeader + 272, 3L * SECTOR_SIZE, 3, 4, 3);

        writeBTreeHeader(disk, 3 * SECTOR_SIZE, 0, 0, 0, 0, 0, 1, 10);
        writeBTreeHeader(disk, 4 * SECTOR_SIZE, 1, 1, 3, 1, 2, 3, 516);

        byte[] root = catalogRecord(1L, "Root", 1, 2L, 0040755, 501L, 20L, 0L, 0, 0, 0);
        byte[] file = catalogRecord(2L, "hello.txt", 2, 16L, 0100644, 501L, 20L, 5L, 1, 7, 1);
        byte[] link = catalogRecord(2L, "link", 2, 17L, 0120777, 501L, 20L, 9L, 1, 8, 1);
        writeLeafNode(disk, 5 * SECTOR_SIZE, 2, 0, List.of(root, file));
        writeLeafNode(disk, 6 * SECTOR_SIZE, 0, 1, List.of(link));
        System.arraycopy("hello".getBytes(StandardCharsets.UTF_8), 0, disk, 7 * SECTOR_SIZE, 5);
        System.arraycopy("hello.txt".getBytes(StandardCharsets.UTF_8), 0, disk, 8 * SECTOR_SIZE, 9);
        return disk;
    }

    /// Creates an HFS Plus disk whose catalog and regular-file forks require overflow extents.
    ///
    /// The catalog's linked leaf nodes and the regular file's seventeen logical blocks are deliberately stored in
    /// non-adjacent, non-monotonic allocation extents. The extents-overflow tree itself remains completely described
    /// by the volume header, as required by the reader.
    ///
    /// @return a new sixty-four-sector fragmented HFS Plus disk
    static byte[] createFragmentedHFSPlusDisk() {
        byte[] disk = new byte[64 * SECTOR_SIZE];
        int volumeHeader = 2 * SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader, (short) 0x482b);
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader + 2, (short) 4);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 40, SECTOR_SIZE);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 44, 64);
        ByteArrayAccess.writeIntBigEndian(disk, volumeHeader + 48, 35);

        writeFork(disk, volumeHeader + 192, 2L * SECTOR_SIZE, 2, 3, 2);
        List<Extent> catalogInitialExtents = List.of(
                new Extent(5, 1),
                new Extent(8, 1),
                new Extent(6, 1),
                new Extent(13, 1),
                new Extent(15, 1),
                new Extent(17, 1),
                new Extent(19, 1),
                new Extent(21, 1)
        );
        writeFork(disk, volumeHeader + 272, 9L * SECTOR_SIZE, 9, catalogInitialExtents);

        writeBTreeHeader(disk, 3 * SECTOR_SIZE, 1, 1, 3, 1, 1, 2, 10);
        byte[] catalogExtents = extentOverflowRecord(
                4,
                8,
                List.of(new Extent(23, 1))
        );
        List<Extent> middleFileExtents = List.of(
                new Extent(24, 1),
                new Extent(26, 1),
                new Extent(28, 1),
                new Extent(30, 1),
                new Extent(32, 1),
                new Extent(34, 1),
                new Extent(36, 1),
                new Extent(38, 1)
        );
        byte[] middleFileExtentRecord = extentOverflowRecord(
                16,
                8,
                middleFileExtents
        );
        byte[] finalFileExtentRecord = extentOverflowRecord(
                16,
                16,
                List.of(new Extent(40, 1))
        );
        writeLeafNode(
                disk,
                4 * SECTOR_SIZE,
                0,
                0,
                List.of(catalogExtents, middleFileExtentRecord, finalFileExtentRecord)
        );

        writeBTreeHeader(disk, 5 * SECTOR_SIZE, 1, 1, 3, 1, 2, 9, 516);
        byte[] root = catalogRecord(1L, "Root", 1, 2L, 0040755, 501L, 20L, 0L, 0, 0, 0);
        byte[] contents = fragmentedFileContents();
        List<Extent> fileInitialExtents = List.of(
                new Extent(10, 1),
                new Extent(12, 1),
                new Extent(9, 1),
                new Extent(14, 1),
                new Extent(16, 1),
                new Extent(18, 1),
                new Extent(20, 1),
                new Extent(22, 1)
        );
        byte[] file = catalogRecord(
                2L,
                "fragmented.bin",
                2,
                16L,
                0100644,
                501L,
                20L,
                contents.length,
                17,
                fileInitialExtents
        );
        byte[] link = catalogRecord(2L, "link", 2, 17L, 0120777, 501L, 20L, 14L, 1, 11, 1);
        writeLeafNode(disk, 8 * SECTOR_SIZE, 2, 0, List.of(root, file));
        writeLeafNode(disk, 6 * SECTOR_SIZE, 0, 1, List.of(link));

        ArrayList<Extent> fileExtentsInOrder = new ArrayList<>(fileInitialExtents);
        fileExtentsInOrder.addAll(middleFileExtents);
        fileExtentsInOrder.add(new Extent(40, 1));
        for (int index = 0; index < fileExtentsInOrder.size(); index++) {
            int sourceOffset = index * SECTOR_SIZE;
            int length = Math.min(SECTOR_SIZE, contents.length - sourceOffset);
            System.arraycopy(
                    contents,
                    sourceOffset,
                    disk,
                    fileExtentsInOrder.get(index).startBlock() * SECTOR_SIZE,
                    length
            );
        }
        System.arraycopy("fragmented.bin".getBytes(StandardCharsets.UTF_8), 0, disk, 11 * SECTOR_SIZE, 14);
        return disk;
    }

    /// Returns the deterministic contents stored across the fragmented fixture's seventeen extents.
    ///
    /// @return a new byte array containing the expected regular-file contents
    static byte[] fragmentedFileContents() {
        byte[] contents = new byte[16 * SECTOR_SIZE + 37];
        for (int index = 0; index < contents.length; index++) {
            contents[index] = (byte) (index * 73 + index / SECTOR_SIZE * 19);
        }
        return contents;
    }

    /// Writes one HFS Plus fork-data record with one extent.
    private static void writeFork(
            byte[] target,
            int offset,
            long logicalSize,
            int totalBlocks,
            int startBlock,
            int blockCount
    ) {
        writeFork(
                target,
                offset,
                logicalSize,
                totalBlocks,
                blockCount == 0 ? List.of() : List.of(new Extent(startBlock, blockCount))
        );
    }

    /// Writes one HFS Plus fork-data record with up to eight inline extents.
    private static void writeFork(
            byte[] target,
            int offset,
            long logicalSize,
            int totalBlocks,
            List<Extent> extents
    ) {
        if (extents.size() > 8) {
            throw new IllegalArgumentException("a fork record cannot contain more than eight inline extents");
        }
        ByteArrayAccess.writeLongBigEndian(target, offset, logicalSize);
        ByteArrayAccess.writeIntBigEndian(target, offset + 12, totalBlocks);
        for (int index = 0; index < extents.size(); index++) {
            Extent extent = extents.get(index);
            ByteArrayAccess.writeIntBigEndian(target, offset + 16 + index * 8, extent.startBlock());
            ByteArrayAccess.writeIntBigEndian(target, offset + 20 + index * 8, extent.blockCount());
        }
    }

    /// Writes a B-tree header node and its first record offset.
    private static void writeBTreeHeader(
            byte[] target,
            int offset,
            int treeDepth,
            int rootNode,
            int leafRecords,
            int firstLeafNode,
            int lastLeafNode,
            int totalNodes,
            int maximumKeyLength
    ) {
        target[offset + 8] = 1;
        ByteArrayAccess.writeShortBigEndian(target, offset + 10, (short) 1);
        int header = offset + 14;
        ByteArrayAccess.writeShortBigEndian(target, header, (short) treeDepth);
        ByteArrayAccess.writeIntBigEndian(target, header + 2, rootNode);
        ByteArrayAccess.writeIntBigEndian(target, header + 6, leafRecords);
        ByteArrayAccess.writeIntBigEndian(target, header + 10, firstLeafNode);
        ByteArrayAccess.writeIntBigEndian(target, header + 14, lastLeafNode);
        ByteArrayAccess.writeShortBigEndian(target, header + 18, (short) SECTOR_SIZE);
        ByteArrayAccess.writeShortBigEndian(target, header + 20, (short) maximumKeyLength);
        ByteArrayAccess.writeIntBigEndian(target, header + 22, totalNodes);
        ByteArrayAccess.writeShortBigEndian(target, offset + SECTOR_SIZE - 2, (short) 14);
    }

    /// Creates one HFS Plus catalog folder or file record.
    private static byte[] catalogRecord(
            long parentId,
            String name,
            int recordType,
            long id,
            int mode,
            long ownerId,
            long groupId,
            long logicalSize,
            int totalBlocks,
            int startBlock,
            int blockCount
    ) {
        return catalogRecord(
                parentId,
                name,
                recordType,
                id,
                mode,
                ownerId,
                groupId,
                logicalSize,
                totalBlocks,
                blockCount == 0 ? List.of() : List.of(new Extent(startBlock, blockCount))
        );
    }

    /// Creates one HFS Plus catalog folder or file record with explicitly supplied inline extents.
    private static byte[] catalogRecord(
            long parentId,
            String name,
            int recordType,
            long id,
            int mode,
            long ownerId,
            long groupId,
            long logicalSize,
            int totalBlocks,
            List<Extent> extents
    ) {
        byte[] encodedName = name.getBytes(StandardCharsets.UTF_16BE);
        int keyLength = 6 + encodedName.length;
        int dataOffset = 2 + keyLength;
        int dataSize = recordType == 1 ? 88 : 248;
        byte[] record = new byte[dataOffset + dataSize];
        ByteArrayAccess.writeShortBigEndian(record, 0, (short) keyLength);
        ByteArrayAccess.writeIntBigEndian(record, 2, Math.toIntExact(parentId));
        ByteArrayAccess.writeShortBigEndian(record, 6, (short) (encodedName.length / 2));
        System.arraycopy(encodedName, 0, record, 8, encodedName.length);
        ByteArrayAccess.writeShortBigEndian(record, dataOffset, (short) recordType);
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 8, Math.toIntExact(id));
        int hfsTime = (int) (2_082_844_800L + 1_700_000_000L);
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 12, hfsTime);
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 16, hfsTime);
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 24, hfsTime);
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 32, Math.toIntExact(ownerId));
        ByteArrayAccess.writeIntBigEndian(record, dataOffset + 36, Math.toIntExact(groupId));
        ByteArrayAccess.writeShortBigEndian(record, dataOffset + 42, (short) mode);
        if (recordType == 2) {
            writeFork(record, dataOffset + 88, logicalSize, totalBlocks, extents);
        }
        return record;
    }

    /// Creates one extents-overflow leaf record for a data fork.
    private static byte[] extentOverflowRecord(
            int fileId,
            int startBlock,
            List<Extent> extents
    ) {
        if (extents.isEmpty() || extents.size() > 8) {
            throw new IllegalArgumentException("an extent-overflow record must contain one to eight extents");
        }
        byte[] record = new byte[12 + 8 * 8];
        ByteArrayAccess.writeShortBigEndian(record, 0, (short) 10);
        record[2] = 0;
        ByteArrayAccess.writeIntBigEndian(record, 4, fileId);
        ByteArrayAccess.writeIntBigEndian(record, 8, startBlock);
        for (int index = 0; index < extents.size(); index++) {
            Extent extent = extents.get(index);
            ByteArrayAccess.writeIntBigEndian(record, 12 + index * 8, extent.startBlock());
            ByteArrayAccess.writeIntBigEndian(record, 16 + index * 8, extent.blockCount());
        }
        return record;
    }

    /// Writes one HFS Plus leaf node with an ordered reverse offset table.
    private static void writeLeafNode(
            byte[] target,
            int offset,
            int forwardLink,
            int backwardLink,
            List<byte[]> records
    ) {
        byte[] node = new byte[SECTOR_SIZE];
        ByteArrayAccess.writeIntBigEndian(node, 0, forwardLink);
        ByteArrayAccess.writeIntBigEndian(node, 4, backwardLink);
        node[8] = (byte) 0xff;
        node[9] = 1;
        ByteArrayAccess.writeShortBigEndian(node, 10, (short) records.size());
        int recordOffset = 14;
        ByteArrayAccess.writeShortBigEndian(node, SECTOR_SIZE - 2, (short) recordOffset);
        for (int index = 0; index < records.size(); index++) {
            byte[] record = records.get(index);
            System.arraycopy(record, 0, node, recordOffset, record.length);
            recordOffset += record.length;
            ByteArrayAccess.writeShortBigEndian(
                    node,
                    SECTOR_SIZE - (index + 2) * Short.BYTES,
                    (short) recordOffset
            );
        }
        int offsetTableStart = SECTOR_SIZE - (records.size() + 1) * Short.BYTES;
        if (recordOffset > offsetTableStart) {
            throw new IllegalArgumentException("B-tree records exceed one generated leaf node");
        }
        System.arraycopy(node, 0, target, offset, node.length);
    }

    /// Describes one generated allocation extent.
    ///
    /// @param startBlock the physical allocation-block index
    /// @param blockCount the number of contiguous allocation blocks
    @NotNullByDefault
    private record Extent(int startBlock, int blockCount) {
    }

    /// Reads a channel into the target until it is full.
    ///
    /// @param channel the source channel
    /// @param target the destination buffer
    /// @throws IOException if the channel ends or makes no progress before the target is full
    static void readFully(SeekableByteChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new IOException("Unexpected end of generated image");
            }
            if (read == 0) {
                throw new IOException("Generated image read made no progress");
            }
        }
    }

    /// Stores one generated run's type and encoded bytes.
    ///
    /// @param type the UDIF run type
    /// @param bytes the encoded physical bytes
    @NotNullByDefault
    record Run(int type, byte @Unmodifiable [] bytes) {
        /// Copies the encoded bytes into this immutable fixture description.
        Run {
            bytes = bytes.clone();
        }

        /// Returns a defensive copy of the encoded physical bytes.
        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }
    }
}
