// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable option composition across the Deflate codec family.
@NotNullByDefault
final class DeflateCodecConfigurationContractTest {
    /// Verifies raw Deflate preserves every independent option while changing or removing its dictionary.
    @Test
    void composesRawDeflateConfiguration() {
        RawCompressionDictionary dictionary = RawCompressionDictionary.of(new byte[]{1, 2, 3});
        DeflateCodec defaults = DeflateCodec.DEFAULT;
        DeflateCodec configured = defaults
                .withCompressionLevel(9L)
                .withStrategy(DeflateStrategy.FILTERED)
                .withDictionary(dictionary)
                .withMaximumOutputSize(101L)
                .withMaximumWindowSize(32_768L)
                .withMaximumMemorySize(65_536L);

        assertDefaultLimits(defaults);
        assertEquals(6L, defaults.compressionLevel());
        assertEquals(9L, configured.compressionLevel());
        assertSame(DeflateStrategy.FILTERED, configured.strategy());
        assertSame(dictionary, configured.dictionary());
        assertEquals(101L, configured.maximumOutputSize());
        assertEquals(32_768L, configured.maximumWindowSize());
        assertEquals(65_536L, configured.maximumMemorySize());
        assertSame(configured, configured.withCompressionLevel(9L));
        assertSame(configured, configured.withStrategy(DeflateStrategy.FILTERED));
        assertSame(configured, configured.withDictionary(dictionary));
        assertSame(configured, configured.withMaximumOutputSize(101L));
        assertSame(configured, configured.withMaximumWindowSize(32_768L));
        assertSame(configured, configured.withMaximumMemorySize(65_536L));

        DeflateCodec withoutDictionary = configured.withoutDictionary();
        assertNotSame(configured, withoutDictionary);
        assertNull(withoutDictionary.dictionary());
        assertEquals(configured.compressionLevel(), withoutDictionary.compressionLevel());
        assertSame(configured.strategy(), withoutDictionary.strategy());
        assertEquals(configured.maximumOutputSize(), withoutDictionary.maximumOutputSize());
        assertEquals(configured.maximumWindowSize(), withoutDictionary.maximumWindowSize());
        assertEquals(configured.maximumMemorySize(), withoutDictionary.maximumMemorySize());
        assertSame(withoutDictionary, withoutDictionary.withoutDictionary());
    }

    /// Verifies zlib preserves every independent option while changing or removing its identified dictionary.
    @Test
    void composesZlibConfiguration() {
        ZlibDictionary dictionary = ZlibDictionary.of(new byte[]{4, 5, 6});
        ZlibCodec defaults = ZlibCodec.DEFAULT;
        ZlibCodec configured = defaults
                .withCompressionLevel(8L)
                .withStrategy(DeflateStrategy.HUFFMAN_ONLY)
                .withDictionary(dictionary)
                .withMaximumOutputSize(202L)
                .withMaximumWindowSize(16_384L)
                .withMaximumMemorySize(32_768L);

        assertDefaultLimits(defaults);
        assertEquals(6L, defaults.compressionLevel());
        assertEquals(8L, configured.compressionLevel());
        assertSame(DeflateStrategy.HUFFMAN_ONLY, configured.strategy());
        assertSame(dictionary, configured.dictionary());
        assertEquals(202L, configured.maximumOutputSize());
        assertEquals(16_384L, configured.maximumWindowSize());
        assertEquals(32_768L, configured.maximumMemorySize());
        assertSame(configured, configured.withCompressionLevel(8L));
        assertSame(configured, configured.withStrategy(DeflateStrategy.HUFFMAN_ONLY));
        assertSame(configured, configured.withDictionary(dictionary));
        assertSame(configured, configured.withMaximumOutputSize(202L));
        assertSame(configured, configured.withMaximumWindowSize(16_384L));
        assertSame(configured, configured.withMaximumMemorySize(32_768L));

        ZlibCodec withoutDictionary = configured.withoutDictionary();
        assertNotSame(configured, withoutDictionary);
        assertNull(withoutDictionary.dictionary());
        assertEquals(configured.compressionLevel(), withoutDictionary.compressionLevel());
        assertSame(configured.strategy(), withoutDictionary.strategy());
        assertEquals(configured.maximumOutputSize(), withoutDictionary.maximumOutputSize());
        assertEquals(configured.maximumWindowSize(), withoutDictionary.maximumWindowSize());
        assertEquals(configured.maximumMemorySize(), withoutDictionary.maximumMemorySize());
        assertSame(withoutDictionary, withoutDictionary.withoutDictionary());
    }

