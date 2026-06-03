// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies a ZIP encryption method.
///
/// @param name the stable display name for the encryption method
@NotNullByDefault
public record ZipEncryption(
        String name
) {
    /// The unencrypted ZIP method.
    private static final ZipEncryption NONE = new ZipEncryption("none");

    /// The traditional PKWARE ZIP encryption method.
    private static final ZipEncryption TRADITIONAL = new ZipEncryption("traditional");

    /// The WinZip AES-128 encryption method.
    private static final ZipEncryption WINZIP_AES_128 = new ZipEncryption("winzip-aes-128");

    /// The WinZip AES-192 encryption method.
    private static final ZipEncryption WINZIP_AES_192 = new ZipEncryption("winzip-aes-192");

    /// The WinZip AES-256 encryption method.
    private static final ZipEncryption WINZIP_AES_256 = new ZipEncryption("winzip-aes-256");

    /// Returns the unencrypted ZIP method.
    public static ZipEncryption none() {
        return NONE;
    }

    /// Returns the traditional PKWARE ZIP encryption method.
    public static ZipEncryption traditional() {
        return TRADITIONAL;
    }

    /// Returns the WinZip AES-128 encryption method.
    public static ZipEncryption winZipAes128() {
        return WINZIP_AES_128;
    }

    /// Returns the WinZip AES-192 encryption method.
    public static ZipEncryption winZipAes192() {
        return WINZIP_AES_192;
    }

    /// Returns the WinZip AES-256 encryption method.
    public static ZipEncryption winZipAes256() {
        return WINZIP_AES_256;
    }
}
