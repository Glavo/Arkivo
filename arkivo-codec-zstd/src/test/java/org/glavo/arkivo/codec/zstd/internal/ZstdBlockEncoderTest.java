// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies block-level match and offset state transitions.
@NotNullByDefault
public final class ZstdBlockEncoderTest {
    /// Verifies repeated offsets with literals use the three canonical move-to-front codes.
    @Test
    public void selectsRepeatedOffsetsWithLiterals() {
        ZstdBlockEncoder.RepeatedOffsets repeated =
                new ZstdBlockEncoder.RepeatedOffsets(1, 4, 8);

        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(1L, repeated),
                ZstdBlockEncoder.selectOffset(1, 3, repeated, true)
        );
        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(
                        2L,
                        new ZstdBlockEncoder.RepeatedOffsets(4, 1, 8)
                ),
                ZstdBlockEncoder.selectOffset(4, 3, repeated, true)
        );
        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(
                        3L,
                        new ZstdBlockEncoder.RepeatedOffsets(8, 1, 4)
                ),
                ZstdBlockEncoder.selectOffset(8, 3, repeated, true)
        );
    }

    /// Verifies zero-literal sequences apply the shifted repeated-offset interpretation.
    @Test
    public void selectsRepeatedOffsetsWithoutLiterals() {
        ZstdBlockEncoder.RepeatedOffsets repeated =
                new ZstdBlockEncoder.RepeatedOffsets(6, 4, 8);

        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(
                        1L,
                        new ZstdBlockEncoder.RepeatedOffsets(4, 6, 8)
                ),
                ZstdBlockEncoder.selectOffset(4, 0, repeated, true)
        );
        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(
                        2L,
                        new ZstdBlockEncoder.RepeatedOffsets(8, 6, 4)
                ),
                ZstdBlockEncoder.selectOffset(8, 0, repeated, true)
        );
        assertEquals(
                new ZstdBlockEncoder.OffsetEncoding(
                        3L,
                        new ZstdBlockEncoder.RepeatedOffsets(5, 6, 4)
                ),
                ZstdBlockEncoder.selectOffset(5, 0, repeated, true)
        );
    }

    /// Verifies explicit offsets update state and can bypass unknown contextual state.
    @Test
    public void selectsExplicitOffsets() {
        ZstdBlockEncoder.RepeatedOffsets repeated =
                new ZstdBlockEncoder.RepeatedOffsets(8, 4, 2);
        ZstdBlockEncoder.OffsetEncoding expected = new ZstdBlockEncoder.OffsetEncoding(
                7L,
                new ZstdBlockEncoder.RepeatedOffsets(4, 8, 4)
        );

        assertEquals(expected, ZstdBlockEncoder.selectOffset(4, 5, repeated, false));
        assertEquals(expected, ZstdBlockEncoder.selectOffset(4, 0, repeated, false));
    }
}