    /// Verifies gzip preserves its compression policy while each decoding limit changes independently.
    @Test
    void composesGzipConfiguration() {
        GzipCodec defaults = GzipCodec.DEFAULT;
        GzipCodec configured = defaults
                .withCompressionLevel(7L)
                .withStrategy(DeflateStrategy.FILTERED)
                .withMaximumOutputSize(303L)
                .withMaximumWindowSize(32_768L)
                .withMaximumMemorySize(65_536L);

        assertDefaultLimits(defaults);
        assertEquals(6L, defaults.compressionLevel());
        assertEquals(7L, configured.compressionLevel());
        assertSame(DeflateStrategy.FILTERED, configured.strategy());
        assertEquals(303L, configured.maximumOutputSize());
        assertEquals(32_768L, configured.maximumWindowSize());
        assertEquals(65_536L, configured.maximumMemorySize());
        assertSame(configured, configured.withCompressionLevel(7L));
        assertSame(configured, configured.withStrategy(DeflateStrategy.FILTERED));
        assertSame(configured, configured.withMaximumOutputSize(303L));
        assertSame(configured, configured.withMaximumWindowSize(32_768L));
        assertSame(configured, configured.withMaximumMemorySize(65_536L));
    }

    /// Verifies Deflate64 preserves its compression level while each decoding limit changes independently.
    @Test
    void composesDeflate64Configuration() {
        Deflate64Codec defaults = Deflate64Codec.DEFAULT;
        Deflate64Codec configured = defaults
                .withCompressionLevel(5L)
                .withMaximumOutputSize(404L)
                .withMaximumWindowSize(65_536L)
                .withMaximumMemorySize(131_072L);

        assertDefaultLimits(defaults);
        assertEquals(6L, defaults.compressionLevel());
        assertEquals(5L, configured.compressionLevel());
        assertEquals(404L, configured.maximumOutputSize());
        assertEquals(65_536L, configured.maximumWindowSize());
        assertEquals(131_072L, configured.maximumMemorySize());
        assertSame(configured, configured.withCompressionLevel(5L));
        assertSame(configured, configured.withMaximumOutputSize(404L));
        assertSame(configured, configured.withMaximumWindowSize(65_536L));
        assertSame(configured, configured.withMaximumMemorySize(131_072L));
    }

    /// Verifies fixed-window decoders apply both the window limit and the effective memory limit.
    @Test
    void enforcesFixedDecoderWindowsAcrossLimitAxes() throws IOException {
        assertFixedWindow(DeflateCodec.DEFAULT, 32_768L);
        assertFixedWindow(GzipCodec.DEFAULT, 32L * 1024L);
        assertFixedWindow(Deflate64Codec.DEFAULT, 65_536L);
    }

    /// Verifies every Deflate family rejects compression levels immediately outside its documented range.
    @Test
    void rejectsOutOfRangeCompressionLevels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeflateCodec.DEFAULT.withCompressionLevel(DeflateCodec.MINIMUM_COMPRESSION_LEVEL - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DeflateCodec.DEFAULT.withCompressionLevel(DeflateCodec.MAXIMUM_COMPRESSION_LEVEL + 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZlibCodec.DEFAULT.withCompressionLevel(ZlibCodec.MINIMUM_COMPRESSION_LEVEL - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZlibCodec.DEFAULT.withCompressionLevel(ZlibCodec.MAXIMUM_COMPRESSION_LEVEL + 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GzipCodec.DEFAULT.withCompressionLevel(GzipCodec.MINIMUM_COMPRESSION_LEVEL - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GzipCodec.DEFAULT.withCompressionLevel(GzipCodec.MAXIMUM_COMPRESSION_LEVEL + 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Deflate64Codec.DEFAULT.withCompressionLevel(Deflate64Codec.MINIMUM_COMPRESSION_LEVEL - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Deflate64Codec.DEFAULT.withCompressionLevel(Deflate64Codec.MAXIMUM_COMPRESSION_LEVEL + 1L)
        );
    }

    /// Verifies one codec starts with unrestricted decoding limits.
    private static void assertDefaultLimits(CompressionCodec<?> codec) {
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumOutputSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumMemorySize());
    }

    /// Verifies one fixed-window codec at the exact limit and one byte below it.
    private static void assertFixedWindow(CompressionCodec<?> codec, long requiredWindowSize) throws IOException {
        DecompressionWindowLimitException windowFailure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.withMaximumWindowSize(requiredWindowSize - 1L).newDecoder()
        );
        assertEquals(requiredWindowSize - 1L, windowFailure.maximumWindowSize());
        assertEquals(requiredWindowSize, windowFailure.requiredWindowSize());

        DecompressionWindowLimitException memoryFailure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.withMaximumMemorySize(requiredWindowSize - 1L).newDecoder()
        );
        assertEquals(requiredWindowSize - 1L, memoryFailure.maximumWindowSize());
        assertEquals(requiredWindowSize, memoryFailure.requiredWindowSize());

        try (CompressionDecoder ignored = codec.withMaximumWindowSize(requiredWindowSize).newDecoder()) {
            assertEquals(requiredWindowSize, codec.withMaximumWindowSize(requiredWindowSize).maximumWindowSize());
        }
        try (CompressionDecoder ignored = codec.withMaximumMemorySize(requiredWindowSize).newDecoder()) {
            assertEquals(requiredWindowSize, codec.withMaximumMemorySize(requiredWindowSize).maximumMemorySize());
        }
    }
}
