// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies DMG compatibility against fixtures downloaded from the pinned libdmg-hfsplus source tree.
@NotNullByDefault
final class LibDMGCorpusTest {
    /// The property containing the prepared libdmg-hfsplus source root.
    private static final String SOURCE_DIRECTORY_PROPERTY = "arkivo.libdmgHfsplus.testDataDirectory";

    /// Decodes representative UDIF images to their upstream HFS partition reference bytes.
    @Test
    void decodesReferenceImages() throws IOException {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        for (Fixture fixture : List.of(
                new Fixture("test/attribution_reference/hdiutila.hfs.dmg", "test/attribution_reference/hdiutila.hfs"),
                new Fixture("test/attribution_reference/hdiutilb.hfs.dmg", "test/attribution_reference/hdiutilb.hfs"),
                new Fixture("test/run_spanning_reference/hdiutila.hfs.dmg", "test/run_spanning_reference/hdiutila.hfs")
        )) {
            Path dmgPath = root.resolve(fixture.dmg());
            byte[] expected = Files.readAllBytes(root.resolve(fixture.hfs()));
            try (DMGImage image = DMGImage.open(dmgPath)) {
                DMGPartition hfs = hfsPartition(image);
                byte[] actual = new byte[Math.toIntExact(hfs.size())];
                try (SeekableByteChannel channel = image.openPartition(hfs)) {
                    readFully(channel, ByteBuffer.wrap(actual));
                }
                assertArrayEquals(expected, actual, fixture.dmg());
            }
        }
    }

    /// Opens an upstream image as a file system and verifies its selected partition and root.
    @Test
    void opensHFSPlusFileSystem() throws IOException {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        Path dmgPath = root.resolve("test/attribution_reference/hdiutila.hfs.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(dmgPath)) {
            assertTrue(Files.isDirectory(fileSystem.getPath("/")));
            assertTrue(fileSystem.partition().size() > 0L);
            assertEquals("hfsplus", Files.getFileStore(fileSystem.getPath("/")).type());
            try (SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/x"))) {
                assertInstanceOf(InterruptibleChannel.class, channel);
            }
        }
    }

    /// Opens a DMG through JDK path-provider discovery.
    @Test
    void opensThroughInstalledFileSystemProvider() throws IOException {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        Path dmgPath = root.resolve("test/attribution_reference/hdiutila.hfs.dmg");
        try (FileSystem fileSystem = FileSystems.newFileSystem(dmgPath)) {
            assertEquals(DMGArkivoFormat.instance().uriScheme(), fileSystem.provider().getScheme());
            assertEquals("content-x\n", Files.readString(fileSystem.getPath("/x"), StandardCharsets.UTF_8));
        }
    }

    /// Applies one cumulative metadata limit across UDIF and HFS Plus parsing.
    @Test
    void enforcesMetadataLimitAcrossImageLayers() {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        Path dmgPath = root.resolve("test/attribution_reference/hdiutila.hfs.dmg");
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumMetadataSize(512L).build();
        ArchiveReadOptions common = ArchiveReadOptions.DEFAULT.withLimits(limits);
        DMGArchiveOptions.Read options = DMGArchiveOptions.READ_DEFAULTS.withCommon(common);

        ArkivoReadLimitException exception = assertThrows(
                ArkivoReadLimitException.class,
                () -> DMGArkivoFileSystem.open(dmgPath, options)
        );
        assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
    }

    /// Opens every DMG fixture in the upstream source tree as an HFS Plus file system.
    @Test
    void opensAllReferenceImages() throws IOException {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        for (String relativePath : List.of(
                "test/attribution_reference/hdiutila.hfs.dmg",
                "test/attribution_reference/hdiutilb.hfs.dmg",
                "test/hfs_xattrs_reference/hdiutil.hfs.dmg",
                "test/hfs_xattrs_reference/hdiutila.hfs.dmg",
                "test/hfs_xattrs_reference/hdiutilab.hfs.dmg",
                "test/hfs_xattrs_reference/hdiutilb.hfs.dmg",
                "test/run_spanning_reference/hdiutila.hfs.dmg"
        )) {
            try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(root.resolve(relativePath))) {
                assertTrue(Files.isDirectory(fileSystem.getPath("/")), relativePath);
            }
        }
    }

    /// Reads catalog file data through HFS Plus extents and on-demand UDIF decompression.
    @Test
    void readsHFSPlusFileContents() throws IOException {
        Path root = Path.of(System.getProperty(SOURCE_DIRECTORY_PROPERTY));
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(
                root.resolve("test/run_spanning_reference/hdiutila.hfs.dmg")
        )) {
            assertEquals("content-x\n", Files.readString(fileSystem.getPath("/x"), StandardCharsets.UTF_8));
        }
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(
                root.resolve("test/hfs_xattrs_reference/hdiutilab.hfs.dmg")
        )) {
            assertEquals("content-a\n", Files.readString(fileSystem.getPath("/a"), StandardCharsets.UTF_8));
            assertEquals("content-b\n", Files.readString(fileSystem.getPath("/b"), StandardCharsets.UTF_8));
        }
    }

    /// Returns whether one decoded partition has a direct HFS Plus signature.
    private static boolean hasHFSPlusSignature(DMGImage image, DMGPartition partition) {
        if (partition.size() < 1026L) {
            return false;
        }
        try (SeekableByteChannel channel = image.openPartition(partition)) {
            channel.position(1024L);
            ByteBuffer signature = ByteBuffer.allocate(2);
            readFully(channel, signature);
            return signature.array()[0] == 'H' && (signature.array()[1] == '+' || signature.array()[1] == 'X');
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Returns the first direct HFS Plus partition in a fixture.
    private static DMGPartition hfsPartition(DMGImage image) {
        for (DMGPartition partition : image.partitions()) {
            if (hasHFSPlusSignature(image, partition)) {
                return partition;
            }
        }
        throw new AssertionError("Fixture has no HFS Plus partition");
    }

    /// Reads a target buffer completely.
    private static void readFully(SeekableByteChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new IOException("Unexpected end of fixture");
            }
            if (read == 0) {
                throw new IOException("Fixture read made no progress");
            }
        }
    }

    /// Stores one upstream encoded image and its decoded HFS reference path.
    ///
    /// @param dmg the source-root-relative DMG path
    /// @param hfs the source-root-relative decoded HFS path
    private record Fixture(String dmg, String hfs) {
    }
}
