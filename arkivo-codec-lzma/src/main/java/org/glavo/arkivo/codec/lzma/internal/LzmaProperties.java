// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Stores validated LZMA literal, position, and dictionary properties.
///
/// @param literalContextBits  the number of previous-byte high bits used by literal contexts
/// @param literalPositionBits the number of output-position low bits used by literal contexts
/// @param positionBits        the number of output-position low bits used by match contexts
/// @param dictionarySize      the declared LZ dictionary size in bytes
@NotNullByDefault
public record LzmaProperties(
        int literalContextBits,
        int literalPositionBits,
        int positionBits,
        int dictionarySize
) {
    /// The default LZMA literal-context bit count.
    public static final int DEFAULT_LITERAL_CONTEXT_BITS = 3;

    /// The default LZMA literal-position bit count.
    public static final int DEFAULT_LITERAL_POSITION_BITS = 0;

    /// The default LZMA position-state bit count.
    public static final int DEFAULT_POSITION_BITS = 2;

    /// The minimum allocated dictionary size required by the LZMA format.
    public static final int MINIMUM_DICTIONARY_SIZE = 4 * 1024;

    /// The largest dictionary accepted by Arkivo's Java decoder implementation.
    public static final int MAXIMUM_DICTIONARY_SIZE = 3 * 512 * 1024 * 1024;

    /// Validates LZMA properties.
    public LzmaProperties {
        if (literalContextBits < 0 || literalContextBits > 8) {
            throw new IllegalArgumentException("LZMA lc must be between 0 and 8");
        }
        if (literalPositionBits < 0 || literalPositionBits > 4) {
            throw new IllegalArgumentException("LZMA lp must be between 0 and 4");
        }
        if (literalContextBits + literalPositionBits > 4) {
            throw new IllegalArgumentException("LZMA lc + lp must not exceed 4");
        }
        if (positionBits < 0 || positionBits > 4) {
            throw new IllegalArgumentException("LZMA pb must be between 0 and 4");
        }
        if (dictionarySize < 0 || dictionarySize > MAXIMUM_DICTIONARY_SIZE) {
            throw new IllegalArgumentException("Unsupported LZMA dictionary size: "
                    + Integer.toUnsignedLong(dictionarySize));
        }
    }

    /// Creates the common default LZMA properties with the requested dictionary size.
    public static LzmaProperties defaults(int dictionarySize) {
        return new LzmaProperties(
                DEFAULT_LITERAL_CONTEXT_BITS,
                DEFAULT_LITERAL_POSITION_BITS,
                DEFAULT_POSITION_BITS,
                dictionarySize
        );
    }

    /// Parses one packed LZMA property byte and dictionary size.
    public static LzmaProperties decode(int property, int dictionarySize) {
        if (property < 0 || property >= 9 * 5 * 5) {
            throw new IllegalArgumentException("Invalid LZMA property byte: " + property);
        }
        int remainder = property;
        int literalContextBits = remainder % 9;
        remainder /= 9;
        int literalPositionBits = remainder % 5;
        int positionBits = remainder / 5;
        return new LzmaProperties(
                literalContextBits,
                literalPositionBits,
                positionBits,
                dictionarySize
        );
    }

    /// Returns the packed LZMA property byte.
    public int propertyByte() {
        return (positionBits * 5 + literalPositionBits) * 9 + literalContextBits;
    }

    /// Returns the actual minimum-sized dictionary allocation.
    int allocatedDictionarySize() {
        return Math.max(dictionarySize, MINIMUM_DICTIONARY_SIZE);
    }
}
