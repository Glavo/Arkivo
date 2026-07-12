// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Identifies a ZIP encryption method.
@NotNullByDefault
public final class ZipEncryption {
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

    /// The stable display name for the encryption method.
    private final String name;

    /// Creates a ZIP encryption method.
    private ZipEncryption(String name) {
        this.name = name;
    }

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

    /// Returns a ZIP encryption method with the given stable display name.
    public static ZipEncryption of(String name) {
        return switch (name) {
            case "none" -> NONE;
            case "traditional" -> TRADITIONAL;
            case "winzip-aes-128" -> WINZIP_AES_128;
            case "winzip-aes-192" -> WINZIP_AES_192;
            case "winzip-aes-256" -> WINZIP_AES_256;
            default -> new ZipEncryption(name);
        };
    }

    /// Returns the stable display name for the encryption method.
    public String name() {
        return name;
    }

    /// Compares this encryption method with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ZipEncryption that && name.equals(that.name);
    }

    /// Returns the hash code for this encryption method.
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    /// Returns the stable display name for this encryption method.
    @Override
    public String toString() {
        return name;
    }
}
