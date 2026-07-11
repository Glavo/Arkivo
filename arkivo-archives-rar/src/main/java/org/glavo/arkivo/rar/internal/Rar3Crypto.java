// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/// Implements RAR 3.x AES-128 key derivation and CBC block decryption.
@NotNullByDefault
final class Rar3Crypto {
    /// The AES block, key, and initialization vector size.
    static final int BLOCK_SIZE = 16;

    /// The optional RAR 3.x salt size.
    static final int SALT_SIZE = 8;

    /// The number of SHA-1 derivation rounds used by RAR 3.x.
    private static final int KDF_ROUNDS = 0x40000;

    /// The interval between initialization vector bytes produced by the KDF.
    private static final int IV_INTERVAL = KDF_ROUNDS / BLOCK_SIZE;

    /// The largest UTF-16LE password accepted by RAR, in bytes.
    private static final int MAX_PASSWORD_BYTES = 127 * Character.BYTES;

    /// The SHA-1 compression block size.
    private static final int SHA1_BLOCK_SIZE = 64;

    /// Prevents instantiation.
    private Rar3Crypto() {
    }

    /// Derives the RAR 3.x AES key and initialization vector from UTF-16LE password bytes.
    static DerivedKeys deriveKeys(byte[] password, byte @Nullable [] salt) throws IOException {
        if ((password.length & 1) != 0 || password.length > MAX_PASSWORD_BYTES) {
            throw new IOException("RAR3 password must be UTF-16LE and contain at most 127 characters");
        }
        if (salt != null && salt.length != SALT_SIZE) {
            throw new IOException("RAR3 encryption salt has invalid size");
        }

        byte[] seed = Arrays.copyOf(password, password.length + (salt != null ? salt.length : 0));
        if (salt != null) {
            System.arraycopy(salt, 0, seed, password.length, salt.length);
        }
        byte[] counter = new byte[3];
        byte[] key = new byte[BLOCK_SIZE];
        byte[] initializationVector = new byte[BLOCK_SIZE];
        byte @Nullable [] digest = null;
        int[] workspace = new int[16];
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long byteCount = 0L;
            for (int round = 0; round < KDF_ROUNDS; round++) {
                int bufferPosition = (int) (byteCount & (SHA1_BLOCK_SIZE - 1));
                sha1.update(seed);
                byteCount += seed.length;
                corruptSeedAfterSha1Update(seed, bufferPosition, workspace);

                counter[0] = (byte) round;
                counter[1] = (byte) (round >>> 8);
                counter[2] = (byte) (round >>> 16);
                sha1.update(counter);
                byteCount += counter.length;

                if (round % IV_INTERVAL == 0) {
                    byte[] snapshot = digestSnapshot(sha1);
                    initializationVector[round / IV_INTERVAL] = snapshot[snapshot.length - 1];
                    Arrays.fill(snapshot, (byte) 0);
                }
            }

            digest = sha1.digest();
            for (int word = 0; word < 4; word++) {
                int source = word * Integer.BYTES;
                key[source] = digest[source + 3];
                key[source + 1] = digest[source + 2];
                key[source + 2] = digest[source + 1];
                key[source + 3] = digest[source];
            }
            return new DerivedKeys(key, initializationVector);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR3 cryptographic primitives are unavailable", exception);
        } finally {
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(counter, (byte) 0);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(initializationVector, (byte) 0);
            Arrays.fill(workspace, 0);
            clear(digest);
        }
    }

    /// Returns a SHA-1 digest snapshot without consuming the active derivation state.
    private static byte[] digestSnapshot(MessageDigest sha1) throws IOException {
        try {
            return ((MessageDigest) sha1.clone()).digest();
        } catch (CloneNotSupportedException exception) {
            throw new IOException("SHA-1 provider cannot snapshot RAR3 key derivation state", exception);
        }
    }

    /// Reproduces the RAR 3.x SHA-1 input mutation applied to directly processed full blocks.
    private static void corruptSeedAfterSha1Update(byte[] seed, int bufferPosition, int[] workspace) {
        if (seed.length <= SHA1_BLOCK_SIZE) {
            return;
        }
        for (int offset = SHA1_BLOCK_SIZE - bufferPosition;
             offset + SHA1_BLOCK_SIZE <= seed.length;
             offset += SHA1_BLOCK_SIZE) {
            for (int index = 0; index < workspace.length; index++) {
                int position = offset + index * Integer.BYTES;
                workspace[index] = Byte.toUnsignedInt(seed[position]) << 24
                        | Byte.toUnsignedInt(seed[position + 1]) << 16
                        | Byte.toUnsignedInt(seed[position + 2]) << 8
                        | Byte.toUnsignedInt(seed[position + 3]);
            }
            for (int index = 16; index < 80; index++) {
                int value = workspace[(index - 3) & 15]
                        ^ workspace[(index - 8) & 15]
                        ^ workspace[(index - 14) & 15]
                        ^ workspace[(index - 16) & 15];
                workspace[index & 15] = Integer.rotateLeft(value, 1);
            }
            for (int index = 0; index < workspace.length; index++) {
                int position = offset + index * Integer.BYTES;
                int value = workspace[index];
                seed[position] = (byte) value;
                seed[position + 1] = (byte) (value >>> 8);
                seed[position + 2] = (byte) (value >>> 16);
                seed[position + 3] = (byte) (value >>> 24);
            }
        }
    }

    /// Creates a stateful RAR 3.x AES-CBC block decryptor.
    static CbcDecryptor decryptor(byte[] key, byte[] initializationVector) throws IOException {
        return new CbcDecryptor(key, initializationVector);
    }

    /// Clears a nullable sensitive byte array.
    static void clear(byte @Nullable [] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    /// Stores one RAR 3.x AES key and initialization vector.
    @NotNullByDefault
    static final class DerivedKeys implements AutoCloseable {
        /// The AES-128 key.
        private final byte[] key;

        /// The AES-CBC initialization vector.
        private final byte[] initializationVector;

        /// Whether the key material has been cleared.
        private boolean closed;

        /// Creates derived key material using defensive copies.
        private DerivedKeys(byte[] key, byte[] initializationVector) {
            this.key = key.clone();
            this.initializationVector = initializationVector.clone();
        }

        /// Returns a defensive copy of the AES key.
        byte[] key() {
            ensureOpen();
            return key.clone();
        }

        /// Returns a defensive copy of the AES-CBC initialization vector.
        byte[] initializationVector() {
            ensureOpen();
            return initializationVector.clone();
        }

        /// Clears all derived key material.
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(initializationVector, (byte) 0);
        }

        /// Requires this key material to remain available.
        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("RAR3 derived keys have been cleared");
            }
        }
    }

    /// Decrypts AES blocks while applying RAR 3.x CBC chaining.
    @NotNullByDefault
    static final class CbcDecryptor implements RarCbcDecryptor {
        /// The AES electronic-codebook primitive used for individual blocks.
        private final Cipher aes;

        /// The preceding ciphertext block or initialization vector.
        private final byte[] previousBlock;

        /// Whether mutable CBC state has been cleared.
        private boolean cleared;

        /// Creates one RAR 3.x AES-CBC decryptor.
        private CbcDecryptor(byte[] key, byte[] initializationVector) throws IOException {
            if (key.length != BLOCK_SIZE || initializationVector.length != BLOCK_SIZE) {
                throw new IOException("RAR3 AES key or initialization vector has invalid size");
            }
            try {
                aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } catch (GeneralSecurityException exception) {
                throw new IOException("AES-128 is unavailable for RAR3 decryption", exception);
            }
            previousBlock = initializationVector.clone();
        }

        /// Decrypts one complete ciphertext block into the target array.
        @Override
        public void decryptBlock(byte[] ciphertext, byte[] target) throws IOException {
            if (cleared) {
                throw new IOException("RAR3 AES decryptor has been cleared");
            }
            if (ciphertext.length != BLOCK_SIZE || target.length != BLOCK_SIZE) {
                throw new IOException("RAR3 AES data is not block aligned");
            }
            try {
                aes.doFinal(ciphertext, 0, ciphertext.length, target, 0);
            } catch (GeneralSecurityException exception) {
                throw new IOException("Could not decrypt RAR3 AES block", exception);
            }
            for (int index = 0; index < target.length; index++) {
                target[index] ^= previousBlock[index];
            }
            System.arraycopy(ciphertext, 0, previousBlock, 0, ciphertext.length);
        }

        /// Clears mutable CBC chaining state and prevents further decryption.
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
