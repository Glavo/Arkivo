// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable BZip2 configuration and its documented decoder-limit scope.
@NotNullByDefault
final class BZip2CodecConfigurationContractTest {
    /// Verifies compression level and three decoding limits compose without mutating the default codec.
    @Test
    void composesIndependentConfigurationValues() {
        BZip2Codec defaults = BZip2Codec.DEFAULT;
        BZip2Codec configured = defaults
                .withCompressionLevel(3L)
                .withMaximumOutputSize(101L)
                .withMaximumWindowSize(202L)
                .withMaximumMemorySize(303L);

        assertEquals(9L, defaults.compressionLevel());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumOutputSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumMemorySize());
        assertEquals(3L, configured.compressionLevel());
        assertEquals(101L, configured.maximumOutputSize());
        assertEquals(202L, configured.maximumWindowSize());
        assertEquals(303L, configured.maximumMemorySize());
        assertSame(configured, configured.withCompressionLevel(3L));
        assertSame(configured, configured.withMaximumOutputSize(101L));
        assertSame(configured, configured.withMaximumWindowSize(202L));
        assertSame(configured, configured.withMaximumMemorySize(303L));

        assertThrows(
                IllegalArgumentException.class,
                () -> defaults.withMaximumOutputSize(CompressionCodec.UNLIMITED_SIZE - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> defaults.withMaximumWindowSize(CompressionCodec.UNLIMITED_SIZE - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> defaults.withMaximumMemorySize(CompressionCodec.UNLIMITED_SIZE - 1L)
        );
    }

    /// Verifies BZip2 retains but does not interpret window and memory limits when constructing a decoder.
    @Test
    void treatsWindowAndMemoryLimitsAsUnenforcedMetadata() throws IOException {
        byte[] content = {1, 2, 3, 4, 5};
        ByteBuffer encoded = BZip2Codec.DEFAULT.compress(ByteBuffer.wrap(content));
        BZip2Codec restricted = BZip2Codec.DEFAULT
                .withMaximumOutputSize(content.length)
                .withMaximumWindowSize(0L)
                .withMaximumMemorySize(0L);
        ByteBuffer decoded = ByteBuffer.allocate(content.length);

        restricted.decompress(encoded, decoded);

        assertArrayEquals(content, decoded.array());
    }
}
