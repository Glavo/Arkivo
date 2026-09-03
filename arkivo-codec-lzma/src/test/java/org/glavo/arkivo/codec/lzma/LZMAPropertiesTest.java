// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies LZMA property packing, validation boundaries, and immutable reconfiguration.
@NotNullByDefault
final class LZMAPropertiesTest {
    /// Verifies the default factory uses the documented model constants for every supported dictionary boundary.
    @Test
    void createsDefaultProperties() {
        LZMAProperties zeroDictionary = LZMAProperties.defaults(0);
        assertEquals(LZMAProperties.DEFAULT_LITERAL_CONTEXT_BITS, zeroDictionary.literalContextBits());
        assertEquals(LZMAProperties.DEFAULT_LITERAL_POSITION_BITS, zeroDictionary.literalPositionBits());
        assertEquals(LZMAProperties.DEFAULT_POSITION_BITS, zeroDictionary.positionBits());
        assertEquals(0, zeroDictionary.dictionarySize());
        assertEquals(0x5d, zeroDictionary.propertyByte());

        LZMAProperties maximumDictionary =
                LZMAProperties.defaults(LZMAProperties.MAXIMUM_DICTIONARY_SIZE);
        assertEquals(LZMAProperties.MAXIMUM_DICTIONARY_SIZE, maximumDictionary.dictionarySize());
    }

    /// Verifies every representable packed property is either decoded exactly or rejected for the supported lc/lp sum.
    @Test
    void roundTripsEveryPackedPropertyValue() {
        int dictionarySize = 1 << 20;
        for (int property = 0; property < 9 * 5 * 5; property++) {
            int literalContextBits = property % 9;
            int quotient = property / 9;
            int literalPositionBits = quotient % 5;
            int positionBits = quotient / 5;

            if (literalContextBits + literalPositionBits > 4) {
                int unsupportedProperty = property;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> LZMAProperties.decode(unsupportedProperty, dictionarySize),
                        Integer.toString(property)
                );
                continue;
            }

            LZMAProperties decoded = LZMAProperties.decode(property, dictionarySize);
            assertEquals(literalContextBits, decoded.literalContextBits(), Integer.toString(property));
            assertEquals(literalPositionBits, decoded.literalPositionBits(), Integer.toString(property));
            assertEquals(positionBits, decoded.positionBits(), Integer.toString(property));
            assertEquals(dictionarySize, decoded.dictionarySize(), Integer.toString(property));
            assertEquals(property, decoded.propertyByte(), Integer.toString(property));
        }

        assertThrows(IllegalArgumentException.class, () -> LZMAProperties.decode(-1, dictionarySize));
        assertThrows(IllegalArgumentException.class, () -> LZMAProperties.decode(9 * 5 * 5, dictionarySize));
    }

    /// Verifies every constructor field accepts its inclusive boundary and rejects values immediately outside it.
    @Test
    void validatesConstructorBoundaries() {
        assertEquals(
                new LZMAProperties(4, 0, 4, LZMAProperties.MAXIMUM_DICTIONARY_SIZE),
                new LZMAProperties(4, 0, 4, LZMAProperties.MAXIMUM_DICTIONARY_SIZE)
        );
        assertEquals(0, new LZMAProperties(0, 4, 0, 0).dictionarySize());

        assertInvalid(-1, 0, 0, 0);
        assertInvalid(9, 0, 0, 0);
        assertInvalid(0, -1, 0, 0);
        assertInvalid(0, 5, 0, 0);
        assertInvalid(4, 1, 0, 0);
        assertInvalid(0, 0, -1, 0);
        assertInvalid(0, 0, 5, 0);
        assertInvalid(0, 0, 0, -1);
        assertInvalid(0, 0, 0, LZMAProperties.MAXIMUM_DICTIONARY_SIZE + 1);
    }

    /// Verifies withers preserve identity for equal values and copy only the requested component otherwise.
    @Test
    void reconfiguresImmutably() {
        LZMAProperties properties = new LZMAProperties(1, 2, 3, 1 << 16);

        assertSame(properties, properties.withLiteralContextBits(1));
        assertSame(properties, properties.withLiteralPositionBits(2));
        assertSame(properties, properties.withPositionBits(3));
        assertSame(properties, properties.withDictionarySize(1 << 16));

        LZMAProperties changedContext = properties.withLiteralContextBits(2);
        assertNotSame(properties, changedContext);
        assertEquals(new LZMAProperties(2, 2, 3, 1 << 16), changedContext);
        assertEquals(new LZMAProperties(1, 1, 3, 1 << 16), properties.withLiteralPositionBits(1));
        assertEquals(new LZMAProperties(1, 2, 4, 1 << 16), properties.withPositionBits(4));
        assertEquals(new LZMAProperties(1, 2, 3, 1 << 17), properties.withDictionarySize(1 << 17));
        assertEquals(new LZMAProperties(1, 2, 3, 1 << 16), properties);

        assertThrows(IllegalArgumentException.class, () -> properties.withLiteralContextBits(3));
        assertThrows(IllegalArgumentException.class, () -> properties.withLiteralPositionBits(4));
        assertThrows(IllegalArgumentException.class, () -> properties.withPositionBits(5));
        assertThrows(IllegalArgumentException.class, () -> properties.withDictionarySize(-1));
    }

    /// Verifies one constructor tuple is rejected.
    private static void assertInvalid(
            int literalContextBits,
            int literalPositionBits,
            int positionBits,
            int dictionarySize
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(
                        literalContextBits,
                        literalPositionBits,
                        positionBits,
                        dictionarySize
                )
        );
    }
}
