// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ADC_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.BZIP2_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.IGNORE_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.RAW_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SPARSE_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.XZ_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ZLIB_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.adcRepeatedByte;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.compress;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.concatenate;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises generated flattened UDIF layouts without repository binary fixtures.
@NotNullByDefault
final class UDIFImageTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Decodes raw, sparse, ADC, zlib, BZip2, and XZ runs through random-access reads.
    @Test
    void decodesSupportedRuns() throws IOException {
        byte[][] sectors = new byte[7][];
        for (int index = 0; index < sectors.length; index++) {
            sectors[index] = sector(index + 1);
        }
        sectors[1] = new byte[512];
        sectors[2] = new byte[512];
        Arrays.fill(sectors[3], (byte) 'A');

        List<DMGTestFixtures.Run> runs = List.of(
                new DMGTestFixtures.Run(RAW_RUN, sectors[0]),
                new DMGTestFixtures.Run(SPARSE_RUN, new byte[0]),
                new DMGTestFixtures.Run(IGNORE_RUN, new byte[0]),
                new DMGTestFixtures.Run(ADC_RUN, adcRepeatedByte()),
                new DMGTestFixtures.Run(ZLIB_RUN, compress(ZlibCodec.DEFAULT, sectors[4])),
                new DMGTestFixtures.Run(BZIP2_RUN, compress(BZip2Codec.DEFAULT, sectors[5])),
                new DMGTestFixtures.Run(XZ_RUN, compress(XZCodec.DEFAULT, sectors[6]))
        );
        Path imagePath = writeImage(temporaryDirectory.resolve("runs.dmg"), runs);
        byte[] expected = concatenate(sectors);

        try (DMGImage image = DMGImage.open(imagePath);
             SeekableByteChannel channel = image.openChannel()) {
            assertInstanceOf(InterruptibleChannel.class, channel);
            assertEquals(expected.length, image.size());
            assertEquals(List.of(new DMGPartition(
                    0,
                    0L,
                    expected.length,
                    null,
                    null,
                    DMGPartitionScheme.RAW
            )), image.partitions());
            channel.position(377L);
            ByteBuffer actual = ByteBuffer.allocate(expected.length - 377);
            readFully(channel, actual);
            assertArrayEquals(java.util.Arrays.copyOfRange(expected, 377, expected.length), actual.array());
            try (SeekableByteChannel partition = image.openPartition(image.partitions().get(0))) {
                assertInstanceOf(InterruptibleChannel.class, partition);
            }
        }
    }

    /// Detects a trailer-identified DMG and restores the borrowed channel position.
    @Test
    void detectsSeekableTrailerWithoutChangingPosition() throws IOException {
        Path imagePath = writeImage(
                temporaryDirectory.resolve("detect.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(7)))
        );
        try (SeekableByteChannel channel = Files.newByteChannel(imagePath, StandardOpenOption.READ)) {
            channel.position(3L);
            assertSame(DMGArkivoFormat.instance(), ArkivoFormats.detect(channel));
            assertEquals(3L, channel.position());
        }
        assertFalse(DMGArkivoFormat.instance().matches(ByteBuffer.wrap(Files.readAllBytes(imagePath))));
    }

    /// Enforces the decoded archive-size limit before opening data channels.
    @Test
    void enforcesDecodedSizeLimit() throws IOException {
        Path imagePath = writeImage(
                temporaryDirectory.resolve("limited.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(3)))
        );
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumDecodedArchiveSize(511L).build();
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(limits);
        assertThrows(IOException.class, () -> DMGImage.open(imagePath, options));
    }

    /// Enforces compressed-run memory limits when the run is first requested.
    @Test
    void enforcesCompressedRunMemoryLimitLazily() throws IOException {
        byte[] encoded = compress(ZlibCodec.DEFAULT, sector(11));
        Path imagePath = writeImage(
                temporaryDirectory.resolve("memory-limited.dmg"),
                List.of(new DMGTestFixtures.Run(ZLIB_RUN, encoded))
        );
        ArchiveReadLimits limits = ArchiveReadLimits.builder()
                .maximumDecoderMemorySize(511L + encoded.length)
                .build();
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(limits);

        try (DMGImage image = DMGImage.open(imagePath, options);
             SeekableByteChannel channel = image.openChannel()) {
            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertTrue(exception.getMessage().contains("UDIF compressed run requires"));
        }
    }

    /// Reports unsupported LZFSE only when data from that run is requested.
    @Test
    void reportsUnsupportedLZFSEOnRead() throws IOException {
        Path imagePath = writeImage(
                temporaryDirectory.resolve("lzfse.dmg"),
                List.of(new DMGTestFixtures.Run(0x8000_0007, new byte[]{0}))
        );

        try (DMGImage image = DMGImage.open(imagePath);
             SeekableByteChannel channel = image.openChannel()) {
            assertEquals(DMGTestFixtures.SECTOR_SIZE, image.size());
            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertEquals("LZFSE-compressed UDIF runs are not supported", exception.getMessage());
        }
    }

    /// Implements the read-only seekable-channel lifecycle and validates partition ownership.
    @Test
    void implementsReadOnlyChannelLifecycle() throws IOException {
        Path imagePath = writeImage(
                temporaryDirectory.resolve("channel-lifecycle.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(13)))
        );
        DMGImage image = DMGImage.open(imagePath);
        assertTrue(image.isOpen());
        DMGPartition partition = image.partitions().get(0);

        try (SeekableByteChannel channel = image.openChannel()) {
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertSame(channel, channel.position(image.size()));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertSame(channel, channel.position(image.size() + 17L));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
            assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.wrap(new byte[]{1})));
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));
        }

        DMGPartition foreign = new DMGPartition(
                partition.index(),
                partition.offset(),
                partition.size() - 1L,
                partition.name(),
                partition.type(),
                partition.scheme()
        );
        assertThrows(IllegalArgumentException.class, () -> image.openPartition(foreign));
        image.close();
        image.close();
        assertFalse(image.isOpen());
        assertThrows(ClosedChannelException.class, image::openChannel);
        assertThrows(ClosedChannelException.class, () -> image.openPartition(partition));
    }
}
