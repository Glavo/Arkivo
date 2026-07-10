// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

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
    }

    /// Verifies method-specific custom parameters and default dispatch.
    @Test
    public void customConfigurations() {
        assertEquals(64 * 1024, SevenZipCompression.lzma(64 * 1024).parameter());
        assertEquals(128 * 1024, SevenZipCompression.lzma2(128 * 1024).parameter());
        assertEquals(3, SevenZipCompression.bzip2(3).parameter());
        assertEquals(1, SevenZipCompression.deflate(1).parameter());
        assertEquals(SevenZipCompression.lzma2(), SevenZipCompression.of(SevenZipCompressionMethod.LZMA2));
        assertEquals("lzma2(131072)", SevenZipCompression.lzma2(128 * 1024).toString());
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
    }

    /// Verifies stable case-insensitive compression method parsing.
    @Test
    public void methodParsing() {
        assertEquals(SevenZipCompressionMethod.COPY, SevenZipCompressionMethod.parse("copy"));
        assertEquals(SevenZipCompressionMethod.LZMA, SevenZipCompressionMethod.parse("LZMA"));
        assertEquals(SevenZipCompressionMethod.LZMA2, SevenZipCompressionMethod.parse("lzma2"));
        assertEquals(SevenZipCompressionMethod.BZIP2, SevenZipCompressionMethod.parse("bzip2"));
        assertEquals(SevenZipCompressionMethod.DEFLATE, SevenZipCompressionMethod.parse("deflate"));
        assertThrows(IllegalArgumentException.class, () -> SevenZipCompressionMethod.parse("zstd"));
    }
}
