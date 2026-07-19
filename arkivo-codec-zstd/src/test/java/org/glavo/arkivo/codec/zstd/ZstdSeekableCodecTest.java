// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies standard seek-table production, parsing, and logical random access.
@NotNullByDefault
public final class ZstdSeekableCodecTest {
    /// Verifies a seekable encoding remains sequentially compatible and exposes exact frame mappings.
    @Test
    @Timeout(10)
    public void writesCompatibleIndexedFrames() throws IOException {
        byte[] source = patternedBytes(3500);
        Path path = Files.createTempFile("arkivo-zstd-seekable-", ".zst");
        try {
            ZstdCodec codec = ZstdCodec.DEFAULT.withFrameChecksum(true);
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = codec.newSeekableWritableByteChannel(
                    target,
                    new SeekableEncodingOptions(source.length, 1024),
                    ResourceOwnership.BORROWED
            )) {
                assertInstanceOf(InterruptibleChannel.class, encoder);
                encoder.encode(ByteBuffer.wrap(source));
            }

            byte[] encoded = Files.readAllBytes(path);
            assertArrayEquals(
                    source,
                    codec.withMaximumOutputSize(source.length).decompress(ByteBuffer.wrap(encoded)).array()
            );

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel encodedSource = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = codec.readIndex(encodedSource);
                assertNotNull(index);
                assertEquals(0L, encodedSource.position());
            }
            assertEquals(encoded.length, index.compressedSize());
            assertEquals(source.length, index.uncompressedSize());
            assertEquals(4, index.frameCount());
            assertEquals(1024L, index.frameUncompressedSize(0));
            assertEquals(1024L, index.frameUncompressedOffset(1));
            assertEquals(428L, index.frameUncompressedSize(3));
            assertEquals(index.frameCompressedOffset(3) + index.frameCompressedSize(3), encoded.length - 65L);

