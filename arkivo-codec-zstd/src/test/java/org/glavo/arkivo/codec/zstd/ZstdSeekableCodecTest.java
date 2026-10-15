// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
                encoder.startFrame(EncodingOptions.ofSourceSize(source.length - 250L));
                encoder.encode(ByteBuffer.wrap(source, 250, source.length - 250));
                encoder.finish();
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

    /// Verifies logical views implement the positioning, read-only, buffer-progress, and closure contracts of NIO.
    @ParameterizedTest
    @ValueSource(ints = {0, 2500})
    public void implementsReadOnlySeekableChannelContract(int size) throws IOException {
        byte[] decoded = patternedBytes(size);
        Path path = Files.createTempFile("arkivo-zstd-seekable-channel-contract-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                    target,
                    SeekableEncodingOptions.ofMaximumFrameSize(512),
                    ResourceOwnership.BORROWED
            )) {
                encoder.encode(ByteBuffer.wrap(decoded));
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }

            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.BORROWED);
                assertEquals(decoded.length, logical.size());
                assertEquals(0L, logical.position());

                long beyondEnd = decoded.length + 19L;
                assertSame(logical, logical.position(beyondEnd));
                assertEquals(0, logical.read(ByteBuffer.allocate(0)));
                assertEquals(-1, logical.read(ByteBuffer.allocate(1)));
                assertEquals(beyondEnd, logical.position());
                assertThrows(IllegalArgumentException.class, () -> logical.position(-1L));
                assertEquals(beyondEnd, logical.position());

                assertSame(logical, logical.position(17L));
                ByteBuffer writeSource = ByteBuffer.wrap(patternedBytes(8));
                assertThrows(NonWritableChannelException.class, () -> logical.write(writeSource));
                assertEquals(0, writeSource.position());
                assertEquals(17L, logical.position());
                assertThrows(IllegalArgumentException.class, () -> logical.truncate(-1L));
                assertThrows(NonWritableChannelException.class, () -> logical.truncate(0L));
                assertEquals(17L, logical.position());

                for (boolean cached : new boolean[]{false, true}) {
                    if (cached && decoded.length > 0) {
                        logical.position(0L);
                        assertEquals(1, logical.read(ByteBuffer.allocate(1)));
                    }
                    for (long position : new long[]{0L, 17L, 512L, decoded.length, beyondEnd}) {
                        logical.position(position);
                        long physicalPosition = source.position();
                        for (boolean direct : new boolean[]{false, true}) {
                            for (int remaining : new int[]{0, 16}) {
                                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(20) : ByteBuffer.allocate(20);
                                ByteBuffer readOnlyTarget = storage.asReadOnlyBuffer().position(2)
                                        .limit(2 + remaining).mark();
                                assertThrows(IllegalArgumentException.class, () -> logical.read(readOnlyTarget));
                                assertEquals(2, readOnlyTarget.position());
                                assertEquals(2 + remaining, readOnlyTarget.limit());
                                assertEquals(2, readOnlyTarget.reset().position());
                                assertEquals(position, logical.position());
                                assertEquals(physicalPosition, source.position());
                            }
                        }
                    }
                }

                logical.position(0L);
                ByteBuffer target = ByteBuffer.allocate(16);
                assertEquals(decoded.length == 0 ? -1 : 16, logical.read(target));
                assertArrayEquals(java.util.Arrays.copyOf(decoded, 16), target.array());

                logical.close();
                logical.close();
                assertFalse(logical.isOpen());
                assertTrue(source.isOpen());
                assertThrows(ClosedChannelException.class, logical::position);
                assertThrows(ClosedChannelException.class, logical::size);
                assertThrows(ClosedChannelException.class, () -> logical.position(0L));
                assertThrows(ClosedChannelException.class, () -> logical.read(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, () -> logical.read(ByteBuffer.allocate(0).asReadOnlyBuffer()));
                assertThrows(ClosedChannelException.class, () -> logical.write(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, () -> logical.truncate(0L));
            }

            SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
            SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.BORROWED);
            source.close();
            assertFalse(logical.isOpen());
            assertThrows(ClosedChannelException.class, () -> logical.read(ByteBuffer.allocate(1)));
            logical.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies a logical read preserves completed-frame progress when decoding a later frame fails.
    @Test
    public void preservesCompletedFramesBeforeLaterFrameFailure() throws IOException {
        byte[] decoded = patternedBytes(1300);
        int frameSize = 512;
        Path path = Files.createTempFile("arkivo-zstd-seekable-partial-failure-", ".zst");
        try {
            try (SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                    target,
                    SeekableEncodingOptions.ofMaximumFrameSize(frameSize),
                    ResourceOwnership.BORROWED
            )) {
                encoder.encode(ByteBuffer.wrap(decoded));
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }
            byte[] corrupted = Files.readAllBytes(path);
            corrupted[Math.toIntExact(index.frameCompressedOffset(1))] ^= 1;
            Files.write(path, corrupted, StandardOpenOption.TRUNCATE_EXISTING);

            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel logical = index.newReadableByteChannel(source)) {
                ByteBuffer target = ByteBuffer.allocate(decoded.length + 4);
                target.position(2);
                target.limit(2 + decoded.length);

                assertThrows(IOException.class, () -> logical.read(target));
                assertEquals(2 + frameSize, target.position());
                assertEquals(2 + decoded.length, target.limit());
                assertEquals(frameSize, logical.position());
                assertArrayEquals(
                        java.util.Arrays.copyOf(decoded, frameSize),
                        java.util.Arrays.copyOfRange(target.array(), 2, target.position())
                );
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies index parsing retains its primary failure when restoring the source position also fails.
    @Test
    public void preservesIndexFailureWhenPositionRestorationFails(@TempDir Path directory) throws IOException {
        Path path = Files.write(directory.resolve("index-failure.zst"), new byte[32]);
        // The nine-byte footer starts at offset 23; the caller's logical origin is offset 3.
        for (Throwable failure : new Throwable[]{
                new IOException("index read failed"),
                new IllegalStateException("index read failed"),
                new AssertionError("index read failed"),
                new ArithmeticException("index arithmetic failed")
        }) {
            IOException restorationFailure = new IOException("position restoration failed");
            try (FailingSeekableChannel source = new FailingSeekableChannel(
                    Files.newByteChannel(path), 23L, 1, failure
            )) {
                source.position(3L);
                source.positionFailureOffset = 3L;
                source.positionFailure = restorationFailure;

                Throwable caught = assertThrows(Throwable.class, () -> ZstdCodec.DEFAULT.readIndex(source));
                if (failure instanceof ArithmeticException) {
                    assertInstanceOf(IOException.class, caught);
                    assertSame(failure, caught.getCause());
                } else {
                    assertSame(failure, caught);
                }
                assertArrayEquals(new Throwable[]{restorationFailure}, caught.getSuppressed());
                assertTrue(source.isOpen());
                assertEquals(24L, source.position());
            }
        }
    }

    /// Verifies restoration failure is propagated even when no seek table is recognized.
    @Test
    public void reportsIndexPositionRestorationFailure(@TempDir Path directory) throws IOException {
        Path path = Files.write(directory.resolve("no-index.zst"), new byte[32]);
        IOException failure = new IOException("position restoration failed");
        try (FailingSeekableChannel source = new FailingSeekableChannel(
                Files.newByteChannel(path), -1L, 0, null
        )) {
            source.position(3L);
            source.positionFailureOffset = 3L;
            source.positionFailure = failure;

            assertSame(failure, assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source)));
            assertEquals(32L, source.position());
            assertTrue(source.isOpen());
        }
    }

    /// Verifies a shared read and restoration failure is not suppressed onto itself.
    @Test
    public void preservesSharedIndexFailure(@TempDir Path directory) throws IOException {
        Path path = Files.write(directory.resolve("shared-index-failure.zst"), new byte[32]);
        IOException failure = new IOException("shared index failure");
        try (FailingSeekableChannel source = new FailingSeekableChannel(
                Files.newByteChannel(path), 23L, 0, failure
        )) {
            source.position(3L);
            source.positionFailureOffset = 3L;
            source.positionFailure = failure;

            assertSame(failure, assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source)));
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    /// Verifies failed physical frame loads preserve prior output and retry without caching incomplete decoded data.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7})
    public void retriesFailedPhysicalFrameLoads(int partialSize, @TempDir Path directory) throws IOException {
        byte[] expected = patternedBytes(1300);
        int frameSize = 512;
        Path path = directory.resolve("retry.zst");
        ZstdCodec codec = ZstdCodec.DEFAULT.withFrameChecksum(true);
        try (SeekableByteChannel target = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var encoder = codec.newSeekableWritableByteChannel(target,
                     SeekableEncodingOptions.ofMaximumFrameSize(frameSize), ResourceOwnership.BORROWED)) {
            encoder.encode(ByteBuffer.wrap(expected));
        }
        CompressionCodec.Seekable.Index index;
        try (SeekableByteChannel source = Files.newByteChannel(path)) {
            index = codec.readIndex(source);
            assertNotNull(index);
        }
        for (Throwable failure : new Throwable[]{
                new IOException("encoded frame read failed"),
                new IllegalStateException("encoded frame read failed"),
                new AssertionError("encoded frame read failed")
        }) {
            for (boolean direct : new boolean[]{false, true}) {
                for (ResourceOwnership ownership : ResourceOwnership.values()) {
                    try (FailingSeekableChannel source = new FailingSeekableChannel(
                            Files.newByteChannel(path), index.frameCompressedOffset(1), partialSize, failure
                    )) {
                        try (SeekableByteChannel logical = index.newReadableByteChannel(source, ownership)) {
                            ByteBuffer target = direct
                                    ? ByteBuffer.allocateDirect(expected.length + 4)
                                    : ByteBuffer.allocate(expected.length + 4);
                            for (int offset = 0; offset < target.capacity(); offset++) {
                                target.put(offset, (byte) 0x5A);
                            }
                            target.position(2).limit(2 + expected.length).mark();
                            assertSame(failure, assertThrows(failure.getClass(), () -> logical.read(target)));
                            assertEquals(2 + frameSize, target.position());
                            assertEquals(2 + expected.length, target.limit());
                            assertEquals(frameSize, logical.position());
                            assertEquals(index.frameCompressedOffset(1) + partialSize, source.position());
                            ByteBuffer storage = target.duplicate().clear();
                            for (int offset = 0; offset < target.capacity(); offset++) {
                                byte value = offset >= 2 && offset < frameSize + 2 ? expected[offset - 2] : (byte) 0x5A;
                                assertEquals(value, storage.get(offset));
                            }

                            assertEquals(expected.length - frameSize, logical.read(target));
                            assertEquals(expected.length, logical.position());
                            assertEquals(0, logical.read(target));
                            assertEquals(-1, logical.read(ByteBuffer.allocate(1)));
                            assertEquals(2, target.reset().position());
                            byte[] actual = new byte[expected.length];
                            target.get(actual);
                            assertArrayEquals(expected, actual);
                            assertEquals((byte) 0x5A, storage.get(0));
                            assertEquals((byte) 0x5A, storage.get(1));
                            assertEquals((byte) 0x5A, storage.get(expected.length + 2));
                            assertEquals((byte) 0x5A, storage.get(expected.length + 3));

                            int reads = source.readCount;
                            logical.position(1100);
                            ByteBuffer cached = ByteBuffer.allocate(17);
                            assertEquals(17, logical.read(cached));
                            assertArrayEquals(java.util.Arrays.copyOfRange(expected, 1100, 1117), cached.array());
                            assertEquals(reads, source.readCount);
                        }
                        assertEquals(ownership == ResourceOwnership.BORROWED, source.isOpen());
                    }
                }
            }
        }
    }

    /// Verifies pre-existing interruption aborts seekable writers and logical readers together with their endpoints.
    @Test
    public void preservesInterruptibleLifecycle() throws IOException {
        byte[] decoded = patternedBytes(1024);
        Path path = Files.createTempFile("arkivo-zstd-seekable-interrupt-", ".zst");
        try {
            SeekableByteChannel target = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                    target,
                    SeekableEncodingOptions.DEFAULT,
                    ResourceOwnership.BORROWED
            );
            try {
                Thread.currentThread().interrupt();
                assertThrows(
                        ClosedByInterruptException.class,
                        () -> encoder.write(ByteBuffer.wrap(decoded))
                );
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(encoder.isOpen());
                assertFalse(target.isOpen());
            } finally {
                Thread.interrupted();
                encoder.close();
                target.close();
            }

            try (SeekableByteChannel encodedTarget = Files.newByteChannel(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); var completeEncoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(encodedTarget)) {
                completeEncoder.encode(ByteBuffer.wrap(decoded));
            }
            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }

            SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
            SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.BORROWED);
            try {
                Thread.currentThread().interrupt();
                assertThrows(
                        ClosedByInterruptException.class,
                        () -> logical.read(ByteBuffer.allocate(1))
                );
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(logical.isOpen());
                assertFalse(source.isOpen());
            } finally {
                Thread.interrupted();
                logical.close();
                source.close();
            }
        } finally {
            Thread.interrupted();
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

    /// Preserves a read failure when automatic source closure reports the same exception object.
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void preservesReadFailureDuringAutomaticClose(int failingFrame, @TempDir Path directory) throws IOException {
        Path path = directory.resolve("read-close.zst");
        try (SeekableByteChannel target = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target,
                     SeekableEncodingOptions.ofMaximumFrameSize(64), ResourceOwnership.BORROWED)) {
            encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
        }
        CompressionCodec.Seekable.Index index;
        try (SeekableByteChannel source = Files.newByteChannel(path)) {
            index = ZstdCodec.DEFAULT.readIndex(source);
            assertNotNull(index);
        }
        for (int type = 0; type < 3; type++) {
            for (boolean shared : new boolean[]{false, true}) {
                for (ResourceOwnership ownership : ResourceOwnership.values()) {
                    Throwable failure = switch (type) {
                        case 0 -> new IOException("frame read failed");
                        case 1 -> new IllegalStateException("frame read failed");
                        default -> new AssertionError("frame read failed");
                    };
                    Throwable cleanup = shared ? failure : new IOException("source close failed");
                    FailingSeekableChannel source = new FailingSeekableChannel(
                            Files.newByteChannel(path), index.frameCompressedOffset(failingFrame), 1, failure);
                    source.closeFailure = cleanup;
                    source.closeFailuresRemaining = 1;
                    try {
                        SeekableByteChannel logical = index.newReadableByteChannel(source, ownership);
                        ByteBuffer output = ByteBuffer.allocate(128);
                        Throwable caught = assertThrows(failure.getClass(), () -> {
                            try (logical) {
                                logical.read(output);
                            }
                        });
                        assertSame(failure, caught);
                        assertEquals(failingFrame * 64, output.position());
                        assertFalse(logical.isOpen());
                        assertTrue(source.isOpen());
                        if (ownership == ResourceOwnership.OWNED) {
                            assertEquals(1, caught.getSuppressed().length);
                            if (shared) {
                                assertInstanceOf(IOException.class, caught.getSuppressed()[0]);
                                assertSame(failure, caught.getSuppressed()[0].getCause());
                            } else {
                                assertSame(cleanup, caught.getSuppressed()[0]);
                            }
                        } else {
                            assertEquals(0, caught.getSuppressed().length);
                        }
                        logical.close();
                        logical.close();
                        assertEquals(ownership == ResourceOwnership.OWNED ? 2 : 0, source.closeCount);
                        assertEquals(ownership == ResourceOwnership.BORROWED, source.isOpen());
                    } finally {
                        source.closeFailuresRemaining = 0;
                        source.close();
                    }
                }
            }
        }
    }

    /// Wraps repeated close failures while leaving an unsuccessful owned close retryable.
    @Test
    public void preservesCloseFailureDuringAutomaticClose(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("close-close.zst");
        try (SeekableByteChannel target = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target,
                     SeekableEncodingOptions.ofMaximumFrameSize(64), ResourceOwnership.BORROWED)) {
            encoder.encode(ByteBuffer.wrap(patternedBytes(64)));
        }
        IOException failure = new IOException("source close failed twice");
        FailingSeekableChannel source = new FailingSeekableChannel(Files.newByteChannel(path), -1, 0, null);
        try {
            var index = ZstdCodec.DEFAULT.readIndex(source);
            assertNotNull(index);
            source.closeFailure = failure;
            source.closeFailuresRemaining = 2;
            SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.OWNED);
            IOException caught = assertThrows(IOException.class, () -> {
                try (logical) {
                    logical.close();
                }
            });
            assertSame(failure, caught);
            assertEquals(1, caught.getSuppressed().length);
            assertSame(failure, caught.getSuppressed()[0].getCause());
            assertFalse(logical.isOpen());
            assertTrue(source.isOpen());
            logical.close();
            logical.close();
            assertEquals(3, source.closeCount);
            assertFalse(source.isOpen());
        } finally {
            source.closeFailuresRemaining = 0;
            source.close();
        }
    }

    /// Preserves finalization failures across automatic close and retries only endpoint cleanup.
    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "2,false", "0,true", "1,true", "2,true"})
    public void preservesWriterCloseFailureDuringAutomaticClose(
            int type, boolean interruptible, @TempDir Path directory
    ) throws IOException {
        Path path = directory.resolve("writer-close-close.zst");
        Throwable failure = switch (type) {
            case 0 -> new IOException("target close failed twice");
            case 1 -> new IllegalStateException("target close failed twice");
            default -> new AssertionError("target close failed twice");
        };
        SeekableByteChannel file = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FailingSeekableChannel target = interruptible
                ? new InterruptibleFailingSeekableChannel(file)
                : new FailingSeekableChannel(file, -1, 0, null);
        try {
            target.closeFailure = failure;
            target.closeFailuresRemaining = 2;
            var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target,
                    SeekableEncodingOptions.ofMaximumFrameSize(64), ResourceOwnership.OWNED);
            byte[] content = patternedBytes(128);
            encoder.encode(ByteBuffer.wrap(content));
            Throwable caught = assertThrows(failure.getClass(), () -> {
                try (encoder) {
                    encoder.finish();
                }
            });
            assertSame(failure, caught);
            assertEquals(1, caught.getSuppressed().length);
            assertInstanceOf(IOException.class, caught.getSuppressed()[0]);
            Throwable cleanup = caught.getSuppressed()[0];
            assertSame(failure, interruptible ? cleanup.getCause().getCause() : cleanup.getCause());
            assertFalse(encoder.isOpen());
            assertTrue(target.isOpen());
            long encodedSize = target.size();
            encoder.close();
            encoder.close();
            assertEquals(3, target.closeCount);
            assertFalse(target.isOpen());
            assertEquals(encodedSize, Files.size(path));
            assertEquals(encodedSize, encoder.outputBytes());
            try (SeekableByteChannel source = Files.newByteChannel(path)) {
                var index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
                assertEquals(2, index.frameCount());
                try (SeekableByteChannel decoded = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                    ByteBuffer output = ByteBuffer.allocate(content.length);
                    while (output.hasRemaining()) {
                        assertTrue(decoded.read(output) > 0);
                    }
                    assertArrayEquals(content, output.array());
                }
            }
        } finally {
            target.closeFailuresRemaining = 0;
            target.close();
        }
    }

    /// Retains partial seek-table progress and never retries serialization after a terminal write failure.
    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "2,false", "0,true", "1,true", "2,true"})
    public void preservesPartialSeekTableFailure(
            int type, boolean interruptible, @TempDir Path directory
    ) throws IOException {
        Path path = directory.resolve("partial-table.zst");
        Throwable failure = switch (type) {
            case 0 -> new IOException("seek table write failed");
            case 1 -> new IllegalStateException("seek table write failed");
            default -> new AssertionError("seek table write failed");
        };
        SeekableByteChannel file = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FailingSeekableChannel target = interruptible
                ? new InterruptibleFailingSeekableChannel(file)
                : new FailingSeekableChannel(file, -1, 0, null);
        try {
            var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target,
                    SeekableEncodingOptions.ofMaximumFrameSize(64), ResourceOwnership.OWNED);
            encoder.encode(ByteBuffer.wrap(patternedBytes(128)));
            long framesSize = target.size();
            target.writeFailure = failure;
            target.partialWriteSize = 5;
            target.closeFailure = failure;
            target.closeFailuresRemaining = 2;
            Throwable caught = assertThrows(failure.getClass(), () -> {
                try (encoder) {
                    encoder.finish();
                }
            });
            assertSame(failure, caught);
            assertEquals(1, caught.getSuppressed().length);
            Throwable cleanup = caught.getSuppressed()[0];
            assertInstanceOf(IOException.class, cleanup);
            assertSame(failure, interruptible ? cleanup.getCause().getCause() : cleanup.getCause());
            assertEquals(framesSize + 5, target.size());
            assertEquals(framesSize + 5, encoder.outputBytes());
            assertEquals(128, encoder.inputBytes());
            assertFalse(encoder.isOpen());
            assertTrue(target.isOpen());
            encoder.close();
            encoder.close();
            assertEquals(3, target.closeCount);
            assertFalse(target.isOpen());
            assertEquals(framesSize + 5, Files.size(path));
            assertEquals(framesSize + 5, encoder.outputBytes());
        } finally {
            target.closeFailuresRemaining = 0;
            target.close();
        }
    }

    /// Exposes interruptible capability for idle finalization and close-retry tests.
    @NotNullByDefault
    private static final class InterruptibleFailingSeekableChannel
            extends FailingSeekableChannel implements InterruptibleChannel {
        /// Wraps a file with configurable failures without injecting asynchronous cancellation.
        private InterruptibleFailingSeekableChannel(SeekableByteChannel delegate) {
            super(delegate, -1, 0, null);
        }
    }

    /// Wraps an encoded file with configurable read, position-restoration, and close failures.
    @NotNullByDefault
    private static class FailingSeekableChannel implements SeekableByteChannel {
        /// The owned encoded file channel.
        private final SeekableByteChannel delegate;

        /// Physical position at which the failing read starts.
        private final long failurePosition;

        /// Bytes accepted by the failing read before it throws.
        private final int partialSize;

        /// The pending one-shot failure.
        private @Nullable Throwable failure;

        /// The pending one-shot write failure.
        private @Nullable Throwable writeFailure;

        /// Bytes emitted by the failing write before it throws.
        private int partialWriteSize;

        /// The position setter failure, or `null` for normal positioning.
        private @Nullable IOException positionFailure;

        /// Requested position at which the setter fails.
        private long positionFailureOffset;

        /// Number of physical read calls, including the failed call.
        private int readCount;

        /// The exception to deliver while close failures remain armed.
        private @Nullable Throwable closeFailure;

        /// The number of close attempts that must fail without closing the delegate.
        private int closeFailuresRemaining;

        /// Number of physical close calls, including failed calls.
        private int closeCount;

        /// Creates a failure-injecting view over the supplied encoded source.
        private FailingSeekableChannel(
                SeekableByteChannel delegate,
                long failurePosition,
                int partialSize,
                @Nullable Throwable failure
        ) {
            this.delegate = delegate;
            this.failurePosition = failurePosition;
            this.partialSize = partialSize;
            this.failure = failure;
        }

        /// Reads normally except for one partial read at the configured frame origin.
        @Override
        public int read(ByteBuffer target) throws IOException {
            readCount++;
            if (failure != null && delegate.position() == failurePosition) {
                Throwable pending = failure;
                failure = null;
                int limit = target.limit();
                target.limit(target.position() + Math.min(partialSize, target.remaining()));
                try {
                    delegate.read(target);
                } finally {
                    target.limit(limit);
                }
                if (pending instanceof IOException exception) {
                    throw exception;
                }
                if (pending instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) pending;
            }
            return delegate.read(target);
        }

        /// Delegates writes except for an armed partial write failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (writeFailure != null) {
                Throwable pending = writeFailure;
                writeFailure = null;
                int limit = source.limit();
                source.limit(source.position() + Math.min(partialWriteSize, source.remaining()));
                try {
                    delegate.write(source);
                } finally {
                    source.limit(limit);
                }
                if (pending instanceof IOException exception) {
                    throw exception;
                }
                if (pending instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) pending;
            }
            return delegate.write(source);
        }

        /// Returns the current encoded-file position.
        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        /// Repositions the encoded source unless the requested offset triggers an injected failure.
        @Override
        public SeekableByteChannel position(long position) throws IOException {
            if (positionFailure != null && position == positionFailureOffset) {
                throw positionFailure;
            }
            delegate.position(position);
            return this;
        }

        /// Returns the complete encoded size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Delegates truncation to the underlying channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        /// Returns whether the physical file remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Closes the encoded file after any configured close failures are exhausted.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                if (closeFailure instanceof IOException exception) {
                    throw exception;
                }
                if (closeFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) closeFailure;
            }
            delegate.close();
        }
    }
}
