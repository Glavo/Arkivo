// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Provides validated lzip header constants and dictionary-size coding.
@NotNullByDefault
public final class LzipSupport {
    /// The fixed lzip member signature.
    public static final byte @Unmodifiable [] MAGIC = {'L', 'Z', 'I', 'P'};

    /// The only currently defined lzip member version.
    public static final int VERSION = 1;

    /// The fixed lzip member header size.
    public static final int HEADER_SIZE = 6;

    /// The fixed lzip member trailer size.
    public static final int TRAILER_SIZE = 20;

    /// The maximum member size emitted by the reference lzip implementation.
    public static final long MAXIMUM_MEMBER_SIZE = 2L << 50;

    /// Creates no instances.
    private LzipSupport() {
    }

    /// Encodes an exactly representable lzip dictionary size into its one-byte header field.
    ///
    /// @param dictionarySize the dictionary size to encode, in bytes
    /// @return the unsigned value of the encoded header byte
    /// @throws IllegalArgumentException if {@code dictionarySize} is not exactly representable
    public static int encodeDictionarySize(int dictionarySize) {
        for (int logarithm = 12; logarithm <= 29; logarithm++) {
            int base = 1 << logarithm;
            int unit = base >>> 4;
            for (int fraction = 0; fraction <= 7; fraction++) {
                if (base - fraction * unit == dictionarySize) {
                    return fraction << 5 | logarithm;
                }
            }
        }
        throw new IllegalArgumentException(
                "Lzip dictionary size is not exactly representable: " + dictionarySize
        );
    }

    /// Decodes and validates the one-byte dictionary-size field from a lzip member header.
    ///
    /// @param encoded the unsigned value of the header byte, from {@code 0} through {@code 255}
    /// @return the decoded dictionary size, in bytes
    /// @throws IllegalArgumentException if {@code encoded} is outside the unsigned-byte range or has an invalid exponent
    public static int decodeDictionarySize(int encoded) {
        if (encoded < 0 || encoded > 0xff) {
            throw new IllegalArgumentException("Lzip dictionary code must be an unsigned byte: " + encoded);
        }
        int logarithm = encoded & 0x1f;
        if (logarithm < 12 || logarithm > 29) {
            throw new IllegalArgumentException("Invalid lzip dictionary code: " + encoded);
        }
        int base = 1 << logarithm;
        return base - (encoded >>> 5) * (base >>> 4);
    }

}
