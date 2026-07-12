// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Holds temporary validated UTF-16LE password bytes while a 7z AES writer is initialized.
@NotNullByDefault
final class SevenZipWritePassword implements AutoCloseable {
    /// The temporary UTF-16LE password bytes, or `null` when encryption is disabled.
    private final byte @Nullable [] bytes;

    /// Whether this temporary password has been cleared.
    private boolean closed;

    /// Creates a temporary validated password.
    private SevenZipWritePassword(byte @Nullable [] bytes) {
        this.bytes = bytes;
    }

    /// Obtains and strictly validates the archive password, or disables encryption when the provider is absent.
    static SevenZipWritePassword open(@Nullable ArkivoPasswordProvider passwordProvider) throws IOException {
        if (passwordProvider == null) {
            return new SevenZipWritePassword(null);
        }
        byte @Nullable [] password = passwordProvider.passwordForArchive();
        if (password == null) {
            throw new IOException("7z encrypted archive write requires a password");
        }
        try {
            validateUtf16Le(password);
            return new SevenZipWritePassword(password);
        } catch (IOException | RuntimeException | Error exception) {
            Arrays.fill(password, (byte) 0);
            throw exception;
        }
    }

    /// Returns the temporary UTF-16LE bytes for immediate header-key derivation.
    byte @Nullable [] bytes() {
        if (closed) {
            throw new IllegalStateException("7z write password has been cleared");
        }
        return bytes;
    }

    /// Clears the temporary password bytes.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
        closed = true;
    }

    /// Strictly validates 7z password bytes as UTF-16LE text.
    private static void validateUtf16Le(byte[] password) throws IOException {
        try {
            StandardCharsets.UTF_16LE.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(password));
        } catch (CharacterCodingException exception) {
            throw new IOException("7z write password must contain valid UTF-16LE bytes", exception);
        }
    }
}
