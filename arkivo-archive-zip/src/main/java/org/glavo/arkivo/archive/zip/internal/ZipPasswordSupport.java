// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.PasswordEncryption;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.PasswordRequest;
import org.glavo.arkivo.archive.zip.ZipArkivoFormat;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Creates password requests for ZIP encryption methods.
@NotNullByDefault
final class ZipPasswordSupport {
    /// Creates no instances.
    private ZipPasswordSupport() {
    }

    /// Creates a password request for one encrypted ZIP entry.
    static PasswordRequest entryRequest(String entryPath, ZipEncryption encryption) {
        Objects.requireNonNull(entryPath, "entryPath");
        Objects.requireNonNull(encryption, "encryption");
        int keyLengthBits;
        if (encryption.equals(ZipEncryption.winZipAes128())) {
            keyLengthBits = 128;
        } else if (encryption.equals(ZipEncryption.winZipAes192())) {
            keyLengthBits = 192;
        } else if (encryption.equals(ZipEncryption.winZipAes256())) {
            keyLengthBits = 256;
        } else {
            keyLengthBits = PasswordEncryption.UNKNOWN_KEY_LENGTH;
        }
        return new PasswordRequest(
                ZipArkivoFormat.instance(),
                PasswordPurpose.ENTRY_CONTENT,
                entryPath,
                PasswordEncryption.of(encryption.name(), keyLengthBits),
                0
        );
    }
}
