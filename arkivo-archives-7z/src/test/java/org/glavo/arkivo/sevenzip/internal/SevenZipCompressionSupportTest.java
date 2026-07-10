// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests adaptation of public 7z compression settings to the encoder dependency.
@NotNullByDefault
public final class SevenZipCompressionSupportTest {
    /// The expected option sentinel used when a method has no encoder option.
    private static final int NO_OPTION = -1;

    /// Verifies every public method and parameter maps to the expected Commons configuration.
    @Test
    public void mapsCompressionConfigurations() {
        assertConfiguration(SevenZipCompression.copy(), SevenZMethod.COPY, NO_OPTION);
        assertConfiguration(SevenZipCompression.lzma(64 * 1024), SevenZMethod.LZMA, 64 * 1024);
        assertConfiguration(SevenZipCompression.lzma2(128 * 1024), SevenZMethod.LZMA2, 128 * 1024);
        assertConfiguration(SevenZipCompression.bzip2(3), SevenZMethod.BZIP2, 3);
        assertConfiguration(SevenZipCompression.deflate(2), SevenZMethod.DEFLATE, 2);
    }

    /// Verifies one mapped Commons configuration.
    private static void assertConfiguration(
            SevenZipCompression compression,
            SevenZMethod expectedMethod,
            int expectedOption
    ) {
        SevenZMethodConfiguration configuration = SevenZipCompressionSupport.configuration(compression);
        assertEquals(expectedMethod, configuration.getMethod());
        if (expectedOption == NO_OPTION) {
            assertNull(configuration.getOptions());
        } else {
            assertEquals(expectedOption, configuration.getOptions());
        }
    }
}
