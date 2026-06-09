// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archives;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies runtime discovery through the aggregate archive module.
@NotNullByDefault
final class ArchiveAggregationTest {
    /// Verifies that every aggregated archive format provider is visible.
    @Test
    void discoversAggregatedFormats() {
        Set<String> names = ArkivoFormats.installed()
                .stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("7z", "tar", "zip"), names);
    }
}
