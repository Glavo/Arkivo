// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the default ByteBuffer compression codec adapters.
@NotNullByDefault
final class CompressionCodecBufferTest {
    /// Verifies heap-buffer copying, bounds, and position updates.
    @Test
    void adaptsChannelOperationsToHeapBuffers() throws IOException {
        CompressionCodec codec = new IdentityCodec();
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        source.position(1);
        source.limit(4);
        ByteBuffer compressed = ByteBuffer.allocate(7);
        compressed.position(2);
        compressed.limit(5);

        assertTrue(codec.canCompressBuffers());
        assertTrue(codec.canDecompressBuffers());
        codec.compress(source, compressed);

        assertEquals(4, source.position());
        assertEquals(5, compressed.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(compressed, 2, 5));

        ByteBuffer encoded = compressed.duplicate();
        encoded.position(2);
        encoded.limit(5);
        ByteBuffer decoded = ByteBuffer.allocate(6);
        decoded.position(1);
        decoded.limit(4);
        codec.decompress(encoded, decoded);

        assertEquals(5, encoded.position());
        assertEquals(4, decoded.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(decoded, 1, 4));
    }

    /// Verifies allocating one-shot operations, dynamic growth, and bounded decompression.
    @Test
    void allocatesOneShotBuffersWithStrictOutputLimits() throws IOException {
        CompressionCodec codec = new IdentityCodec();
        byte[] content = new byte[(1 << 20) + 12_345];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31 + index / 257);
        }

        ByteBuffer source = ByteBuffer.allocateDirect(content.length + 4);
        source.position(2);
        source.put(content);
        source.flip();
        source.position(2);
        ByteBuffer compressed = codec.compress(source);
        assertEquals(source.limit(), source.position());
        assertEquals(0, compressed.position());
        assertEquals(content.length, compressed.remaining());
        assertTrue(compressed.hasArray());
        assertArrayEquals(content, bufferBytes(compressed, 0, compressed.limit()));

        ByteBuffer decoded = codec.decompress(compressed, content.length);
        assertEquals(compressed.limit(), compressed.position());
        assertEquals(0, decoded.position());
        assertEquals(content.length, decoded.limit());
        assertArrayEquals(content, bufferBytes(decoded, 0, decoded.limit()));

        ByteBuffer exact = codec.decompress(ByteBuffer.wrap(new byte[]{1, 2, 3}), 3L);
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(exact, 0, exact.limit()));

        ByteBuffer exceededSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> codec.decompress(exceededSource, 3L)
        );
        assertEquals(3L, exception.maximumOutputSize());
        assertEquals(4, exceededSource.position());

        assertEquals(0, codec.decompress(ByteBuffer.allocate(0), 0L).remaining());
        assertThrows(
                DecompressionLimitException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1}), 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decompress(ByteBuffer.allocate(0), -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decompress(ByteBuffer.allocate(0), (long) Integer.MAX_VALUE + 1L)
        );
    }
    /// Verifies one-shot adapters restore read-ahead and failed decoder construction.
    @Test
    void restoresReadAheadSourcePositions() throws IOException {
        CompressionCodec codec = new ReadAheadCodec(3, false);

        ByteBuffer fixedSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 9});
        ByteBuffer fixedTarget = ByteBuffer.allocate(3);
        codec.decompress(fixedSource, fixedTarget);
        assertEquals(3, fixedSource.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(fixedTarget, 0, fixedTarget.position()));

        ByteBuffer allocatingSource = ByteBuffer.wrap(new byte[]{4, 5, 6, 9});
        ByteBuffer allocated = codec.decompress(allocatingSource, 3L);
        assertEquals(3, allocatingSource.position());
        assertArrayEquals(new byte[]{4, 5, 6}, bufferBytes(allocated, 0, allocated.limit()));

        CompressionCodec failing = new ReadAheadCodec(0, true);
        ByteBuffer failingSource = ByteBuffer.wrap(new byte[]{7, 8, 9});
        assertThrows(
                IOException.class,
                () -> failing.decompress(failingSource, ByteBuffer.allocate(1))
        );
        assertEquals(0, failingSource.position());
    }

    /// Verifies deterministic failures for invalid targets and insufficient capacity.
    @Test
    void reportsInvalidOrInsufficientTargets() {
        CompressionCodec codec = new IdentityCodec();

        ByteBuffer same = ByteBuffer.allocate(4);
        assertThrows(IllegalArgumentException.class, () -> codec.compress(same, same));
        assertThrows(ReadOnlyBufferException.class,
                () -> codec.compress(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.allocate(1).asReadOnlyBuffer()));

        ByteBuffer compressionSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer compressionTarget = ByteBuffer.allocate(2);
        assertThrows(BufferOverflowException.class, () -> codec.compress(compressionSource, compressionTarget));
        assertEquals(0, compressionSource.position());
        assertEquals(2, compressionTarget.position());

        ByteBuffer decompressionSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer decompressionTarget = ByteBuffer.allocate(2);
        assertThrows(BufferOverflowException.class, () -> codec.decompress(decompressionSource, decompressionTarget));
        assertEquals(3, decompressionSource.position());
        assertEquals(2, decompressionTarget.position());
    }

    /// Returns bytes from the given absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Implements a decoder that prefetches its complete source but exposes only one logical prefix.
    @NotNullByDefault
    private static final class ReadAheadDecoder implements CompressionDecoder {
        /// The prefetched source bytes.
        private final ByteBuffer content;

        /// The logical decoded prefix length.
        private final int decodedSize;

        /// The number of decoded bytes returned.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Prefetches the source into a fixed test buffer.
        private ReadAheadDecoder(ReadableByteChannel source, int decodedSize) throws IOException {
            ByteBuffer prefetched = ByteBuffer.allocate(32);
            while (true) {
                int read = source.read(prefetched);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Read-ahead test source made no progress");
                }
            }
            prefetched.flip();
            if (decodedSize < 0 || decodedSize > prefetched.remaining()) {
                throw new IllegalArgumentException("decodedSize is out of range");
            }
            content = prefetched;
            this.decodedSize = decodedSize;
        }

        /// Returns bytes from the logical decoded prefix.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (content.position() >= decodedSize) {
                return -1;
            }
            int count = Math.min(target.remaining(), decodedSize - content.position());
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            outputBytes += count;
            return count;
        }

        /// Returns the logical compressed prefix consumed.
        @Override
        public long inputBytes() {
            return content.position();
        }

        /// Returns the complete prefetched source size.
        @Override
        public long sourceBytes() {
            return content.limit();
        }

        /// Returns a read-only view of the prefetched suffix.
        @Override
        public @UnmodifiableView ByteBuffer unconsumedInput() {
            return content.asReadOnlyBuffer();
        }

        /// Returns decoded bytes delivered to callers.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether this decoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this decoder.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides identity encoding and configurable read-ahead decoding.
    @NotNullByDefault
    private static final class ReadAheadCodec implements CompressionCodec {
        /// The supported test operations.
        private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
                CompressionFeature.COMPRESSION,
                CompressionFeature.DECOMPRESSION,
                CompressionFeature.ONE_SHOT_COMPRESSION,
                CompressionFeature.ONE_SHOT_DECOMPRESSION
        ));

        /// The logical decoded prefix length.
        private final int decodedSize;

        /// Whether decoder construction consumes input and fails.
        private final boolean failOpen;

        /// Creates a configurable read-ahead codec.
        private ReadAheadCodec(int decodedSize, boolean failOpen) {
            this.decodedSize = decodedSize;
            this.failOpen = failOpen;
        }

        /// Returns the test codec name.
        @Override
        public String name() {
            return "read-ahead";
        }

        /// Returns the test codec capabilities.
        @Override
        public CompressionCapabilities capabilities() {
            return CAPABILITIES;
        }

        /// Opens an identity encoder.
        @Override
        public CompressionEncoder openEncoder(
                WritableByteChannel target,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.compressionOptions(), "read-ahead compression");
            return StreamCodecAdapters.openEncoder(target, ownership, output -> output);
        }

        /// Opens a read-ahead decoder or fails after consuming one source byte.
        @Override
        public CompressionDecoder openDecoder(
                ReadableByteChannel source,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.decompressionOptions(), "read-ahead decompression");
            if (failOpen) {
                source.read(ByteBuffer.allocate(1));
                throw new IOException("Read-ahead decoder construction failed");
            }
            return new ReadAheadDecoder(source, decodedSize);
        }
    }

    /// Implements an identity transformation through the channel API.
    @NotNullByDefault
    private static final class IdentityCodec implements CompressionCodec {
        /// The supported identity operations.
        private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
                CompressionFeature.COMPRESSION,
                CompressionFeature.DECOMPRESSION,
                CompressionFeature.ONE_SHOT_COMPRESSION,
                CompressionFeature.ONE_SHOT_DECOMPRESSION
        ));

        /// Creates an identity codec.
        private IdentityCodec() {
        }

        /// Returns the identity codec name.
        @Override
        public String name() {
            return "identity";
        }

        /// Returns the supported identity operations.
        @Override
        public CompressionCapabilities capabilities() {
            return CAPABILITIES;
        }

        /// Opens an identity encoder.
        @Override
        public CompressionEncoder openEncoder(
                WritableByteChannel target,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.compressionOptions(), "identity compression");
            return StreamCodecAdapters.openEncoder(target, ownership, output -> output);
        }

        /// Opens an identity decoder.
        @Override
        public CompressionDecoder openDecoder(
                ReadableByteChannel source,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.decompressionOptions(), "identity decompression");
            return StreamCodecAdapters.openDecoder(source, ownership, input -> input);
        }
    }
}
