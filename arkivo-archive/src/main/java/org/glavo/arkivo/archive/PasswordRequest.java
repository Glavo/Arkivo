// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Describes one request for archive password bytes.
///
/// The entry path, when present, is an absolute logical archive path. The zero-based attempt number allows providers
/// to select another credential after a format implementation detects an incorrect password.
///
/// @param format     the archive format requesting the password
/// @param purpose    the protected material for which the password is requested
/// @param entryPath  the absolute logical entry path associated with the request, or `null` when no single entry applies
/// @param encryption the encryption scheme associated with the request
/// @param attempt    the zero-based password attempt number
@NotNullByDefault
public record PasswordRequest(
        ArkivoFormat format,
        PasswordPurpose purpose,
        @Nullable String entryPath,
        PasswordEncryption encryption,
        int attempt
) {
    /// Creates and validates a password request.
    public PasswordRequest {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(encryption, "encryption");
        if (entryPath != null && !entryPath.startsWith("/")) {
            throw new IllegalArgumentException("entryPath must be an absolute logical archive path");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
    }
}
