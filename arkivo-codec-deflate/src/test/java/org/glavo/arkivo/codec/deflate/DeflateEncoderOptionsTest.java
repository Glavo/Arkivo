// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pure Java raw Deflate compression levels and strategies through the public codec options.
@NotNullByDefault
final class DeflateEncoderOptionsTest {
    /// Shared codec under test.
    private static final DeflateCodec CODEC = new DeflateCodec();

    /// Verifies the stable zero-through-nine compression-level model.
    @Test
    void exposesBoundedCompressionLevels() {
        assertEquals(0L, CODEC.minimumCompressionLevel());
        assertEquals(9L, CODEC.maximumCompressionLevel());
        assertEquals(6L, CODEC.defaultCompressionLevel());
        assertThrows(IllegalArgumentException.class, () -> encode(new byte[0], options(-1, CompressionStrategy.DEFAULT)));
        assertThrows(IllegalArgumentException.class, () -> encode(new byte[0], options(10, CompressionStrategy.DEFAULT)));
    }

    /// Verifies level zero emits stored blocks while higher levels use match search.
    @Test
    void levelZeroUsesStoredBlocks() throws IOException {
        byte[] source = "level-sensitive raw Deflate payload ".repeat(2_000).getBytes(StandardCharsets.UTF_8);

        byte[] stored = encode(source, options(0, CompressionStrategy.DEFAULT));
        byte[] compressed = encode(source, options(9, CompressionStrategy.DEFAULT));

        assertEquals(0, Byte.toUnsignedInt(stored[0]) >>> 1 & 3);
        assertTrue(compressed.length < stored.length / 8);
        assertArrayEquals(source, inflate(stored));
        assertArrayEquals(source, inflate(compressed));
    }

    /// Verifies all advertised strategy values reach the pure Java encoder and produce interoperable streams.
    @Test
    void supportsEveryCompressionStrategy() throws IOException {
        byte[] source = "strategy option interoperability ".repeat(1_500).getBytes(StandardCharsets.UTF_8);
        int defaultSize = 0;
        int huffmanOnlySize = 0;
        for (CompressionStrategy strategy : CompressionStrategy.values()) {
            byte[] encoded = encode(source, options(6, strategy));
            assertArrayEquals(source, inflate(encoded));
            if (strategy == CompressionStrategy.DEFAULT) {
                defaultSize = encoded.length;
            } else if (strategy == CompressionStrategy.HUFFMAN_ONLY) {
                huffmanOnlySize = encoded.length;
            }
        }
        assertTrue(defaultSize < huffmanOnlySize);
    }

    /// Creates a compression option set for one level and strategy.
    private static CodecOptions options(long level, CompressionStrategy strategy) {
        return CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, level)
                .set(StandardCodecOptions.COMPRESSION_STRATEGY, strategy)
                .build();
    }

    /// Encodes source bytes through the public channel adapter.
    private static byte @Unmodifiable [] encode(byte[] source, CodecOptions options) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CODEC.compress(
                Channels.newChannel(new ByteArrayInputStream(source)),
                Channels.newChannel(output),
                options
        );
        return output.toByteArray();
    }

    /// Decodes a raw stream with the independent JDK implementation.
    private static byte @Unmodifiable [] inflate(byte[] source) throws IOException {
        try (InputStream input = new InflaterInputStream(
                new ByteArrayInputStream(source),
                new Inflater(true)
        )) {
            return input.readAllBytes();
        }
    }
}
