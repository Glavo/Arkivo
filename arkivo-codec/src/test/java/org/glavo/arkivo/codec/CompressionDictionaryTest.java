// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable compression dictionary values and identifiers.
@NotNullByDefault
final class CompressionDictionaryTest {
    /// Verifies dictionary factories and byte access cannot mutate stored content.
    @Test
    void preservesImmutableBytes() {
        byte[] source = {1, 2, 3};
        CompressionDictionary dictionary = CompressionDictionary.of(source, 42L);

        source[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());
        byte[] returned = dictionary.bytes();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());
        assertEquals(42L, dictionary.id());
        assertEquals(3, dictionary.size());
    }

    /// Verifies unknown identifiers and invalid negative identifiers are distinguished.
    @Test
    void validatesIdentifiers() {
        assertEquals(
                CompressionDictionary.UNKNOWN_ID,
                CompressionDictionary.of(new byte[0]).id()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompressionDictionary.of(new byte[0], CompressionDictionary.UNKNOWN_ID - 1L)
        );
    }
}
