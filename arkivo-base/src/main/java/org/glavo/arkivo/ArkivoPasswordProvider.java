// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;

/// Supplies passwords for encrypted archive data.
@NotNullByDefault
public interface ArkivoPasswordProvider {
    /// Returns a provider that never supplies a password.
    static ArkivoPasswordProvider none() {
        return (rawPath, path) -> null;
    }

    /// Returns a provider that supplies a defensive copy of the given password.
    static ArkivoPasswordProvider fixed(char[] password) {
        char[] storedPassword = password.clone();
        return (rawPath, path) -> storedPassword.clone();
    }

    /// Returns a password for the archive item, or for the archive as a whole when both path values are `null`.
    char @Nullable [] passwordFor(byte @Nullable @Unmodifiable [] rawPath, @Nullable String path) throws IOException;
}
