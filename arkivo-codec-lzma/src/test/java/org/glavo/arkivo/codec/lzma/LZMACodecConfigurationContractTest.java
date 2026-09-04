// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable option composition and decoder limits across the LZMA codec family.
@NotNullByDefault
final class LZMACodecConfigurationContractTest {
    /// Verifies raw LZMA preserves model, termination, size, and limit settings independently.
    @Test
    void composesRawLzmaConfiguration() {
        LZMAProperties properties = new LZMAProperties(2, 1, 1, 65_536);
        RawLZMACodec defaults = RawLZMACodec.DEFAULT;
        RawLZMACodec configured = defaults
                .withProperties(properties)
                .withEndMarker(false)
                .withDecodedSize(123L)
                .withMaximumOutputSize(124L)
                .withMaximumWindowSize(65_536L)
                .withMaximumMemorySize(131_072L);

        assertDefaultLimits(defaults);
        assertEquals(LZMAProperties.defaults(RawLZMACodec.DEFAULT_DICTIONARY_SIZE), defaults.properties());
        assertTrue(defaults.emitsEndMarker());
        assertEquals(CompressionCodec.UNKNOWN_SIZE, defaults.decodedSize());
        assertSame(properties, configured.properties());
        assertFalse(configured.emitsEndMarker());
        assertEquals(123L, configured.decodedSize());
        assertEquals(124L, configured.maximumOutputSize());
        assertEquals(65_536L, configured.maximumWindowSize());
        assertEquals(131_072L, configured.maximumMemorySize());
        assertSame(configured, configured.withProperties(properties));
        assertSame(configured, configured.withDictionarySize(properties.dictionarySize()));
        assertSame(configured, configured.withEndMarker(false));
        assertSame(configured, configured.withDecodedSize(123L));
        assertSame(configured, configured.withMaximumOutputSize(124L));
        assertSame(configured, configured.withMaximumWindowSize(65_536L));
        assertSame(configured, configured.withMaximumMemorySize(131_072L));

        assertThrows(NullPointerException.class, () -> configured.withProperties(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> configured.withDecodedSize(CompressionCodec.UNKNOWN_SIZE - 1L)
        );
    }

    /// Verifies LZMA-alone preserves model properties while each decoder limit changes independently.
    @Test
    void composesLzmaAloneConfiguration() {
        LZMAProperties properties = new LZMAProperties(3, 0, 2, 131_072);
        LZMACodec defaults = LZMACodec.DEFAULT;
        LZMACodec configured = defaults
                .withProperties(properties)
                .withMaximumOutputSize(201L)
                .withMaximumWindowSize(131_072L)
                .withMaximumMemorySize(262_144L);

        assertDefaultLimits(defaults);
        assertSame(properties, configured.properties());
        assertEquals(201L, configured.maximumOutputSize());
        assertEquals(131_072L, configured.maximumWindowSize());
        assertEquals(262_144L, configured.maximumMemorySize());
        assertSame(configured, configured.withProperties(properties));
        assertSame(configured, configured.withDictionarySize(properties.dictionarySize()));
        assertSame(configured, configured.withMaximumOutputSize(201L));
        assertSame(configured, configured.withMaximumWindowSize(131_072L));
        assertSame(configured, configured.withMaximumMemorySize(262_144L));
        assertThrows(NullPointerException.class, () -> configured.withProperties(null));
    }

    /// Verifies LZMA2 preserves externally declared properties while each decoder limit changes independently.
    @Test
    void composesLzma2Configuration() {
        LZMAProperties properties = new LZMAProperties(4, 0, 0, 262_144);
        LZMA2Codec defaults = LZMA2Codec.DEFAULT;
        LZMA2Codec configured = defaults
                .withProperties(properties)
                .withMaximumOutputSize(301L)
                .withMaximumWindowSize(262_144L)
                .withMaximumMemorySize(524_288L);

        assertDefaultLimits(defaults);
        assertSame(properties, configured.properties());
        assertEquals(301L, configured.maximumOutputSize());
        assertEquals(262_144L, configured.maximumWindowSize());
        assertEquals(524_288L, configured.maximumMemorySize());
        assertSame(configured, configured.withProperties(properties));
        assertSame(configured, configured.withDictionarySize(properties.dictionarySize()));
        assertSame(configured, configured.withMaximumOutputSize(301L));
        assertSame(configured, configured.withMaximumWindowSize(262_144L));
        assertSame(configured, configured.withMaximumMemorySize(524_288L));
        assertThrows(NullPointerException.class, () -> configured.withProperties(null));
    }

    /// Verifies raw LZMA and LZMA2 apply their external dictionary size to both limit axes.
    @Test
    void enforcesExternalDictionaryAcrossLimitAxes() throws IOException {
        int dictionarySize = 65_536;
        assertEngineWindow(RawLZMACodec.DEFAULT.withDictionarySize(dictionarySize), dictionarySize);
        assertEngineWindow(LZMA2Codec.DEFAULT.withDictionarySize(dictionarySize), dictionarySize);
    }

    /// Verifies an LZMA-alone header dictionary is checked against the configured memory limit during decoding.
    @Test
    void enforcesLzmaAloneHeaderDictionaryAgainstMemoryLimit() throws IOException {
        int dictionarySize = 65_536;
        byte[] content = {1, 2, 3, 4, 5};
        LZMACodec codec = LZMACodec.DEFAULT.withDictionarySize(dictionarySize);
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(content));

        DecompressionWindowLimitException failure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.withMaximumMemorySize(dictionarySize - 1L)
                        .decompress(encoded.duplicate(), ByteBuffer.allocate(content.length))
        );
        assertEquals(dictionarySize - 1L, failure.maximumWindowSize());
        assertEquals(dictionarySize, failure.requiredWindowSize());

        ByteBuffer decoded = ByteBuffer.allocate(content.length);
        codec.withMaximumMemorySize(dictionarySize).decompress(encoded.duplicate(), decoded);
        assertArrayEquals(content, decoded.array());
    }

    /// Verifies one codec starts with unrestricted decoding limits.
    private static void assertDefaultLimits(CompressionCodec<?> codec) {
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumOutputSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumMemorySize());
    }

    /// Verifies an externally known dictionary at the exact limit and one byte below it.
    private static void assertEngineWindow(CompressionCodec<?> codec, long dictionarySize) throws IOException {
        DecompressionWindowLimitException windowFailure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.withMaximumWindowSize(dictionarySize - 1L).newDecoder()
        );
        assertEquals(dictionarySize - 1L, windowFailure.maximumWindowSize());
        assertEquals(dictionarySize, windowFailure.requiredWindowSize());

        DecompressionWindowLimitException memoryFailure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.withMaximumMemorySize(dictionarySize - 1L).newDecoder()
        );
        assertEquals(dictionarySize - 1L, memoryFailure.maximumWindowSize());
        assertEquals(dictionarySize, memoryFailure.requiredWindowSize());

        try (CompressionDecoder ignored = codec.withMaximumWindowSize(dictionarySize).newDecoder()) {
            assertEquals(dictionarySize, codec.withMaximumWindowSize(dictionarySize).maximumWindowSize());
        }
        try (CompressionDecoder ignored = codec.withMaximumMemorySize(dictionarySize).newDecoder()) {
            assertEquals(dictionarySize, codec.withMaximumMemorySize(dictionarySize).maximumMemorySize());
        }
    }
}
