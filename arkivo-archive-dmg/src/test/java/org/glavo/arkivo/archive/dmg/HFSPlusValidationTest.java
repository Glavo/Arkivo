// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createFragmentedHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies rejection of malformed HFS Plus extent and B-tree metadata through the public DMG API.
@NotNullByDefault
final class HFSPlusValidationTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Rejects invalid allocation sizes, free-space counts, and capacities in the volume header.
    @Test
    void rejectsInvalidVolumeGeometry() throws IOException {
        int volumeHeader = 2 * SECTOR_SIZE;

        byte[] invalidBlockSize = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(invalidBlockSize, volumeHeader + 40, 768);
        assertRejected(invalidBlockSize, "invalid-block-size.dmg", "allocation-block size");

        byte[] excessiveFreeBlocks = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(excessiveFreeBlocks, volumeHeader + 48, 17);
        assertRejected(excessiveFreeBlocks, "excessive-free-blocks.dmg", "free-block count");

        byte[] excessiveCapacity = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(excessiveCapacity, volumeHeader + 44, 17);
        assertRejected(excessiveCapacity, "excessive-capacity.dmg", "capacity exceeds its DMG partition");
    }

    /// Rejects malformed logical sizes, allocation counts, and inline extent sequences in special forks.
    @Test
    void rejectsInvalidInitialForkGeometry() throws IOException {
        int volumeHeader = 2 * SECTOR_SIZE;
        int extentsFork = volumeHeader + 192;
        int catalogFork = volumeHeader + 272;

        byte[] unsignedLogicalSize = createHFSPlusDisk();
        ByteArrayAccess.writeLongBigEndian(unsignedLogicalSize, catalogFork, Long.MIN_VALUE);
        assertRejected(unsignedLogicalSize, "unsigned-fork-size.dmg", "logical size exceeds");

        byte[] excessiveLogicalSize = createHFSPlusDisk();
        ByteArrayAccess.writeLongBigEndian(excessiveLogicalSize, catalogFork, 3L * SECTOR_SIZE + 1L);
        assertRejected(excessiveLogicalSize, "excessive-fork-size.dmg", "Invalid HFS Plus fork size");

        byte[] extentOutsideVolume = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(extentOutsideVolume, catalogFork + 16, 15);
        ByteArrayAccess.writeIntBigEndian(extentOutsideVolume, catalogFork + 20, 2);
        assertRejected(extentOutsideVolume, "extent-outside-volume.dmg", "Invalid HFS Plus extent descriptor");

        byte[] extentAfterEmptyDescriptor = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(extentAfterEmptyDescriptor, catalogFork + 32, 9);
        ByteArrayAccess.writeIntBigEndian(extentAfterEmptyDescriptor, catalogFork + 36, 1);
        assertRejected(
                extentAfterEmptyDescriptor,
                "extent-after-empty.dmg",
                "Invalid HFS Plus extent descriptor"
        );

        byte[] excessiveInitialExtents = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(excessiveInitialExtents, catalogFork + 20, 4);
        assertRejected(
                excessiveInitialExtents,
                "excessive-initial-extents.dmg",
                "initial extents exceed the fork's block count"
        );

        byte[] selfReferentialExtentsTree = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(selfReferentialExtentsTree, extentsFork + 12, 2);
        assertRejected(
                selfReferentialExtentsTree,
                "self-referential-extents-tree.dmg",
                "uses self-referential overflow extents"
        );
    }

    /// Rejects invalid node-zero descriptors, node geometry, roots, and leaf-map references.
    @Test
    void rejectsMalformedBTreeHeaders() throws IOException {
        int extentsTree = 3 * SECTOR_SIZE;
        int extentsHeaderRecord = extentsTree + 14;
        int catalogTree = 4 * SECTOR_SIZE;
        int catalogHeaderRecord = catalogTree + 14;

        byte[] wrongNodeKind = createHFSPlusDisk();
        wrongNodeKind[extentsTree + 8] = 0;
        assertRejected(wrongNodeKind, "wrong-header-kind.dmg", "node zero is not a header node");

        byte[] invalidNodeSize = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(invalidNodeSize, extentsTree + 32, (short) 256);
        assertRejected(invalidNodeSize, "invalid-node-size.dmg", "Invalid HFS Plus B-tree node size");

        byte[] nodeLargerThanFork = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(nodeLargerThanFork, extentsTree + 32, (short) 1024);
        assertRejected(nodeLargerThanFork, "node-larger-than-fork.dmg", "header node exceeds its fork");

        byte[] invalidHeaderOffset = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(
                invalidHeaderOffset,
                extentsTree + SECTOR_SIZE - Short.BYTES,
                (short) 13
        );
        assertRejected(invalidHeaderOffset, "invalid-header-offset.dmg", "header-record offset");

        byte[] invalidGeometry = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(invalidGeometry, extentsHeaderRecord + 22, 2);
        assertRejected(invalidGeometry, "invalid-tree-geometry.dmg", "Invalid HFS Plus B-tree geometry");

        byte[] linkedEmptyTree = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(linkedEmptyTree, extentsHeaderRecord + 10, 1);
        assertRejected(linkedEmptyTree, "linked-empty-tree.dmg", "Empty HFS Plus B-tree has linked leaf nodes");

        byte[] invalidRoot = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(invalidRoot, extentsHeaderRecord + 2, 1);
        assertRejected(invalidRoot, "invalid-tree-root.dmg", "Invalid HFS Plus B-tree root node");

        byte[] leafOutsideMap = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(leafOutsideMap, catalogHeaderRecord + 10, 3);
        assertRejected(leafOutsideMap, "leaf-outside-node-map.dmg", "leaf chain is outside the node map");
    }

    /// Rejects leaf chains whose node kinds, links, or actual record counts contradict the header.
    @Test
    void rejectsInconsistentLeafChains() throws IOException {
        int catalogHeaderRecord = 4 * SECTOR_SIZE + 14;
        int firstCatalogLeaf = 5 * SECTOR_SIZE;

        byte[] nonLeafNode = createHFSPlusDisk();
        nonLeafNode[firstCatalogLeaf + 8] = 0;
        assertRejected(nonLeafNode, "non-leaf-catalog-node.dmg", "references a non-leaf node");

        byte[] moreRecordsThanDeclared = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(moreRecordsThanDeclared, catalogHeaderRecord + 6, 2);
        assertRejected(
                moreRecordsThanDeclared,
                "too-many-leaf-records.dmg",
                "contains more leaf records than declared"
        );

        byte[] fewerRecordsThanDeclared = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(fewerRecordsThanDeclared, catalogHeaderRecord + 6, 4);
        assertRejected(fewerRecordsThanDeclared, "too-few-leaf-records.dmg", "leaf-record count mismatch");

        byte[] earlyEnd = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(earlyEnd, firstCatalogLeaf, 0);
        assertRejected(earlyEnd, "early-leaf-end.dmg", "ends before its declared last node");

        byte[] linkOutsideMap = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(linkOutsideMap, firstCatalogLeaf, 3);
        assertRejected(linkOutsideMap, "leaf-link-outside-map.dmg", "Invalid or cyclic HFS Plus B-tree leaf chain");
    }

    /// Rejects leaf nodes whose reverse offset tables cannot fit or address their record regions.
    @Test
    void rejectsMalformedLeafRecordTables() throws IOException {
        int firstCatalogLeaf = 5 * SECTOR_SIZE;

        byte[] oversizedTable = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(oversizedTable, firstCatalogLeaf + 10, (short) 0xffff);
        assertRejected(
                oversizedTable,
                "oversized-catalog-record-table.dmg",
                "HFS Plus B-tree record table exceeds its node"
        );

        byte[] descriptorOverlap = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(
                descriptorOverlap,
                firstCatalogLeaf + SECTOR_SIZE - Short.BYTES,
                (short) 13
        );
        assertRejected(
                descriptorOverlap,
                "catalog-record-overlaps-descriptor.dmg",
                "Invalid HFS Plus B-tree record offset"
        );
    }

    /// Rejects truncated or internally inconsistent catalog keys, records, and fork sizes.
    @Test
    void rejectsMalformedCatalogRecords() throws IOException {
        int firstCatalogLeaf = 5 * SECTOR_SIZE;
        int rootRecord = firstCatalogLeaf + 14;
        int fileRecord = firstCatalogLeaf + 118;

        byte[] truncatedRecord = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(
                truncatedRecord,
                firstCatalogLeaf + SECTOR_SIZE - 2 * Short.BYTES,
                (short) 22
        );
        assertRejected(truncatedRecord, "truncated-catalog-record.dmg", "Truncated HFS Plus catalog record");

        byte[] invalidKeyLength = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(invalidKeyLength, rootRecord, (short) 5);
        assertRejected(invalidKeyLength, "invalid-catalog-key.dmg", "Invalid HFS Plus catalog key length");

        byte[] invalidNameLength = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(invalidNameLength, rootRecord + 6, (short) 5);
        assertRejected(invalidNameLength, "invalid-catalog-name.dmg", "Invalid HFS Plus catalog name length");

        byte[] unknownRecordType = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(unknownRecordType, rootRecord + 16, (short) 9);
        assertRejected(unknownRecordType, "unknown-catalog-type.dmg", "Unknown HFS Plus catalog record type");

        byte[] truncatedDataRecord = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(
                truncatedDataRecord,
                firstCatalogLeaf + SECTOR_SIZE - 2 * Short.BYTES,
                (short) 80
        );
        assertRejected(truncatedDataRecord, "truncated-catalog-data.dmg", "Truncated HFS Plus catalog data record");

        byte[] unsignedFileSize = createHFSPlusDisk();
        ByteArrayAccess.writeLongBigEndian(unsignedFileSize, fileRecord + 114, Long.MIN_VALUE);
        assertRejected(unsignedFileSize, "unsigned-file-size.dmg", "logical size exceeds");
    }

    /// Rejects missing roots, duplicate identifiers, invalid parents, cycles, and duplicate resolved paths.
    @Test
    void rejectsInvalidCatalogHierarchies() throws IOException {
        int rootRecord = 5 * SECTOR_SIZE + 14;
        int fileRecord = 5 * SECTOR_SIZE + 118;
        int linkRecord = 6 * SECTOR_SIZE + 14;

        byte[] missingRoot = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(missingRoot, rootRecord + 24, 99);
        assertRejected(missingRoot, "missing-root.dmg", "catalog has no root folder record");

        byte[] nonDirectoryRoot = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(nonDirectoryRoot, rootRecord + 24, 99);
        ByteArrayAccess.writeIntBigEndian(nonDirectoryRoot, fileRecord + 34, 2);
        assertRejected(nonDirectoryRoot, "non-directory-root.dmg", "catalog has no root folder record");

        byte[] duplicateId = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(duplicateId, fileRecord + 34, 2);
        assertRejected(duplicateId, "duplicate-node-id.dmg", "Duplicate HFS Plus catalog node ID 2");

        byte[] missingParent = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(missingParent, fileRecord + 2, 999);
        assertRejected(missingParent, "missing-directory-parent.dmg", "has no directory parent");

        byte[] nonDirectoryParent = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(nonDirectoryParent, linkRecord + 2, 16);
        assertRejected(nonDirectoryParent, "non-directory-parent.dmg", "has no directory parent");

        byte[] cyclicHierarchy = createHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(cyclicHierarchy, fileRecord + 2, 17);
        ByteArrayAccess.writeShortBigEndian(cyclicHierarchy, fileRecord + 26, (short) 1);
        ByteArrayAccess.writeIntBigEndian(cyclicHierarchy, linkRecord + 2, 16);
        ByteArrayAccess.writeShortBigEndian(cyclicHierarchy, linkRecord + 16, (short) 1);
        assertRejected(cyclicHierarchy, "cyclic-hierarchy.dmg", "Cyclic HFS Plus catalog hierarchy");

        byte[] duplicatePath = createHFSPlusDisk();
        byte[] duplicateName = "hello.txt".getBytes(StandardCharsets.UTF_16BE);
        System.arraycopy(duplicatePath, linkRecord + 16, duplicatePath, linkRecord + 26, 88);
        ByteArrayAccess.writeShortBigEndian(duplicatePath, linkRecord, (short) 24);
        ByteArrayAccess.writeShortBigEndian(duplicatePath, linkRecord + 6, (short) 9);
        System.arraycopy(duplicateName, 0, duplicatePath, linkRecord + 8, duplicateName.length);
        ByteArrayAccess.writeShortBigEndian(duplicatePath, linkRecord + 26, (short) 1);
        assertRejected(duplicatePath, "duplicate-catalog-path.dmg", "Duplicate HFS Plus catalog path: hello.txt");
    }

    /// Rejects empty and reserved catalog path elements before hierarchy indexing.
    @Test
    void rejectsUnusableCatalogNames() throws IOException {
        int rootRecord = 5 * SECTOR_SIZE + 14;

        byte[] emptyName = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(emptyName, rootRecord, (short) 6);
        ByteArrayAccess.writeShortBigEndian(emptyName, rootRecord + 6, (short) 0);
        assertRejected(emptyName, "empty-catalog-name.dmg", "catalog contains an unusable path name");

        byte[] dotName = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(dotName, rootRecord, (short) 8);
        ByteArrayAccess.writeShortBigEndian(dotName, rootRecord + 6, (short) 1);
        dotName[rootRecord + 8] = 0;
        dotName[rootRecord + 9] = '.';
        assertRejected(dotName, "dot-catalog-name.dmg", "catalog contains an unusable path name");

        byte[] dotDotName = createHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(dotDotName, rootRecord, (short) 10);
        ByteArrayAccess.writeShortBigEndian(dotDotName, rootRecord + 6, (short) 2);
        dotDotName[rootRecord + 8] = 0;
        dotDotName[rootRecord + 9] = '.';
        dotDotName[rootRecord + 10] = 0;
        dotDotName[rootRecord + 11] = '.';
        assertRejected(dotDotName, "dot-dot-catalog-name.dmg", "catalog contains an unusable path name");
    }

    /// Rejects malformed and duplicate extents-overflow records before fork resolution.
    @Test
    void rejectsMalformedOverflowRecords() throws IOException {
        int firstRecord = 4 * SECTOR_SIZE + 14;
        int secondRecord = firstRecord + 76;

        byte[] invalidKeyLength = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeShortBigEndian(invalidKeyLength, firstRecord, (short) 8);
        assertRejected(invalidKeyLength, "invalid-overflow-key.dmg", "Invalid HFS Plus extents-overflow key length");

        byte[] emptyExtentRecord = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(emptyExtentRecord, firstRecord + 16, 0);
        assertRejected(emptyExtentRecord, "empty-overflow-record.dmg", "Duplicate or empty HFS Plus extents-overflow record");

        byte[] extentAfterEmptyDescriptor = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(extentAfterEmptyDescriptor, firstRecord + 28, 25);
        ByteArrayAccess.writeIntBigEndian(extentAfterEmptyDescriptor, firstRecord + 32, 1);
        assertRejected(
                extentAfterEmptyDescriptor,
                "sparse-overflow-record.dmg",
                "Invalid HFS Plus extent descriptor"
        );

        byte[] duplicateKey = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(duplicateKey, secondRecord + 4, 4);
        assertRejected(duplicateKey, "duplicate-overflow-key.dmg", "Duplicate or empty HFS Plus extents-overflow record");
    }

    /// Rejects a catalog fork whose required continuation record has the wrong logical start block.
    @Test
    void rejectsMissingCatalogOverflowExtent() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        int firstOverflowRecord = 4 * SECTOR_SIZE + 14;
        ByteArrayAccess.writeIntBigEndian(disk, firstOverflowRecord + 8, 2);

        assertRejected(disk, "missing-catalog-overflow.dmg", "Missing HFS Plus extents-overflow record");
    }

    /// Rejects continuation extents whose combined size exceeds the catalog fork allocation.
    @Test
    void rejectsOversizedCatalogOverflowExtent() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        int firstOverflowRecord = 4 * SECTOR_SIZE + 14;
        ByteArrayAccess.writeIntBigEndian(disk, firstOverflowRecord + 16, 2);

        assertRejected(disk, "oversized-catalog-overflow.dmg", "extents-overflow records exceed the fork size");
    }

    /// Rejects a file fork whose second continuation record does not begin at the exact covered-block count.
    @Test
    void rejectsMissingSecondFileOverflowExtent() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        int thirdOverflowRecord = 4 * SECTOR_SIZE + 14 + 2 * 76;
        ByteArrayAccess.writeIntBigEndian(disk, thirdOverflowRecord + 8, 15);

        assertRejected(disk, "missing-second-file-overflow.dmg", "Missing HFS Plus extents-overflow record");
    }

    /// Rejects an extents-overflow record containing an unsupported fork discriminator.
    @Test
    void rejectsInvalidOverflowForkType() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        int secondOverflowRecord = 4 * SECTOR_SIZE + 14 + 76;
        disk[secondOverflowRecord + 2] = 1;

        assertRejected(disk, "invalid-overflow-fork.dmg", "Invalid HFS Plus extents-overflow fork type");
    }

    /// Rejects a linked catalog leaf chain that returns to an already visited node.
    @Test
    void rejectsCyclicCatalogLeafChain() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(disk, 6 * SECTOR_SIZE, 1);

        assertRejected(disk, "cyclic-catalog-leaves.dmg", "cyclic HFS Plus B-tree leaf chain");
    }

    /// Rejects a catalog leaf whose backward link does not identify the preceding leaf.
    @Test
    void rejectsInconsistentCatalogBackwardLink() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        ByteArrayAccess.writeIntBigEndian(disk, 6 * SECTOR_SIZE + 4, 0);

        assertRejected(disk, "inconsistent-catalog-back-link.dmg", "backward leaf link");
    }

    /// Rejects a catalog leaf carrying a non-leaf node height.
    @Test
    void rejectsInvalidCatalogLeafHeight() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        disk[8 * SECTOR_SIZE + 9] = 2;

        assertRejected(disk, "invalid-catalog-leaf-height.dmg", "leaf-node height");
    }

    /// Rejects a catalog node whose reverse offset table is not monotonically ordered.
    @Test
    void rejectsUnorderedCatalogRecordOffsets() throws IOException {
        byte[] disk = createFragmentedHFSPlusDisk();
        int firstCatalogLeaf = 8 * SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, firstCatalogLeaf + SECTOR_SIZE - 4, (short) 13);

        assertRejected(disk, "unordered-catalog-offsets.dmg", "Unordered HFS Plus B-tree record offsets");
    }

    /// Writes and opens one malformed disk, requiring the supplied diagnostic fragment.
    private void assertRejected(byte[] disk, String name, String expectedMessage) throws IOException {
        Path image = writeRawImage(temporaryDirectory.resolve(name), disk);
        IOException exception = assertThrows(IOException.class, () -> {
            try (DMGArkivoFileSystem ignored = DMGArkivoFileSystem.open(image)) {
                // Opening performs the complete metadata traversal under test.
            }
        });
        assertTrue(exception.getMessage().contains(expectedMessage), exception::getMessage);
    }
}
