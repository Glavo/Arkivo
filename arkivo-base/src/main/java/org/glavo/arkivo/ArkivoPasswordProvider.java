// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Supplies passwords for encrypted archive data.
@NotNullByDefault
public interface ArkivoPasswordProvider {
    /// Returns a provider that never supplies a password.
    static ArkivoPasswordProvider none() {
        return name -> null;
    }

    /// Returns a provider that supplies a defensive copy of the given password.
    static ArkivoPasswordProvider fixed(char[] password) {
        char[] storedPassword = password.clone();
        return name -> storedPassword.clone();
    }

    /// Returns a password for the archive item, or for the archive as a whole when the name is `null`.
    char @Nullable [] passwordFor(@Nullable ArkivoName name) throws IOException;
}
