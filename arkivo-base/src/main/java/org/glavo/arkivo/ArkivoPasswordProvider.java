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

    /// Returns the archive-level password.
    byte @Nullable [] passwordForArchive() throws IOException;

    /// Returns the entry-level password.
    default byte @Nullable [] passwordForEntry(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return passwordForArchive();
    }
}
