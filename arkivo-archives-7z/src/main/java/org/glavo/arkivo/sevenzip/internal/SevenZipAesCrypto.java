// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

/// Implements the 7z AES-256/SHA-256 decryption filter.
@NotNullByDefault
final class SevenZipAesCrypto {
    /// The AES key size used by the 7zAES method.
    private static final int KEY_SIZE = 32;

    /// The AES block size used by the 7zAES method.
    private static final int AES_BLOCK_SIZE = 16;

    /// The largest SHA-256 cycle power supported by the 7-Zip reference implementation.
    private static final int MAX_SUPPORTED_CYCLE_POWER = 24;

    /// The special cycle power that stores salt and password bytes directly in the AES key.
    private static final int COPY_KEY_CYCLE_POWER = 0x3f;

    /// Creates no instances.
    private SevenZipAesCrypto() {
    }

    /// Opens an AES/CBC decrypting stream for a 7zAES coder.
    static InputStream openDecryptingStream(
            InputStream input,
            byte[] properties,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        AesProperties parsedProperties = AesProperties.parse(properties);
        byte @Nullable [] password = passwordProvider != null ? passwordProvider.passwordForArchive() : null;
        if (password == null) {
            throw new IOException("7z AES encrypted data requires a password");
        }

        byte[] key = deriveKey(parsedProperties, password);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(parsedProperties.initializationVector())
            );
            return new CipherInputStream(input, cipher);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to initialize 7z AES decryption", exception);
        }
    }

    /// Derives a 7zAES key from parsed coder properties and password bytes.
    private static byte[] deriveKey(AesProperties properties, byte[] password) throws IOException {
        if (properties.cyclePower() == COPY_KEY_CYCLE_POWER) {
            byte[] key = new byte[KEY_SIZE];
            byte[] salt = properties.salt();
            int position = Math.min(salt.length, key.length);
            System.arraycopy(salt, 0, key, 0, position);
            int passwordLength = Math.min(password.length, key.length - position);
            System.arraycopy(password, 0, key, position, passwordLength);
            return key;
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] salt = properties.salt();
            byte[] counter = new byte[Long.BYTES];
            long rounds = 1L << properties.cyclePower();
            for (long round = 0; round < rounds; round++) {
                sha256.update(salt);
                sha256.update(password);
                sha256.update(counter);
                incrementLittleEndianCounter(counter);
            }
            return sha256.digest();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to derive 7z AES key", exception);
        }
    }

    /// Increments an eight-byte little-endian counter.
    private static void incrementLittleEndianCounter(byte[] counter) {
        for (int index = 0; index < counter.length; index++) {
            counter[index]++;
            if (counter[index] != 0) {
                return;
            }
        }
    }

    /// Stores parsed 7zAES coder properties.
    ///
    /// @param cyclePower the SHA-256 cycle power
    /// @param salt the salt bytes used by key derivation
    /// @param initializationVector the full AES initialization vector
    @NotNullByDefault
    private record AesProperties(
            int cyclePower,
            byte @Unmodifiable [] salt,
            byte @Unmodifiable [] initializationVector
    ) {
        /// Creates parsed immutable AES properties.
        private AesProperties {
            Objects.requireNonNull(salt, "salt");
            Objects.requireNonNull(initializationVector, "initializationVector");
            if (cyclePower < 0) {
                throw new IllegalArgumentException("cyclePower must not be negative");
            }
            if (salt.length > AES_BLOCK_SIZE) {
                throw new IllegalArgumentException("salt must fit in one AES block");
            }
            if (initializationVector.length != AES_BLOCK_SIZE) {
                throw new IllegalArgumentException("initializationVector must contain one AES block");
            }
            salt = salt.clone();
            initializationVector = initializationVector.clone();
        }

        /// Parses 7zAES coder properties.
        private static AesProperties parse(byte[] properties) throws IOException {
            Objects.requireNonNull(properties, "properties");
            if (properties.length == 0) {
                return new AesProperties(0, new byte[0], new byte[AES_BLOCK_SIZE]);
            }

            int first = Byte.toUnsignedInt(properties[0]);
            int cyclePower = first & 0x3f;
            if (cyclePower > MAX_SUPPORTED_CYCLE_POWER && cyclePower != COPY_KEY_CYCLE_POWER) {
                throw new IOException("Unsupported 7z AES cycle power: " + cyclePower);
            }
            if ((first & 0xc0) == 0) {
                if (properties.length != 1) {
                    throw new IOException("Invalid 7z AES coder properties");
                }
                return new AesProperties(cyclePower, new byte[0], new byte[AES_BLOCK_SIZE]);
            }
            if (properties.length <= 1) {
                throw new IOException("Invalid 7z AES coder properties");
            }

            int second = Byte.toUnsignedInt(properties[1]);
            int saltSize = ((first >>> 7) & 1) + (second >>> 4);
            int ivSize = ((first >>> 6) & 1) + (second & 0x0f);
            if (properties.length != 2 + saltSize + ivSize) {
                throw new IOException("Invalid 7z AES coder properties");
            }

            byte[] salt = new byte[saltSize];
            System.arraycopy(properties, 2, salt, 0, salt.length);
            byte[] initializationVector = new byte[AES_BLOCK_SIZE];
            System.arraycopy(properties, 2 + salt.length, initializationVector, 0, ivSize);
            return new AesProperties(cyclePower, salt, initializationVector);
        }

        /// Returns a defensive copy of the salt bytes.
        @Override
        public byte[] salt() {
            return salt.clone();
        }

        /// Returns a defensive copy of the AES initialization vector.
        @Override
        public byte[] initializationVector() {
            return initializationVector.clone();
        }
    }
}
