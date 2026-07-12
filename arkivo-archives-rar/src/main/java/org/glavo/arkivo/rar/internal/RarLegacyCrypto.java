// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Selects the native legacy RAR file-data cipher associated with an extraction version.
@NotNullByDefault
final class RarLegacyCrypto {
    /// The extraction version selecting the byte-oriented RAR 1.3 cipher.
    private static final int VERSION_13 = 13;
    /// The extraction version selecting the stream-oriented RAR 1.5 cipher.
    private static final int VERSION_15 = 15;
    /// The first extraction version selecting the RAR 2.x block cipher.
    private static final int VERSION_20 = 20;
    /// The later extraction version selecting the RAR 2.x block cipher.
    private static final int VERSION_26 = 26;
    /// The largest password prefix consumed by legacy RAR key schedules.
    private static final int MAX_PASSWORD_BYTES = 127;

    /// Prevents construction of this utility class.
    private RarLegacyCrypto() {
    }

    /// Returns whether an extraction version selects a supported legacy cipher.
    static boolean supports(int extractionVersion) {
        return extractionVersion == VERSION_13
                || extractionVersion == VERSION_15
                || extractionVersion == VERSION_20
                || extractionVersion == VERSION_26;
    }

    /// Returns whether an extraction version requires complete 16-byte ciphertext blocks.
    static boolean usesBlockCipher(int extractionVersion) {
        return extractionVersion == VERSION_20 || extractionVersion == VERSION_26;
    }

    /// Creates a stateful decryptor from a zero-terminated raw single-byte password.
    static Decryptor decryptor(int extractionVersion, byte[] password) throws IOException {
        Objects.requireNonNull(password, "password");
        if (!supports(extractionVersion)) {
            throw new IOException("Unsupported legacy RAR encryption version: " + extractionVersion);
        }
        int passwordLength = effectivePasswordLength(password);
        if (passwordLength > MAX_PASSWORD_BYTES) {
            throw new IOException("Legacy RAR password must contain at most 127 bytes");
        }
        byte[] normalizedPassword = Arrays.copyOf(password, passwordLength);
        try {
            RarLegacyCipher cipher = switch (extractionVersion) {
                case VERSION_13 -> new Rar13Cipher(normalizedPassword);
                case VERSION_15 -> new Rar15Cipher(normalizedPassword);
                case VERSION_20, VERSION_26 -> new Rar20Cipher(normalizedPassword);
                default -> throw new AssertionError("Unsupported legacy RAR extraction version");
            };
            return new Decryptor(cipher);
        } finally {
            Arrays.fill(normalizedPassword, (byte) 0);
        }
    }

    /// Returns the password prefix preceding its first zero terminator.
    private static int effectivePasswordLength(byte[] password) {
        int length = 0;
        while (length < password.length && password[length] != 0) length++;
        return length;
    }

    /// Owns one selected cipher and exposes the stream adapter's stable lifecycle contract.
    @NotNullByDefault
    static final class Decryptor implements AutoCloseable {
        /// The selected native cipher state.
        private final RarLegacyCipher cipher;
        /// Whether the cipher state has been cleared.
        private boolean closed;

        /// Wraps one initialized cipher state.
        private Decryptor(RarLegacyCipher cipher) {
            this.cipher = Objects.requireNonNull(cipher, "cipher");
        }

        /// Returns the selected cipher's processing-unit size.
        int blockSize() {
            return cipher.blockSize();
        }

        /// Decrypts bytes in place while preserving state for subsequent volume segments.
        void decrypt(byte[] data, int offset, int length) {
            requireOpen();
            Objects.checkFromIndexSize(offset, length, data.length);
            int blockSize = cipher.blockSize();
            if (length % blockSize != 0) {
                throw new IllegalArgumentException("Legacy RAR ciphertext must contain complete cipher blocks");
            }
            cipher.decrypt(data, offset, length);
        }

        /// Clears all password-derived and evolving cipher state.
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            cipher.clear();
        }

        /// Rejects operations after state destruction.
        private void requireOpen() {
            if (closed) throw new IllegalStateException("Legacy RAR decryptor has been cleared");
        }
    }
}
