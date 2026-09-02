// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createFragmentedHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies rejection of malformed HFS Plus extent and B-tree metadata through the public DMG API.
@NotNullByDefault
final class HFSPlusValidationTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

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
