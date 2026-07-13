// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies runtime discovery through the aggregate codecs module.
@NotNullByDefault
final class CodecAggregationTest {
    /// Verifies that every aggregated compression codec provider is visible.
    @Test
    void discoversAggregatedCodecs() {
        Set<String> names = CompressionCodecs.installed()
                .stream()
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("bzip2", "deflate", "deflate64", "gzip", "lzma", "ppmd", "xz", "zlib", "zstd"), names);
    }
}
