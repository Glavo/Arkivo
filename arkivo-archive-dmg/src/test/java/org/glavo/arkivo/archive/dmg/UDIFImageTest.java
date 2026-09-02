// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Exercises generated flattened UDIF layouts without repository binary fixtures.
@NotNullByDefault
final class UDIFImageTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Decodes raw, sparse, ADC, zlib, BZip2, and XZ runs through random-access reads.
    @Test
    void decodesSupportedRuns() throws IOException {
        byte[][] sectors = new byte[6][];
        for (int index = 0; index < sectors.length; index++) {
            sectors[index] = sector(index + 1);
        }
        sectors[1] = new byte[512];
        Arrays.fill(sectors[2], (byte) 'A');

        List<EncodedRun> runs = List.of(
                new EncodedRun(0x00000001, sectors[0]),
                new EncodedRun(0x00000000, new byte[0]),
                new EncodedRun(0x80000004, adcRepeatedByte()),
                new EncodedRun(0x80000005, compress(ZlibCodec.DEFAULT, sectors[3])),
                new EncodedRun(0x80000006, compress(BZip2Codec.DEFAULT, sectors[4])),
                new EncodedRun(0x80000008, compress(XZCodec.DEFAULT, sectors[5]))
        );
        Path imagePath = writeImage(runs);
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
        Path imagePath = writeImage(List.of(new EncodedRun(0x00000001, sector(7))));
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
        Path imagePath = writeImage(List.of(new EncodedRun(0x00000001, sector(3))));
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumDecodedArchiveSize(511L).build();
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(limits);
        assertThrows(IOException.class, () -> DMGImage.open(imagePath, options));
    }

    /// Writes one generated flattened UDIF image to the temporary directory.
    private Path writeImage(List<EncodedRun> runs) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] table = new byte[204 + (runs.size() + 1) * 40];
        ByteArrayAccess.writeIntBigEndian(table, 0, 0x6d697368);
        ByteArrayAccess.writeIntBigEndian(table, 4, 1);
        ByteArrayAccess.writeLongBigEndian(table, 8, 0L);
        ByteArrayAccess.writeLongBigEndian(table, 16, runs.size());
        ByteArrayAccess.writeLongBigEndian(table, 24, 0L);
        ByteArrayAccess.writeIntBigEndian(table, 200, runs.size() + 1);
        for (int index = 0; index < runs.size(); index++) {
            EncodedRun run = runs.get(index);
            int offset = 204 + index * 40;
            ByteArrayAccess.writeIntBigEndian(table, offset, run.type());
            ByteArrayAccess.writeLongBigEndian(table, offset + 8, index);
            ByteArrayAccess.writeLongBigEndian(table, offset + 16, 1L);
            ByteArrayAccess.writeLongBigEndian(table, offset + 24, data.size());
            ByteArrayAccess.writeLongBigEndian(table, offset + 32, run.bytes().length);
            data.write(run.bytes());
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
        byte[] trailer = new byte[512];
        ByteArrayAccess.writeIntBigEndian(trailer, 0, 0x6b6f6c79);
        ByteArrayAccess.writeIntBigEndian(trailer, 4, 4);
        ByteArrayAccess.writeIntBigEndian(trailer, 8, 512);
        ByteArrayAccess.writeIntBigEndian(trailer, 12, 1);
        ByteArrayAccess.writeLongBigEndian(trailer, 24, 0L);
        ByteArrayAccess.writeLongBigEndian(trailer, 32, data.size());
        ByteArrayAccess.writeLongBigEndian(trailer, 216, data.size());
        ByteArrayAccess.writeLongBigEndian(trailer, 224, xml.length);
        ByteArrayAccess.writeLongBigEndian(trailer, 492, runs.size());

        Path path = temporaryDirectory.resolve("generated.dmg");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        data.writeTo(image);
        image.write(xml);
        image.write(trailer);
        Files.write(path, image.toByteArray());
        return path;
    }

    /// Returns a deterministic 512-byte sector.
    private static byte[] sector(int seed) {
        byte[] bytes = new byte[512];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed * 31 + index * 17);
        }
        return bytes;
    }

    /// Encodes one repeated-byte sector with literal, short-reference, and long-reference ADC chunks.
    private static byte[] adcRepeatedByte() {
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
    private static byte[] compress(CompressionCodec<?> codec, byte[] source) throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(source));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Concatenates byte arrays in order.
    private static byte[] concatenate(byte[][] arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    /// Reads a channel into the target until it is full.
    private static void readFully(SeekableByteChannel channel, ByteBuffer target) throws IOException {
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
    private record EncodedRun(int type, byte @Unmodifiable [] bytes) {
        /// Copies the encoded bytes into this immutable fixture description.
        private EncodedRun {
            bytes = bytes.clone();
        }

        /// Returns a defensive copy of the encoded physical bytes.
        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }
    }
}
