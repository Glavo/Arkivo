// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createApplePartitionMapDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies automatic and explicit DMG partition selection through the public file-system API.
@NotNullByDefault
final class DMGPartitionSelectionTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Skips non-HFS partitions during automatic selection and validates explicitly selected partitions.
    @Test
    void selectsOnlyDirectHfsPartitions() throws IOException {
        byte[] disk = createApplePartitionMapDisk(List.of(
                new DMGTestFixtures.ApplePartition("Padding", "Apple_Free", new byte[SECTOR_SIZE]),
                new DMGTestFixtures.ApplePartition("Data", "Apple_HFS", createHFSPlusDisk())
        ));
        Path image = writeRawImage(temporaryDirectory.resolve("partitioned-hfs.dmg"), disk);

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(image)) {
            assertEquals(2, fileSystem.partition().index());
            assertEquals(DMGPartitionScheme.APPLE_PARTITION_MAP, fileSystem.partition().scheme());
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(
                image,
                DMGArchiveOptions.DEFAULT.withPartitionIndex(2)
        )) {
            assertEquals("Data", fileSystem.partition().name());
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }

        IOException exception = assertThrows(
                IOException.class,
                () -> DMGArkivoFileSystem.open(
                        image,
                        DMGArchiveOptions.DEFAULT.withPartitionIndex(0)
                )
        );
        assertEquals(
                "Selected DMG partition does not contain a direct HFS Plus or HFSX volume",
                exception.getMessage()
        );
    }

    /// Distinguishes an unsupported APFS partition from an image with no recognized file system.
    @Test
    void diagnosesUnsupportedPartitionLayouts() throws IOException {
        byte[] apfsDisk = createApplePartitionMapDisk(List.of(
                new DMGTestFixtures.ApplePartition("Container", "aPpLe_ApFs", new byte[3 * SECTOR_SIZE])
        ));
        Path apfsImage = writeRawImage(temporaryDirectory.resolve("apfs.dmg"), apfsDisk);
        IOException apfsException = assertThrows(
                IOException.class,
                () -> DMGArkivoFileSystem.open(apfsImage)
        );
        assertEquals("DMG contains APFS, which is not supported", apfsException.getMessage());

        Path unknownImage = writeRawImage(
                temporaryDirectory.resolve("unknown-file-system.dmg"),
                new byte[3 * SECTOR_SIZE]
        );
        IOException unknownException = assertThrows(
                IOException.class,
                () -> DMGArkivoFileSystem.open(unknownImage)
        );
        assertEquals(
                "DMG contains no supported direct HFS Plus or HFSX partition",
                unknownException.getMessage()
        );
    }
}
