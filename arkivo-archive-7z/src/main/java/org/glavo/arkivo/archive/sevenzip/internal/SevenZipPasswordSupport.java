// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.PasswordEncryption;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.PasswordRequest;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Creates password requests for 7z AES-256 encryption.
@NotNullByDefault
final class SevenZipPasswordSupport {
    /// The 7z AES-256 encryption description.
    private static final PasswordEncryption ENCRYPTION = PasswordEncryption.of("7z-aes-256", 256);

    /// Creates no instances.
    private SevenZipPasswordSupport() {
    }

    /// Creates a 7z password request for the given protected material.
    static PasswordRequest request(PasswordPurpose purpose, @Nullable String entryPath) {
        return new PasswordRequest(
                SevenZipArkivoFormat.instance(),
                purpose,
                entryPath,
                ENCRYPTION,
                0
        );
    }
}
