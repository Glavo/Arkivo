// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Holds a temporary decoded password while a 7z AES writer is initialized.
@NotNullByDefault
final class SevenZipWritePassword implements AutoCloseable {
    /// The temporary UTF-16LE password bytes, or `null` when encryption is disabled.
    private final byte @Nullable [] bytes;

    /// The decoded password characters, or `null` when encryption is disabled.
    private final char @Nullable [] characters;

    /// Whether this temporary password has been cleared.
    private boolean closed;

    /// Creates a temporary decoded password.
    private SevenZipWritePassword(byte @Nullable [] bytes, char @Nullable [] characters) {
        this.bytes = bytes;
        this.characters = characters;
    }

    /// Obtains and strictly decodes the archive password, or disables encryption when the provider is absent.
    static SevenZipWritePassword open(@Nullable ArkivoPasswordProvider passwordProvider) throws IOException {
        if (passwordProvider == null) {
            return new SevenZipWritePassword(null, null);
        }
        byte @Nullable [] password = passwordProvider.passwordForArchive();
        if (password == null) {
            throw new IOException("7z encrypted archive write requires a password");
        }
        try {
            return new SevenZipWritePassword(password, decodeUtf16Le(password));
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

    /// Returns the temporary characters for immediate writer initialization.
    char @Nullable [] characters() {
        if (closed) {
            throw new IllegalStateException("7z write password has been cleared");
        }
        return characters;
    }

    /// Clears the temporary decoded characters.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (characters != null) {
            Arrays.fill(characters, '\0');
        }
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
        closed = true;
    }

    /// Strictly decodes 7z password bytes as UTF-16LE characters.
    private static char[] decodeUtf16Le(byte[] password) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_16LE.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(password));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("7z write password must contain valid UTF-16LE bytes", exception);
        }
    }
}
