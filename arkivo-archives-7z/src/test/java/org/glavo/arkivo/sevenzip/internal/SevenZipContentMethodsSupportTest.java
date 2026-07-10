// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.glavo.arkivo.sevenzip.SevenZipFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests adaptation of public 7z compression and preprocessing settings to the encoder dependency.
@NotNullByDefault
public final class SevenZipContentMethodsSupportTest {
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

    /// Verifies every public filter and parameter maps to the expected Commons configuration.
    @Test
    public void mapsFilterConfigurations() {
        assertConfiguration(SevenZipFilter.delta(7), SevenZMethod.DELTA_FILTER, 7);
        assertConfiguration(SevenZipFilter.bcjX86(), SevenZMethod.BCJ_X86_FILTER, NO_OPTION);
        assertConfiguration(SevenZipFilter.bcjPpc(), SevenZMethod.BCJ_PPC_FILTER, NO_OPTION);
        assertConfiguration(SevenZipFilter.bcjIa64(), SevenZMethod.BCJ_IA64_FILTER, NO_OPTION);
        assertConfiguration(SevenZipFilter.bcjArm(), SevenZMethod.BCJ_ARM_FILTER, NO_OPTION);
        assertConfiguration(SevenZipFilter.bcjArmThumb(), SevenZMethod.BCJ_ARM_THUMB_FILTER, NO_OPTION);
        assertConfiguration(SevenZipFilter.bcjSparc(), SevenZMethod.BCJ_SPARC_FILTER, NO_OPTION);
    }

    /// Verifies one mapped Commons configuration.
    private static void assertConfiguration(
            SevenZipCompression compression,
            SevenZMethod expectedMethod,
            int expectedOption
    ) {
        SevenZMethodConfiguration configuration = SevenZipContentMethodsSupport.configuration(compression);
        assertEquals(expectedMethod, configuration.getMethod());
        if (expectedOption == NO_OPTION) {
            assertNull(configuration.getOptions());
        } else {
            assertEquals(expectedOption, configuration.getOptions());
        }
    }

    /// Verifies one mapped Commons filter configuration.
    private static void assertConfiguration(
            SevenZipFilter filter,
            SevenZMethod expectedMethod,
            int expectedOption
    ) {
        SevenZMethodConfiguration configuration = SevenZipContentMethodsSupport.configuration(filter);
        assertEquals(expectedMethod, configuration.getMethod());
        if (expectedOption == NO_OPTION) {
            assertNull(configuration.getOptions());
        } else {
            assertEquals(expectedOption, configuration.getOptions());
        }
    }
}
