// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies runtime discovery through the aggregate codecs module.
@NotNullByDefault
final class CodecAggregationTest {
    /// Verifies that every aggregated compression format is visible.
    @Test
    void discoversAggregatedFormats() {
        Set<String> names = CompressionFormats.installed()
                .stream()
                .map(CompressionFormat::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                Set.of(
                        "bzip2",
                        "deflate",
                        "deflate64",
                        "gzip",
                        "lzma",
                        "lzma-raw",
                        "lzma2",
                        "ppmd",
                        "xz",
                        "zlib",
                        "zstd"
                ),
                names
        );
    }
}
