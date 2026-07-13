// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

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
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ2, 0), SevenZipFilter.bcj2());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_PPC, 0), SevenZipFilter.bcjPpc());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_IA64, 0), SevenZipFilter.bcjIa64());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM, 0), SevenZipFilter.bcjArm());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM_THUMB, 0), SevenZipFilter.bcjArmThumb());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_SPARC, 0), SevenZipFilter.bcjSparc());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM64, 0), SevenZipFilter.bcjArm64());
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_RISCV, 0), SevenZipFilter.bcjRiscV());
    }

    /// Verifies custom Delta distances and BCJ start offsets.
    @Test
    public void customConfigurations() {
        assertEquals(7L, SevenZipFilter.delta(7).parameter());
        assertEquals(SevenZipFilter.bcjX86(), SevenZipFilter.of(SevenZipFilterMethod.BCJ_X86));
        assertEquals(SevenZipFilter.bcj2(), SevenZipFilter.of(SevenZipFilterMethod.BCJ2));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_X86, 0xffff_ffffL), SevenZipFilter.bcjX86(0xffff_ffffL));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_PPC, 4), SevenZipFilter.bcjPpc(4));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_IA64, 16), SevenZipFilter.bcjIa64(16));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM, 8), SevenZipFilter.bcjArm(8));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM_THUMB, 2), SevenZipFilter.bcjArmThumb(2));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_SPARC, 12), SevenZipFilter.bcjSparc(12));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM64, 20), SevenZipFilter.bcjArm64(20));
        assertEquals(new SevenZipFilter(SevenZipFilterMethod.BCJ_RISCV, 6), SevenZipFilter.bcjRiscV(6));
        assertEquals("delta(7)", SevenZipFilter.delta(7).toString());
        assertEquals("bcj2", SevenZipFilter.bcj2().toString());
        assertEquals("bcj-arm64(20)", SevenZipFilter.bcjArm64(20).toString());
    }

    /// Verifies invalid filter parameters are rejected before writer creation.
    @Test
    public void invalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.delta(0));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.delta(257));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjX86(-1));
        assertThrows(IllegalArgumentException.class, () -> new SevenZipFilter(SevenZipFilterMethod.BCJ2, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFilter.bcjX86(SevenZipFilter.MAX_BCJ_START_OFFSET + 1)
        );
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjPpc(2));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjIa64(4));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjArm(2));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjArmThumb(1));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjSparc(2));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjArm64(2));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilter.bcjRiscV(1));
    }

    /// Verifies stable case-insensitive filter method parsing.
    @Test
    public void methodParsing() {
        assertEquals(SevenZipFilterMethod.DELTA, SevenZipFilterMethod.parse("delta"));
        assertEquals(SevenZipFilterMethod.BCJ_X86, SevenZipFilterMethod.parse("BCJ_X86"));
        assertEquals(SevenZipFilterMethod.BCJ2, SevenZipFilterMethod.parse("BCJ2"));
        assertEquals(SevenZipFilterMethod.BCJ_PPC, SevenZipFilterMethod.parse("bcj-ppc"));
        assertEquals(SevenZipFilterMethod.BCJ_IA64, SevenZipFilterMethod.parse("bcj-ia64"));
        assertEquals(SevenZipFilterMethod.BCJ_ARM, SevenZipFilterMethod.parse("bcj-arm"));
        assertEquals(SevenZipFilterMethod.BCJ_ARM_THUMB, SevenZipFilterMethod.parse("bcj-arm-thumb"));
        assertEquals(SevenZipFilterMethod.BCJ_SPARC, SevenZipFilterMethod.parse("bcj-sparc"));
        assertEquals(SevenZipFilterMethod.BCJ_ARM64, SevenZipFilterMethod.parse("BCJ_ARM64"));
        assertEquals(SevenZipFilterMethod.BCJ_RISCV, SevenZipFilterMethod.parse("bcj-riscv"));
        assertThrows(IllegalArgumentException.class, () -> SevenZipFilterMethod.parse("bcj-mips"));
    }
}
