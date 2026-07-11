// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/// Implements the RAR5 password derivation, password checks, keyed checksums, and AES-CBC block decryption.
@NotNullByDefault
final class Rar5Crypto {
    /// The RAR5 AES block and initialization vector size.
    static final int BLOCK_SIZE = 16;

    /// The RAR5 salt size.
    static final int SALT_SIZE = 16;

    /// The password verification value size.
    static final int PASSWORD_CHECK_SIZE = 8;

    /// The checksum appended to a password verification value.
    static final int PASSWORD_CHECK_CHECKSUM_SIZE = 4;

    /// The largest PBKDF2 iteration exponent accepted by UnRAR.
    static final int MAX_KDF_LOG = 24;

    /// The SHA-256 digest size.
    private static final int SHA256_SIZE = 32;

    /// The HMAC-SHA256 block size.
    private static final int HMAC_BLOCK_SIZE = 64;

    /// The number of extra PBKDF2 rounds between each supplementary value.
    private static final int SUPPLEMENTARY_ROUNDS = 16;

    /// Prevents instantiation.
    private Rar5Crypto() {
    }

    /// Derives the AES key, keyed-checksum key, and password verification value for one RAR5 salt.
    static DerivedKeys deriveKeys(byte[] password, byte[] salt, int kdfLog) throws IOException {
        if (salt.length != SALT_SIZE) {
            throw new IOException("RAR5 encryption salt has invalid size");
        }
        if (kdfLog < 0 || kdfLog > MAX_KDF_LOG) {
            throw new IOException("Unsupported RAR5 KDF iteration exponent: " + kdfLog);
        }

        byte[] effectivePassword = password.length == 0 ? new byte[HMAC_BLOCK_SIZE] : password;
        byte[] saltBlock = Arrays.copyOf(salt, salt.length + Integer.BYTES);
        saltBlock[salt.length + 3] = 1;
        byte[] value = null;
        byte[] accumulated = null;
        byte[] aesKey = null;
        byte[] hashKey = null;
        byte[] passwordCheck = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(effectivePassword, "HmacSHA256"));

            value = mac.doFinal(saltBlock);
            accumulated = value.clone();
            int iterations = 1 << kdfLog;
            for (int iteration = 1; iteration < iterations; iteration++) {
                value = nextPbkdf2Value(mac, value, accumulated);
            }
            aesKey = accumulated.clone();

            for (int iteration = 0; iteration < SUPPLEMENTARY_ROUNDS; iteration++) {
                value = nextPbkdf2Value(mac, value, accumulated);
            }
            hashKey = accumulated.clone();

