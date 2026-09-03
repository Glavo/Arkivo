// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the immutable configuration contracts of framed and raw-block LZ4 codecs.
@NotNullByDefault
public final class LZ4CodecConfigurationTest {
    /// Verifies that builds are immutable snapshots and `toBuilder` preserves every setting.
    @Test
    public void builderCreatesIndependentCompleteSnapshots() {
        LZ4Dictionary dictionary = LZ4Dictionary.identified(0xfedc_ba98L, new byte[]{1, 2, 3});
        LZ4Codec.Builder builder = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .independentBlocks(false)
                .blockChecksum(true)
                .contentChecksum(false)
                .verifyChecksums(false)
                .dictionary(dictionary)
                .maximumOutputSize(101L)
                .maximumWindowSize(102L)
                .maximumMemorySize(103L);

        LZ4Codec configured = builder.build();
        builder.blockSize(LZ4BlockSize.MIB_4)
                .independentBlocks(true)
                .blockChecksum(false)
                .contentChecksum(true)
                .verifyChecksums(true)
                .withoutDictionary()
                .maximumOutputSize(201L)
                .maximumWindowSize(202L)
                .maximumMemorySize(203L);

        assertConfigured(configured, dictionary);
        LZ4Codec copy = configured.toBuilder().build();
        assertNotSame(configured, copy);
        assertConfigured(copy, dictionary);

        LZ4Codec changed = builder.build();
        assertEquals(LZ4BlockSize.MIB_4, changed.blockSize());
        assertTrue(changed.usesIndependentBlocks());
        assertFalse(changed.emitsBlockChecksums());
        assertTrue(changed.emitsContentChecksum());
        assertTrue(changed.verifiesChecksums());
        assertNull(changed.dictionary());
        assertEquals(201L, changed.maximumOutputSize());
        assertEquals(202L, changed.maximumWindowSize());
        assertEquals(203L, changed.maximumMemorySize());
    }

    /// Verifies no-op withers preserve identity and changed withers preserve the original value.
    @Test
    public void framedWithersArePersistentAndCanonicalizeNoOps() {
        assertSame(LZ4Codec.DEFAULT, LZ4Codec.DEFAULT.withoutDictionary());
        LZ4Dictionary dictionary = LZ4Dictionary.rawContent(new byte[]{4, 5, 6});
        LZ4Codec codec = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .independentBlocks(false)
                .blockChecksum(true)
                .contentChecksum(false)
                .verifyChecksums(false)
                .dictionary(dictionary)
                .maximumOutputSize(101L)
                .maximumWindowSize(102L)
                .maximumMemorySize(103L)
                .build();

        assertSame(codec, codec.withBlockSize(LZ4BlockSize.KIB_64));
        assertSame(codec, codec.withIndependentBlocks(false));
        assertSame(codec, codec.withBlockChecksum(true));
        assertSame(codec, codec.withContentChecksum(false));
        assertSame(codec, codec.withVerifyChecksums(false));
        assertSame(codec, codec.withDictionary(dictionary));
        assertSame(codec, codec.withMaximumOutputSize(101L));
        assertSame(codec, codec.withMaximumWindowSize(102L));
        assertSame(codec, codec.withMaximumMemorySize(103L));

        assertNotSame(codec, codec.withBlockSize(LZ4BlockSize.MIB_1));
        assertNotSame(codec, codec.withIndependentBlocks(true));
        assertNotSame(codec, codec.withBlockChecksum(false));
        assertNotSame(codec, codec.withContentChecksum(true));
        assertNotSame(codec, codec.withVerifyChecksums(true));
        assertNotSame(codec, codec.withMaximumOutputSize(111L));
        assertNotSame(codec, codec.withMaximumWindowSize(112L));
        assertNotSame(codec, codec.withMaximumMemorySize(113L));
        assertNull(codec.withoutDictionary().dictionary());
        assertSame(dictionary, codec.withoutDictionary().withDictionary(dictionary).dictionary());

        assertConfigured(codec, dictionary);
    }

