// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Describes the encryption scheme associated with a password request.
///
/// The stable scheme name is format-defined so third-party archive formats can expose encryption methods without
/// requiring changes to this module.
///
/// @param scheme        the stable, non-empty encryption scheme name
/// @param keyLengthBits the encryption key length in bits, or [#UNKNOWN_KEY_LENGTH] when it is unknown or inapplicable
@NotNullByDefault
public record PasswordEncryption(String scheme, int keyLengthBits) {
    /// Indicates that the encryption key length is unknown or inapplicable.
    public static final int UNKNOWN_KEY_LENGTH = -1;

    /// Creates and validates an encryption description.
    public PasswordEncryption {
        Objects.requireNonNull(scheme, "scheme");
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        if (keyLengthBits != UNKNOWN_KEY_LENGTH && keyLengthBits <= 0) {
            throw new IllegalArgumentException("keyLengthBits must be positive or UNKNOWN_KEY_LENGTH");
        }
    }

    /// Creates an encryption description whose key length is unknown or inapplicable.
    ///
    /// @param scheme the non-blank stable encryption scheme name
    /// @return an encryption description with {@link #UNKNOWN_KEY_LENGTH}
    public static PasswordEncryption of(String scheme) {
        return new PasswordEncryption(scheme, UNKNOWN_KEY_LENGTH);
    }

    /// Creates an encryption description with a known key length.
    ///
    /// @param scheme        the non-blank stable encryption scheme name
    /// @param keyLengthBits the positive encryption key length in bits
    /// @return an encryption description with the specified key length
    public static PasswordEncryption of(String scheme, int keyLengthBits) {
        return new PasswordEncryption(scheme, keyLengthBits);
    }
}