            for (int iteration = 0; iteration < SUPPLEMENTARY_ROUNDS; iteration++) {
                value = nextPbkdf2Value(mac, value, accumulated);
            }
            passwordCheck = new byte[PASSWORD_CHECK_SIZE];
            for (int index = 0; index < accumulated.length; index++) {
                passwordCheck[index % passwordCheck.length] ^= accumulated[index];
            }
            return new DerivedKeys(aesKey, hashKey, passwordCheck);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 cryptographic primitives are unavailable", exception);
        } finally {
            Arrays.fill(saltBlock, (byte) 0);
            if (effectivePassword != password) {
                Arrays.fill(effectivePassword, (byte) 0);
            }
            clear(value);
            clear(accumulated);
            clear(aesKey);
            clear(hashKey);
            clear(passwordCheck);
        }
    }

    /// Advances one PBKDF2 round and XORs it into the accumulated function value.
    private static byte[] nextPbkdf2Value(Mac mac, byte[] previous, byte[] accumulated) {
        byte[] next = mac.doFinal(previous);
        for (int index = 0; index < accumulated.length; index++) {
            accumulated[index] ^= next[index];
        }
        Arrays.fill(previous, (byte) 0);
        return next;
    }

    /// Returns whether the four-byte checksum authenticates the password verification value structure.
    static boolean hasValidPasswordCheckChecksum(byte[] passwordCheck, byte[] checksum) throws IOException {
        if (passwordCheck.length != PASSWORD_CHECK_SIZE || checksum.length != PASSWORD_CHECK_CHECKSUM_SIZE) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordCheck);
            byte[] expected = Arrays.copyOf(digest, PASSWORD_CHECK_CHECKSUM_SIZE);
            boolean matches = MessageDigest.isEqual(expected, checksum);
            Arrays.fill(digest, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            return matches;
        } catch (GeneralSecurityException exception) {
            throw new IOException("SHA-256 is unavailable for RAR5 password checks", exception);
        }
    }

    /// Converts a plain CRC32 value into the password-dependent RAR5 checksum representation.
    static long keyedCrc32(long crc32, byte[] hashKey) throws IOException {
        if (hashKey.length != SHA256_SIZE) {
            throw new IOException("RAR5 keyed-checksum key has invalid size");
        }
        byte[] rawCrc = new byte[]{
                (byte) crc32,
                (byte) (crc32 >>> 8),
                (byte) (crc32 >>> 16),
                (byte) (crc32 >>> 24)
        };
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawCrc);
            long result = 0L;
            for (int index = 0; index < digest.length; index++) {
                result ^= (long) Byte.toUnsignedInt(digest[index]) << ((index & 3) * Byte.SIZE);
            }
            Arrays.fill(digest, (byte) 0);
            return result & 0xffff_ffffL;
        } catch (GeneralSecurityException exception) {
            throw new IOException("HMAC-SHA256 is unavailable for RAR5 checksums", exception);
        } finally {
            Arrays.fill(rawCrc, (byte) 0);
        }
    }

    /// Creates a stateful RAR5 AES-CBC block decryptor.
    static CbcDecryptor decryptor(byte[] key, byte[] initializationVector) throws IOException {
        return new CbcDecryptor(key, initializationVector);
    }

    /// Clears a nullable sensitive byte array.
    static void clear(byte @Nullable [] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    /// Stores RAR5 values derived from one password and salt.
    @NotNullByDefault
    static final class DerivedKeys implements AutoCloseable {
        /// The AES-256 key.
        private final byte[] aesKey;

        /// The HMAC key used for tweaked file checksums.
        private final byte[] hashKey;

        /// The folded password verification value.
        private final byte[] passwordCheck;

        /// Whether the key material has been cleared.
        private boolean closed;

        /// Creates derived key material using defensive copies of all arrays.
        private DerivedKeys(byte[] aesKey, byte[] hashKey, byte[] passwordCheck) {
            this.aesKey = aesKey.clone();
            this.hashKey = hashKey.clone();
            this.passwordCheck = passwordCheck.clone();
        }

        /// Returns a defensive copy of the AES key.
        byte[] aesKey() {
            ensureOpen();
            return aesKey.clone();
        }

        /// Returns a defensive copy of the keyed-checksum key.
        byte[] hashKey() {
            ensureOpen();
            return hashKey.clone();
        }

        /// Returns whether this derivation matches the stored password verification value.
        boolean matchesPasswordCheck(byte[] expected) {
            ensureOpen();
            return expected.length == passwordCheck.length && MessageDigest.isEqual(passwordCheck, expected);
        }

        /// Clears all derived key material.
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(aesKey, (byte) 0);
            Arrays.fill(hashKey, (byte) 0);
            Arrays.fill(passwordCheck, (byte) 0);
        }

        /// Requires this key material to remain available.
        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("RAR5 derived keys have been cleared");
            }
        }
    }

    /// Decrypts independent AES blocks while applying RAR5 CBC chaining.
    @NotNullByDefault
    static final class CbcDecryptor implements RarCbcDecryptor {
        /// The AES electronic-codebook primitive used for one block at a time.
        private final Cipher aes;

        /// The preceding ciphertext block or initial vector.
        private final byte[] previousBlock;

        /// Whether the mutable CBC chaining state has been cleared.
        private boolean cleared;

        /// Creates one AES-CBC decryptor.
        private CbcDecryptor(byte[] key, byte[] initializationVector) throws IOException {
            if (key.length != SHA256_SIZE) {
                throw new IOException("RAR5 AES key has invalid size");
            }
            if (initializationVector.length != BLOCK_SIZE) {
                throw new IOException("RAR5 initialization vector has invalid size");
            }
            try {
                aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } catch (GeneralSecurityException exception) {
                throw new IOException("AES-256 is unavailable for RAR5 decryption", exception);
            }
            previousBlock = initializationVector.clone();
        }

        /// Decrypts one complete ciphertext block into the target array.
        @Override
        public void decryptBlock(byte[] ciphertext, byte[] target) throws IOException {
            if (cleared) {
                throw new IOException("RAR5 AES decryptor has been cleared");
            }
            if (ciphertext.length != BLOCK_SIZE || target.length != BLOCK_SIZE) {
                throw new IOException("RAR5 AES data is not block aligned");
            }
            try {
                aes.doFinal(ciphertext, 0, ciphertext.length, target, 0);
            } catch (GeneralSecurityException exception) {
                throw new IOException("Could not decrypt RAR5 AES block", exception);
            }
            for (int index = 0; index < target.length; index++) {
                target[index] ^= previousBlock[index];
            }
            System.arraycopy(ciphertext, 0, previousBlock, 0, ciphertext.length);
        }

        /// Clears the mutable CBC chaining state and prevents further decryption.
        @Override
        public void clear() {
            if (cleared) {
                return;
            }
            cleared = true;
            Arrays.fill(previousBlock, (byte) 0);
        }
    }
}
