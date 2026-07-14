// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies XXH64 against published seed-zero vectors.
@NotNullByDefault
public final class ZstdXXHash64Test {
    /// Verifies the empty and abc vectors.
    @Test
    public void matchesKnownVectors() {
        ZstdXXHash64 empty = new ZstdXXHash64();
        assertEquals(0xef46_db37_51d8_e999L, empty.digest());

        byte[] input = "abc".getBytes(StandardCharsets.US_ASCII);
        ZstdXXHash64 abc = new ZstdXXHash64();
        abc.update(input, 0, input.length);
        assertEquals(0x44bc_2cf5_ad77_0999L, abc.digest());
    }

    /// Verifies incremental stripes produce the same result as one update.
    @Test
    public void supportsIncrementalUpdates() {
        byte[] input = "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
        ZstdXXHash64 whole = new ZstdXXHash64();
        whole.update(input, 0, input.length);

        ZstdXXHash64 incremental = new ZstdXXHash64();
        for (int offset = 0; offset < input.length; offset += 7) {
            int count = Math.min(7, input.length - offset);
            incremental.update(input, offset, count);
        }
        assertEquals(whole.digest(), incremental.digest());
    }
}
