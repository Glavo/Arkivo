// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable encoding-operation options and source-size validation.
@NotNullByDefault
final class EncodingOptionsTest {
    /// Verifies default, factory, wither, and invalid source-size behavior.
    @Test
    void validatesSourceSizeMetadata() {
        assertEquals(CompressionCodec.UNKNOWN_SIZE, EncodingOptions.DEFAULT.sourceSize());
        assertSame(
                EncodingOptions.DEFAULT,
                EncodingOptions.ofSourceSize(CompressionCodec.UNKNOWN_SIZE)
        );
        assertSame(
                EncodingOptions.DEFAULT,
                EncodingOptions.DEFAULT.withSourceSize(CompressionCodec.UNKNOWN_SIZE)
        );

        EncodingOptions sized = EncodingOptions.ofSourceSize(7L);
        assertEquals(7L, sized.sourceSize());
        assertSame(sized, sized.withSourceSize(7L));
        assertEquals(new EncodingOptions(8L), sized.withSourceSize(8L));
        assertThrows(
                IllegalArgumentException.class,
                () -> EncodingOptions.ofSourceSize(CompressionCodec.UNKNOWN_SIZE - 1L)
        );
    }
}
