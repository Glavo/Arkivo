// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;
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
