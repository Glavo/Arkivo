// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests public 7z output compression configuration.
@NotNullByDefault
public final class SevenZipCompressionTest {
    /// Verifies stable default parameters for every supported method.
    @Test
    public void defaultConfigurations() {
        assertEquals(
                new SevenZipCompression(SevenZipCompressionMethod.COPY, 0),
                SevenZipCompression.copy()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.LZMA,
                        SevenZipCompression.DEFAULT_DICTIONARY_SIZE
                ),
                SevenZipCompression.lzma()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.LZMA2,
                        SevenZipCompression.DEFAULT_DICTIONARY_SIZE
                ),
                SevenZipCompression.lzma2()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.BZIP2,
                        SevenZipCompression.MAX_BZIP2_BLOCK_SIZE
                ),
                SevenZipCompression.bzip2()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.DEFLATE,
                        SevenZipCompression.MAX_DEFLATE_LEVEL
                ),
                SevenZipCompression.deflate()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.DEFLATE64,
                        SevenZipCompression.MAX_DEFLATE64_LEVEL
                ),
                SevenZipCompression.deflate64()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.PPMD,
                        SevenZipCompression.DEFAULT_PPMD_MAXIMUM_ORDER,
                        SevenZipCompression.DEFAULT_PPMD_MEMORY_SIZE
                ),
                SevenZipCompression.ppmd()
        );
        assertEquals(
                new SevenZipCompression(
                        SevenZipCompressionMethod.ZSTANDARD,
                        SevenZipCompression.DEFAULT_ZSTANDARD_LEVEL
                ),
                SevenZipCompression.zstandard()
        );
    }

    /// Verifies method-specific custom parameters and default dispatch.
    @Test
    public void customConfigurations() {
        assertEquals(64 * 1024, SevenZipCompression.lzma(64 * 1024).parameter());
        assertEquals(128 * 1024, SevenZipCompression.lzma2(128 * 1024).parameter());
        assertEquals(3, SevenZipCompression.bzip2(3).parameter());
        assertEquals(1, SevenZipCompression.deflate(1).parameter());
        assertEquals(2, SevenZipCompression.deflate64(2).parameter());
        assertEquals(5, SevenZipCompression.ppmd(5, 4 * 1024 * 1024).parameter());
        assertEquals(4 * 1024 * 1024, SevenZipCompression.ppmd(5, 4 * 1024 * 1024).secondaryParameter());
        assertEquals(7, SevenZipCompression.zstandard(7).parameter());
        assertEquals(SevenZipCompression.lzma2(), SevenZipCompression.of(SevenZipCompressionMethod.LZMA2));
        assertEquals(SevenZipCompression.ppmd(), SevenZipCompression.of(SevenZipCompressionMethod.PPMD));
        assertEquals("lzma2(131072)", SevenZipCompression.lzma2(128 * 1024).toString());
        assertEquals("ppmd(5,4194304)", SevenZipCompression.ppmd(5, 4 * 1024 * 1024).toString());
    }

    /// Verifies invalid method-specific parameters are rejected before writer creation.
    @Test
    public void invalidParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCompression(SevenZipCompressionMethod.COPY, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.lzma(SevenZipCompression.MIN_DICTIONARY_SIZE - 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.lzma2(SevenZipCompression.MAX_DICTIONARY_SIZE + 1)
        );
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.bzip2(0));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.bzip2(10));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.deflate(-1));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.deflate(10));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.deflate64(-1));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompression.deflate64(10));
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.ppmd(SevenZipCompression.MIN_PPMD_MAXIMUM_ORDER - 1, 1 << 20)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.ppmd(SevenZipCompression.MAX_PPMD_MAXIMUM_ORDER + 1, 1 << 20)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.ppmd(4, SevenZipCompression.MIN_PPMD_MEMORY_SIZE - 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.ppmd(4, SevenZipCompression.MAX_PPMD_MEMORY_SIZE + 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCompression(SevenZipCompressionMethod.COPY, 0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.zstandard(SevenZipCompression.MIN_ZSTANDARD_LEVEL - 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipCompression.zstandard(SevenZipCompression.MAX_ZSTANDARD_LEVEL + 1)
        );
    }

    /// Verifies stable case-insensitive compression method parsing.
    @Test
    public void methodParsing() {
        assertEquals(SevenZipCompressionMethod.COPY, SevenZipCompressionMethod.parse("copy"));
        assertEquals(SevenZipCompressionMethod.LZMA, SevenZipCompressionMethod.parse("LZMA"));
        assertEquals(SevenZipCompressionMethod.LZMA2, SevenZipCompressionMethod.parse("lzma2"));
        assertEquals(SevenZipCompressionMethod.BZIP2, SevenZipCompressionMethod.parse("bzip2"));
        assertEquals(SevenZipCompressionMethod.DEFLATE, SevenZipCompressionMethod.parse("deflate"));
        assertEquals(SevenZipCompressionMethod.DEFLATE64, SevenZipCompressionMethod.parse("DEFLATE64"));
        assertEquals(SevenZipCompressionMethod.PPMD, SevenZipCompressionMethod.parse("PPMd"));
        assertEquals(SevenZipCompressionMethod.ZSTANDARD, SevenZipCompressionMethod.parse("zstd"));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompressionMethod.parse("unknown"));
    }
}
