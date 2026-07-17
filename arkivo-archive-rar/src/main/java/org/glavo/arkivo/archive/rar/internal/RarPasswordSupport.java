// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.PasswordEncryption;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.PasswordRequest;
import org.glavo.arkivo.archive.rar.RarArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Creates password requests for supported RAR encryption schemes.
@NotNullByDefault
final class RarPasswordSupport {
    /// The RAR 3.x AES-128 encryption description.
    private static final PasswordEncryption RAR3_AES = PasswordEncryption.of("rar3-aes-128", 128);

    /// The RAR 5 AES-256 encryption description.
    private static final PasswordEncryption RAR5_AES = PasswordEncryption.of("rar5-aes-256", 256);

    /// Creates no instances.
    private RarPasswordSupport() {
    }

    /// Creates a password request for legacy RAR entry content.
    static PasswordRequest legacyEntry(String entryPath, int extractionVersion) {
        return request(
                PasswordPurpose.ENTRY_CONTENT,
                entryPath,
                PasswordEncryption.of("rar-legacy-" + extractionVersion)
        );
    }

    /// Creates a password request for RAR 3.x AES-protected material.
    static PasswordRequest rar3(PasswordPurpose purpose, @Nullable String entryPath) {
        return request(purpose, entryPath, RAR3_AES);
    }

    /// Creates a password request for RAR 5 AES-protected material.
    static PasswordRequest rar5(PasswordPurpose purpose, @Nullable String entryPath) {
        return request(purpose, entryPath, RAR5_AES);
    }

    /// Creates a RAR password request.
    private static PasswordRequest request(
            PasswordPurpose purpose,
            @Nullable String entryPath,
            PasswordEncryption encryption
    ) {
        String absoluteEntryPath = entryPath == null || entryPath.startsWith("/") ? entryPath : "/" + entryPath;
        return new PasswordRequest(
                RarArkivoFormat.instance(),
                purpose,
                absoluteEntryPath,
                encryption,
                0
        );
    }
}
