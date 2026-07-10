// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests public 7z output preprocessing filter configuration.
@NotNullByDefault
public final class SevenZipFilterTest {
    /// Verifies default configurations for every supported filter method.
    @Test
    public void defaultConfigurations() {
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.DELTA, 1), SevenZipFilter.delta());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_X86, 0), SevenZipFilter.bcjX86());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_PPC, 0), SevenZipFilter.bcjPpc());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_IA64, 0), SevenZipFilter.bcjIa64());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM, 0), SevenZipFilter.bcjArm());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM_THUMB, 0), SevenZipFilter.bcjArmThumb());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_SPARC, 0), SevenZipFilter.bcjSparc());
    }

    /// Verifies custom Delta distance and default method dispatch.
    @Test
    public void customConfigurations() {
        assertEquals(7, SevenZipFilter.delta(7).parameter());
        assertEquals(SevenZipFilter.bcjX86(), SevenZipFilter.of(SevenZipFilterMethod.BCJ_X86));
        assertEquals("delta(7)", SevenZipFilter.delta(7).toString());
    }

    /// Verifies invalid filter parameters are rejected before writer creation.
    @Test
    public void invalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.delta(0));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.delta(257));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipFilter(SevenZipFilterMethod.BCJ_X86, 1)
        );
    }

    /// Verifies stable case-insensitive filter method parsing.
    @Test
    public void methodParsing() {
        assertEquals(SevenZipFilterMethod.DELTA, SevenZipFilterMethod.parse("delta"));
        assertEquals(SevenZipFilterMethod.BCJ_X86, SevenZipFilterMethod.parse("BCJ_X86"));
        assertEquals(SevenZipFilterMethod.BCJ_PPC, SevenZipFilterMethod.parse("bcj-ppc"));
        assertEquals(SevenZipFilterMethod.BCJ_IA64, SevenZipFilterMethod.parse("bcj-ia64"));
        assertEquals(SevenZipFilterMethod.BCJ_ARM, SevenZipFilterMethod.parse("bcj-arm"));
        assertEquals(SevenZipFilterMethod.BCJ_ARM_THUMB, SevenZipFilterMethod.parse("bcj-arm-thumb"));
        assertEquals(SevenZipFilterMethod.BCJ_SPARC, SevenZipFilterMethod.parse("bcj-sparc"));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilterMethod.parse("bcj-riscv"));
    }
}
