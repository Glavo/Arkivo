// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.jetbrains.annotations.NotNullByDefault;

/// Stores validated immutable LZMA literal, position, and dictionary properties.
///
/// Values are safe for concurrent use. The packed property byte contains the three bit-count fields but not the
/// dictionary size; raw LZMA and LZMA2 containers must preserve that size separately. Configuration methods return this
/// value when the requested component is unchanged.
///
/// @param literalContextBits  the number of previous-byte high bits used by literal contexts
/// @param literalPositionBits the number of output-position low bits used by literal contexts
/// @param positionBits        the number of output-position low bits used by match contexts
/// @param dictionarySize      the declared LZ dictionary size in bytes
@NotNullByDefault
public record LZMAProperties(
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

    /// The minimum allocated dictionary size required by the implementation.
    public static final int MINIMUM_DICTIONARY_SIZE = 4 * 1024;

    /// The largest dictionary accepted by Arkivo's Java decoder implementation.
    public static final int MAXIMUM_DICTIONARY_SIZE = 3 * 512 * 1024 * 1024;

    /// Validates all LZMA properties.
    ///
    /// @throws IllegalArgumentException if a bit count or dictionary size is outside the supported range, or if the
    ///                                  literal-context and literal-position counts sum to more than four
    public LZMAProperties {
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
            throw new IllegalArgumentException(
                    "Unsupported LZMA dictionary size: " + Integer.toUnsignedLong(dictionarySize)
            );
        }
    }

    /// Creates the common literal and position defaults with the requested dictionary size.
    ///
    /// @param dictionarySize the declared dictionary size, in bytes
    /// @return immutable properties with the default bit counts and requested dictionary size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public static LZMAProperties defaults(int dictionarySize) {
        return new LZMAProperties(
                DEFAULT_LITERAL_CONTEXT_BITS,
                DEFAULT_LITERAL_POSITION_BITS,
                DEFAULT_POSITION_BITS,
                dictionarySize
        );
    }

    /// Parses one packed LZMA property byte and dictionary size.
    ///
    /// @param property the unsigned packed property-byte value, from {@code 0} through {@code 224}
    /// @param dictionarySize the separately declared dictionary size, in bytes
    /// @return the decoded immutable model properties
    /// @throws IllegalArgumentException if {@code property} is invalid or {@code dictionarySize} is unsupported
    public static LZMAProperties decode(int property, int dictionarySize) {
        if (property < 0 || property >= 9 * 5 * 5) {
            throw new IllegalArgumentException("Invalid LZMA property byte: " + property);
        }
        int remainder = property;
        int literalContextBits = remainder % 9;
        remainder /= 9;
        int literalPositionBits = remainder % 5;
        int positionBits = remainder / 5;
        return new LZMAProperties(
                literalContextBits,
                literalPositionBits,
                positionBits,
                dictionarySize
        );
    }

    /// Returns a copy with the requested dictionary size.
    ///
    /// @param dictionarySize the replacement declared dictionary size, in bytes
    /// @return this value if the size is unchanged; otherwise, new properties with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public LZMAProperties withDictionarySize(int dictionarySize) {
        return dictionarySize == this.dictionarySize
                ? this
                : new LZMAProperties(
                        literalContextBits,
                        literalPositionBits,
                        positionBits,
                        dictionarySize
                );
    }

    /// Returns a copy with the requested literal-context bit count.
    ///
    /// @param literalContextBits the replacement bit count, from {@code 0} through {@code 8}
    /// @return this value if the count is unchanged; otherwise, new properties with the requested count
    /// @throws IllegalArgumentException if the count is out of range or its sum with the literal-position count exceeds four
    public LZMAProperties withLiteralContextBits(int literalContextBits) {
        return literalContextBits == this.literalContextBits
                ? this
                : new LZMAProperties(
                        literalContextBits,
                        literalPositionBits,
                        positionBits,
                        dictionarySize
                );
    }

    /// Returns a copy with the requested literal-position bit count.
    ///
    /// @param literalPositionBits the replacement bit count, from {@code 0} through {@code 4}
    /// @return this value if the count is unchanged; otherwise, new properties with the requested count
    /// @throws IllegalArgumentException if the count is out of range or its sum with the literal-context count exceeds four
    public LZMAProperties withLiteralPositionBits(int literalPositionBits) {
        return literalPositionBits == this.literalPositionBits
                ? this
                : new LZMAProperties(
                        literalContextBits,
                        literalPositionBits,
                        positionBits,
                        dictionarySize
                );
    }

    /// Returns a copy with the requested match-position bit count.
    ///
    /// @param positionBits the replacement bit count, from {@code 0} through {@code 4}
    /// @return this value if the count is unchanged; otherwise, new properties with the requested count
    /// @throws IllegalArgumentException if {@code positionBits} is outside the supported range
    public LZMAProperties withPositionBits(int positionBits) {
        return positionBits == this.positionBits
                ? this
                : new LZMAProperties(
                        literalContextBits,
                        literalPositionBits,
                        positionBits,
                        dictionarySize
                );
    }

    /// Returns the packed LZMA property byte.
    ///
    /// @return the unsigned property-byte value, from {@code 0} through {@code 224}
    public int propertyByte() {
        return (positionBits * 5 + literalPositionBits) * 9 + literalContextBits;
    }
}
