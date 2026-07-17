// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Objects;

/// Identifies a ZIP encryption method.
@NotNullByDefault
public enum ZipEncryption {
    /// Leaves the entry data unencrypted.
    NONE("none"),

    /// Encrypts the entry with the traditional PKWARE ZipCrypto scheme.
    ZIP_CRYPTO("zipcrypto"),

    /// Encrypts the entry with the WinZip AES format and a 128-bit key.
    WINZIP_AES_128("winzip-aes-128"),

    /// Encrypts the entry with the WinZip AES format and a 192-bit key.
    WINZIP_AES_192("winzip-aes-192"),

    /// Encrypts the entry with the WinZip AES format and a 256-bit key.
    WINZIP_AES_256("winzip-aes-256");

    /// The stable external identifier for the encryption method.
    private final String id;

    /// Creates an encryption method with its stable external identifier.
    ZipEncryption(String id) {
        this.id = id;
    }

    /// Parses a stable case-insensitive encryption method identifier.
    public static ZipEncryption parse(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        for (ZipEncryption encryption : values()) {
            if (encryption.id.equals(normalized)) {
                return encryption;
            }
        }
        throw new IllegalArgumentException("Unknown ZIP encryption method: " + value);
    }

    /// Returns the stable external identifier for the encryption method.
    public String id() {
        return id;
    }

    /// Returns the stable external identifier for this encryption method.
    @Override
    public String toString() {
        return id;
    }
}
