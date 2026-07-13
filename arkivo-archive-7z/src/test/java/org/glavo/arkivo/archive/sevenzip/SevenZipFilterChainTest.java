// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests immutable 7z preprocessing filter chains.
@NotNullByDefault
public final class SevenZipFilterChainTest {
    /// Verifies factories preserve application order and canonicalize the empty chain.
    @Test
    public void factoriesPreserveApplicationOrder() {
        SevenZipFilter first = SevenZipFilter.bcjArm64(0x1000);
        SevenZipFilter second = SevenZipFilter.delta(4);

        assertSame(SevenZipFilterChain.EMPTY, SevenZipFilterChain.of());
        assertTrue(SevenZipFilterChain.EMPTY.isEmpty());
        assertEquals(List.of(first, second), SevenZipFilterChain.of(first, second).filters());
    }

    /// Verifies construction copies the source and exposes an unmodifiable list.
    @Test
    public void constructionCreatesImmutableSnapshot() {
        ArrayList<SevenZipFilter> source = new ArrayList<>();
        source.add(SevenZipFilter.bcjX86());
        SevenZipFilterChain chain = new SevenZipFilterChain(source);

        source.clear();
        assertEquals(List.of(SevenZipFilter.bcjX86()), chain.filters());
        assertThrows(UnsupportedOperationException.class, () -> chain.filters().clear());
        assertThrows(NullPointerException.class, () -> new SevenZipFilterChain(java.util.Arrays.asList((SevenZipFilter) null)));
    }
}
