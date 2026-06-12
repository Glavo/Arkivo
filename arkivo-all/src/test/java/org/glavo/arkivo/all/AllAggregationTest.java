// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoFormats;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies runtime discovery through the all-in-one aggregate module.
@NotNullByDefault
final class AllAggregationTest {
    /// Verifies that all aggregated archive and codec providers are visible.
    @Test
    void discoversAllAggregatedProviders() {
        Set<String> archiveNames = ArkivoFormats.installed()
                .stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> codecNames = CompressionCodecs.installed()
                .stream()
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("7z", "ar", "rar", "tar", "zip"), archiveNames);
        assertEquals(Set.of("deflate", "gzip", "lzma", "xz", "zlib", "zstd"), codecNames);
    }
}