            try (SeekableByteChannel encodedSource = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel decoded = index.newReadableByteChannel(
                         encodedSource,
                         ResourceOwnership.BORROWED
                 )) {
                assertInstanceOf(InterruptibleChannel.class, decoded);
                decoded.position(937L);
                ByteBuffer range = ByteBuffer.allocate(1800);
                assertEquals(1800, decoded.read(range));
                assertArrayEquals(
                        java.util.Arrays.copyOfRange(source, 937, 2737),
                        range.array()
                );
                decoded.position(source.length - 20L);
                ByteBuffer tail = ByteBuffer.allocate(64);
                assertEquals(20, decoded.read(tail));
                assertEquals(-1, decoded.read(tail));
                assertArrayEquals(
                        java.util.Arrays.copyOfRange(source, source.length - 20, source.length),
                        java.util.Arrays.copyOf(tail.array(), 20)
                );
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies flush preserves the active frame while explicit frame completion records an early boundary.
    @Test
    public void flushesAndFinishesFramesExplicitly() throws IOException {
        byte[] source = patternedBytes(600);
        Path path = Files.createTempFile("arkivo-zstd-seekable-explicit-frame-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                    target,
                    new SeekableEncodingOptions(source.length, 1024),
                    ResourceOwnership.BORROWED
            )) {
                encoder.encode(ByteBuffer.wrap(source, 0, 250));
                encoder.flush();
                encoder.finishFrame();
                encoder.encode(ByteBuffer.wrap(source, 250, source.length - 250));
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel encoded = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(encoded);
                assertNotNull(index);
            }
            assertEquals(2, index.frameCount());
            assertEquals(250L, index.frameUncompressedSize(0));
            assertEquals(350L, index.frameUncompressedSize(1));
            assertArrayEquals(
                    source,
                    ZstdCodec.DEFAULT.withMaximumOutputSize(source.length)
                            .decompress(ByteBuffer.wrap(Files.readAllBytes(path)))
                            .array()
            );
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies empty logical input is represented by an indexed empty frame and an EOF logical channel.
    @Test
    public void writesEmptyIndex() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-empty-", ".zst");
        try {
            ZstdCodec codec = ZstdCodec.DEFAULT;
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = codec.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.allocate(0));
            }
            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = codec.readIndex(source);
                assertNotNull(index);
            }
            assertEquals(1, index.frameCount());
            assertEquals(0L, index.frameUncompressedSize(0));
            assertEquals(0L, index.uncompressedSize());
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel decoded = index.newReadableByteChannel(source)) {
                assertEquals(-1, decoded.read(ByteBuffer.allocate(1)));
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies an ordinary Zstandard frame is distinguishable from a seekable encoding.
    @Test
    public void ordinaryFrameHasNoSeekableIndex() throws IOException {
        byte[] encoded = ZstdCodec.DEFAULT.compress(ByteBuffer.wrap(patternedBytes(128))).array();
        Path path = Files.createTempFile("arkivo-zstd-frame-", ".zst");
        try {
            Files.write(path, encoded);
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertNull(ZstdCodec.DEFAULT.readIndex(source));
                assertEquals(0L, source.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies index parsing and logical channels honor a nonzero encoded-source origin.
    @Test
    public void supportsEmbeddedEncodingOrigin() throws IOException {
        byte[] prefix = patternedBytes(13);
        byte[] decoded = patternedBytes(4096);
        Path path = Files.createTempFile("arkivo-zstd-seekable-origin-", ".bin");
        try {
            Files.write(path, prefix, StandardOpenOption.TRUNCATE_EXISTING);
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            )) {
                target.position(prefix.length);
                try (var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                    encoder.encode(ByteBuffer.wrap(decoded));
                }
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                source.position(prefix.length);
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
                assertEquals(prefix.length, source.position());
                assertEquals(Files.size(path) - prefix.length, index.compressedSize());
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                source.position(prefix.length);
                try (SeekableByteChannel logical = index.newReadableByteChannel(source)) {
                    ByteBuffer actual = ByteBuffer.allocate(decoded.length);
                    assertEquals(decoded.length, logical.read(actual));
                    assertArrayEquals(decoded, actual.array());
                }
                assertTrue(source.isOpen());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies magicless codec configurations advertise and reject unavailable seekable operations.
    @Test
    public void rejectsSeekableOperationsForMagiclessFrames() throws IOException {
        ZstdCodec codec = ZstdCodec.DEFAULT.withFrameFormat(ZstdFrameFormat.MAGICLESS);
        assertFalse(codec.supportsSeekableEncoding());
        Path path = Files.createTempFile("arkivo-zstd-seekable-magicless-", ".zst");
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            )) {
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> codec.newSeekableWritableByteChannel(channel)
                );
                assertThrows(UnsupportedOperationException.class, () -> codec.readIndex(channel));
                assertTrue(channel.isOpen());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies indexed logical size is enforced before a random-access channel is created.
    @Test
    public void indexEnforcesOutputLimit() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-limit-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(1024)));
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertInstanceOf(
                        DecompressionOutputLimitException.class,
                        assertThrows(
                                IOException.class,
                                () -> ZstdCodec.DEFAULT.withMaximumOutputSize(1023).readIndex(source)
                        )
                );
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies seek-table allocation is charged to the operation-scoped decoder memory limit.
    @Test
    public void indexEnforcesMemoryLimit() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-memory-limit-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(1024)));
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertInstanceOf(
                        DecompressionMemoryLimitException.class,
                        assertThrows(
                                IOException.class,
                                () -> ZstdCodec.DEFAULT.withMaximumMemorySize(1L).readIndex(source)
                        )
                );
                assertEquals(0L, source.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies logical reads reserve memory for both the retained index and the decoded frame cache.
    @Test
    public void logicalChannelEnforcesFrameCacheMemoryLimit() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-frame-memory-limit-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(80_000)));
            }
            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.withMaximumMemorySize(65_568L).readIndex(source);
                assertNotNull(index);
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel logical = index.newReadableByteChannel(source)) {
                assertInstanceOf(
                        DecompressionMemoryLimitException.class,
                        assertThrows(IOException.class, () -> logical.read(ByteBuffer.allocate(1)))
                );
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies nonzero reserved descriptor bits make a recognized seek table malformed.
    @Test
    public void rejectsReservedDescriptorBits() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-descriptor-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
            }
            byte[] encoded = Files.readAllBytes(path);
            encoded[encoded.length - 5] = 0x40;
            Files.write(path, encoded, StandardOpenOption.TRUNCATE_EXISTING);

            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source));
                assertEquals(0L, source.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies indexed compressed sizes must end exactly where the terminal seek table begins.
    @Test
    public void rejectsMismatchedFrameExtent() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-frame-extent-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
            }
            byte[] encoded = Files.readAllBytes(path);
            int entryOffset = encoded.length - 17;
            ByteBuffer table = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            table.putInt(entryOffset, table.getInt(entryOffset) + 1);
            Files.write(path, encoded, StandardOpenOption.TRUNCATE_EXISTING);

            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source));
                assertEquals(0L, source.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies seek-table entries cannot declare frames larger than the interoperable implementation bound.
    @Test
    public void rejectsOversizedFrameDeclaration() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-frame-size-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
            }
            byte[] encoded = Files.readAllBytes(path);
            int entryOffset = encoded.length - 17;
            ByteBuffer.wrap(encoded)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(entryOffset + Integer.BYTES, 0x4000_0001);
            Files.write(path, encoded, StandardOpenOption.TRUNCATE_EXISTING);

            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source));
                assertEquals(0L, source.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies exact logical source pledges and endpoint ownership are enforced independently.
    @Test
    public void enforcesSourcePledgeAndOwnership() throws IOException {
        Path path = Files.createTempFile("arkivo-zstd-seekable-ownership-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                        target,
                        new SeekableEncodingOptions(5L, 64),
                        ResourceOwnership.BORROWED
                );
                encoder.encode(ByteBuffer.wrap(patternedBytes(4)));
                assertThrows(IOException.class, encoder::finish);
                assertTrue(target.isOpen());
            }

            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
            }
            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                SeekableByteChannel decoded = index.newReadableByteChannel(source, ResourceOwnership.OWNED);
                decoded.close();
                assertFalse(source.isOpen());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies optional seek-table checksums are validated independently from frame trailers.
    @Test
    public void verifiesSeekTableChecksum() throws IOException {
        byte[] decoded = patternedBytes(4096);
        Path path = Files.createTempFile("arkivo-zstd-seekable-checksum-", ".zst");
        try {
            ZstdCodec codec = ZstdCodec.DEFAULT.withFrameChecksum(true);
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = codec.newSeekableWritableByteChannel(target)) {
                encoder.encode(ByteBuffer.wrap(decoded));
            }

            byte[] encoded = Files.readAllBytes(path);
            int tableSize = 8 + 12 + 9;
            int checksumOffset = encoded.length - tableSize + 8 + 8;
            encoded[checksumOffset] ^= 0x40;
            Files.write(path, encoded, StandardOpenOption.TRUNCATE_EXISTING);

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = codec.readIndex(source);
                assertNotNull(index);
            }
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel logical = index.newReadableByteChannel(source)) {
                assertThrows(IOException.class, () -> logical.read(ByteBuffer.allocate(decoded.length)));
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Creates deterministic bytes crossing literals, matches, and frame boundaries.
    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31 + index / 17);
        }
        return bytes;
    }
}
