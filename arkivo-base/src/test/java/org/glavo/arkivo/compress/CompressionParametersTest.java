// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests compression parameter construction.
@NotNullByDefault
public final class CompressionParametersTest {
    /// Creates a compression parameter test instance.
    public CompressionParametersTest() {
    }

    /// Verifies that default builder values reuse the shared default instance.
    @Test
    public void defaultBuilderValues() {
        CompressionParameters parameters = CompressionParameters.builder().build();
        assertSame(CompressionParameters.defaults(), parameters);
        assertEquals(CompressionParameters.DEFAULT_COMPRESSION_LEVEL, parameters.compressionLevel());
        assertEquals(CompressionParameters.UNKNOWN_UNCOMPRESSED_SIZE, parameters.expectedUncompressedSize());
        assertNull(parameters.dictionary());
    }

    /// Verifies that dictionary bytes are copied into and out of parameters.
    @Test
    public void dictionaryIsCopied() {
        byte[] dictionary = {1, 2, 3};
        CompressionParameters parameters = CompressionParameters.builder()
                .compressionLevel(3)
                .expectedUncompressedSize(12)
                .dictionary(dictionary)
                .build();

        dictionary[0] = 9;
        assertEquals(3, parameters.compressionLevel());
        assertEquals(12, parameters.expectedUncompressedSize());
        assertArrayEquals(new byte[]{1, 2, 3}, parameters.dictionary());

        byte[] returned = parameters.dictionary();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, parameters.dictionary());
    }
}