    /// Verifies framed-builder null and numeric preconditions.
    @Test
    public void framedBuilderRejectsInvalidSettings() {
        assertThrows(NullPointerException.class, () -> LZ4Codec.builder().blockSize(null));
        assertThrows(NullPointerException.class, () -> LZ4Codec.builder().dictionary(null));
        assertThrows(NullPointerException.class, () -> LZ4Codec.DEFAULT.withBlockSize(null));
        assertThrows(NullPointerException.class, () -> LZ4Codec.DEFAULT.withDictionary(null));
        assertThrows(NullPointerException.class, () -> LZ4Codec.DEFAULT.newEncoder(null));
        assertThrows(IllegalArgumentException.class, () -> LZ4Codec.builder().maximumOutputSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> LZ4Codec.builder().maximumWindowSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> LZ4Codec.builder().maximumMemorySize(-2L));
    }

    /// Verifies raw-block configuration bounds, no-op identity, and compressed-size limits.
    @Test
    public void rawBlockConfigurationHonorsAllBounds() {
        LZ4BlockCodec codec = LZ4BlockCodec.DEFAULT
                .withMaximumBlockSize(65_536L)
                .withMaximumOutputSize(71L)
                .withMaximumWindowSize(72L)
                .withMaximumMemorySize(73L);

        assertSame(LZ4BlockFormat.instance(), codec.format());
        assertEquals(65_536, codec.maximumBlockSize());
        assertEquals(71L, codec.maximumOutputSize());
        assertEquals(72L, codec.maximumWindowSize());
        assertEquals(73L, codec.maximumMemorySize());
        assertSame(codec, codec.withMaximumBlockSize(65_536L));
        assertSame(codec, codec.withMaximumOutputSize(71L));
        assertSame(codec, codec.withMaximumWindowSize(72L));
        assertSame(codec, codec.withMaximumMemorySize(73L));
        assertNotSame(codec, codec.withMaximumBlockSize(65_535L));
        assertNotSame(codec, codec.withMaximumOutputSize(81L));
        assertNotSame(codec, codec.withMaximumWindowSize(82L));
        assertNotSame(codec, codec.withMaximumMemorySize(83L));

        assertEquals(16L, codec.maxCompressedSize(0L));
        assertEquals(65_536L + 65_536L / 255L + 16L, codec.maxCompressedSize(65_536L));
        assertEquals(
                CompressionCodec.UNKNOWN_SIZE,
                codec.maxCompressedSize((long) LZ4BlockCodec.MAXIMUM_SUPPORTED_BLOCK_SIZE + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedSize(-1L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumBlockSize(0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.withMaximumBlockSize((long) LZ4BlockCodec.MAXIMUM_SUPPORTED_BLOCK_SIZE + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumOutputSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumWindowSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumMemorySize(-2L));
        assertThrows(
                NullPointerException.class,
                () -> codec.newEncoder((EncodingOptions) null)
        );

        assertEquals(1, LZ4BlockCodec.DEFAULT.withMaximumBlockSize(1L).maximumBlockSize());
        assertEquals(
                LZ4BlockCodec.MAXIMUM_SUPPORTED_BLOCK_SIZE,
                LZ4BlockCodec.DEFAULT
                        .withMaximumBlockSize(LZ4BlockCodec.MAXIMUM_SUPPORTED_BLOCK_SIZE)
                        .maximumBlockSize()
        );
    }

    /// Asserts every nondefault framed-codec setting used by snapshot tests.
    private static void assertConfigured(LZ4Codec codec, LZ4Dictionary dictionary) {
        assertSame(LZ4Format.instance(), codec.format());
        assertEquals(LZ4BlockSize.KIB_64, codec.blockSize());
        assertFalse(codec.usesIndependentBlocks());
        assertTrue(codec.emitsBlockChecksums());
        assertFalse(codec.emitsContentChecksum());
        assertFalse(codec.verifiesChecksums());
        assertSame(dictionary, codec.dictionary());
        assertEquals(101L, codec.maximumOutputSize());
        assertEquals(102L, codec.maximumWindowSize());
        assertEquals(103L, codec.maximumMemorySize());
    }
}
