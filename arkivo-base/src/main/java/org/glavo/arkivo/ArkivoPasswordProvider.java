// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;

/// Supplies passwords for encrypted archive data.
@NotNullByDefault
public interface ArkivoPasswordProvider {
    /// Returns a provider that never supplies a password.
    static ArkivoPasswordProvider none() {
        return () -> null;
    }

    /// Returns a provider that supplies a defensive copy of the given password bytes.
    static ArkivoPasswordProvider fixed(byte[] password) {
        byte[] storedPassword = password.clone();
        return () -> storedPassword.clone();
    }

    /// Returns a provider that encodes the given password characters with the given charset.
    static ArkivoPasswordProvider fixed(char[] password, Charset charset) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(charset, "charset");
        ByteBuffer buffer = charset.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return fixed(bytes);
    }

    /// Returns caller-owned archive-level password bytes, or `null` when no password is available.
    ///
    /// Callers may clear the returned array after use. Implementations that retain credentials must return a defensive
    /// copy.
    byte @Nullable [] passwordForArchive() throws IOException;

    /// Returns caller-owned entry-level password bytes, or `null` when no password is available.
    ///
    /// Callers may clear the returned array after use. Implementations that retain credentials must return a defensive
    /// copy.
    default byte @Nullable [] passwordForEntry(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return passwordForArchive();
    }
}
