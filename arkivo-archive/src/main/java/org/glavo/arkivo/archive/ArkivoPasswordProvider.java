// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/// Supplies passwords for encrypted archive data.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoPasswordProvider {
    /// Returns a provider that never supplies a password.
    static ArkivoPasswordProvider none() {
        return request -> null;
    }

    /// Returns a provider that retains a private copy of the given password bytes and supplies a fresh copy per request.
    static ArkivoPasswordProvider fixed(byte[] password) {
        byte[] storedPassword = Objects.requireNonNull(password, "password").clone();
        return request -> storedPassword.clone();
    }

    /// Returns a provider that encodes the given password characters with the given charset.
    static ArkivoPasswordProvider fixed(char[] password, Charset charset) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(charset, "charset");
        ByteBuffer buffer = charset.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            return fixed(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (buffer.hasArray()) {
                Arrays.fill(buffer.array(), (byte) 0);
            }
        }
    }

    /// Returns caller-owned password bytes for the given request, or `null` when no password is available.
    ///
    /// A non-null returned array is exclusively owned by the caller, which should clear it immediately after deriving
    /// the required encryption state. Implementations must return a fresh array and must neither retain nor reuse that
    /// array. The provider must not retain the request or any caller-owned data reachable from it.
    byte @Nullable [] password(PasswordRequest request) throws IOException;
}
