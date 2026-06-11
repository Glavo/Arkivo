// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/// Implements WinZip AES encryption and decryption for ZIP entries.
@NotNullByDefault
final class ZipAesCrypto {
    /// The PBKDF2 iteration count used by WinZip AES.
    private static final int PBKDF2_ITERATIONS = 1000;

    /// The HMAC-SHA1 output length in bytes.
    private static final int HMAC_SHA1_SIZE = 20;

    /// The HMAC-SHA1 block size in bytes.
    private static final int HMAC_SHA1_BLOCK_SIZE = 64;

    /// The AES block size in bytes.
    private static final int AES_BLOCK_SIZE = 16;

    /// The random source used to create salts for encrypted entries.
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Creates no instances.
    private ZipAesCrypto() {
    }

    /// Opens a stream that decrypts one WinZip AES entry body and verifies its authentication code.
    static InputStream openDecryptingStream(
            InputStream input,
            ZipAesExtraField aes,
            byte[] password,
            long compressedSize
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aes, "aes");
        Objects.requireNonNull(password, "password");
        if (compressedSize < aes.overheadSize()) {
            throw new IOException("WinZip AES entry is missing encryption overhead");
        }
        Decryptor decryptor = openDecryptor(input, aes, password);
        return new DecryptingInputStream(
                input,
                decryptor,
                compressedSize - aes.overheadSize(),
                aes.authenticationCodeSize()
        );
    }

    /// Opens a WinZip AES decryptor after consuming and validating salt and password verifier bytes.
    static Decryptor openDecryptor(
            InputStream input,
            ZipAesExtraField aes,
            byte[] password
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aes, "aes");
        Objects.requireNonNull(password, "password");

        byte[] salt = readBytes(input, aes.saltSize(), "Unexpected end of WinZip AES salt");
        byte[] passwordVerifier = readBytes(
                input,
                aes.passwordVerifierSize(),
                "Unexpected end of WinZip AES password verifier"
        );
        return new Decryptor(createDecryptingState(aes, password, salt, passwordVerifier));
    }

    /// Returns the current failure with the given exception added as a suppressed failure when needed.
    private static Throwable mergeFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure != null) {
            failure.addSuppressed(exception);
            return failure;
        }
        return exception;
    }

    /// Throws the given failure while preserving its original checked or unchecked type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Opens a stream that encrypts one WinZip AES entry body and appends its authentication code.
    static EncryptingOutputStream openEncryptingStream(
            OutputStream output,
            ZipAesExtraField aes,
            byte[] password
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(aes, "aes");
        Objects.requireNonNull(password, "password");

        byte[] salt = new byte[aes.saltSize()];
        RANDOM.nextBytes(salt);
        byte[] passwordVerifier = new byte[aes.passwordVerifierSize()];
        State state = createEncryptingState(aes, password, salt, passwordVerifier);
        output.write(salt);
        output.write(passwordVerifier);
        return new EncryptingOutputStream(output, state);
    }

    /// Creates a decryption state after validating the password verifier.
    private static State createDecryptingState(
            ZipAesExtraField aes,
            byte[] password,
            byte[] salt,
            byte[] passwordVerifier
    ) throws IOException {
        int keySize = aes.keySize();
        byte[] derivedKey = deriveKey(password, salt, keySize * 2 + passwordVerifier.length);
        if (!matches(derivedKey, keySize * 2, passwordVerifier)) {
            throw new IOException("ZIP password verification failed");
        }

        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derivedKey, 0, keySize, "AES"));

            Mac authentication = Mac.getInstance("HmacSHA1");
            authentication.init(new SecretKeySpec(derivedKey, keySize, keySize, "HmacSHA1"));
            return new State(cipher, authentication);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to initialize WinZip AES decryption", exception);
        }
    }

    /// Creates an encryption state and copies the derived password verifier into the output buffer.
    private static State createEncryptingState(
            ZipAesExtraField aes,
            byte[] password,
            byte[] salt,
            byte[] passwordVerifierOutput
    ) throws IOException {
        int keySize = aes.keySize();
        byte[] derivedKey = deriveKey(password, salt, keySize * 2 + passwordVerifierOutput.length);
        System.arraycopy(derivedKey, keySize * 2, passwordVerifierOutput, 0, passwordVerifierOutput.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derivedKey, 0, keySize, "AES"));

            Mac authentication = Mac.getInstance("HmacSHA1");
            authentication.init(new SecretKeySpec(derivedKey, keySize, keySize, "HmacSHA1"));
            return new State(cipher, authentication);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to initialize WinZip AES encryption", exception);
        }
    }

    /// Derives WinZip AES key material with PBKDF2-HMAC-SHA1.
    private static byte[] deriveKey(byte[] password, byte[] salt, int length) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            byte[] hmacKey = password.length == 0 ? new byte[HMAC_SHA1_BLOCK_SIZE] : password;
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA1"));

            byte[] derivedKey = new byte[length];
            byte[] blockIndex = new byte[4];
            int generated = 0;
            int block = 1;
            while (generated < length) {
                blockIndex[0] = (byte) (block >>> 24);
                blockIndex[1] = (byte) (block >>> 16);
                blockIndex[2] = (byte) (block >>> 8);
                blockIndex[3] = (byte) block;

                mac.update(salt);
                byte[] value = mac.doFinal(blockIndex);
                byte[] blockValue = value.clone();
                for (int iteration = 1; iteration < PBKDF2_ITERATIONS; iteration++) {
                    value = mac.doFinal(value);
                    for (int index = 0; index < blockValue.length; index++) {
                        blockValue[index] ^= value[index];
                    }
                }

                int copyLength = Math.min(blockValue.length, length - generated);
                System.arraycopy(blockValue, 0, derivedKey, generated, copyLength);
                generated += copyLength;
                block++;
            }
            return derivedKey;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to derive WinZip AES key", exception);
        }
    }

    /// Returns whether derived key bytes match a password verifier.
    private static boolean matches(byte[] derivedKey, int offset, byte[] passwordVerifier) {
        int diff = 0;
        for (int index = 0; index < passwordVerifier.length; index++) {
            diff |= derivedKey[offset + index] ^ passwordVerifier[index];
        }
        return diff == 0;
    }

    /// Reads exactly `length` bytes from a stream.
    private static byte[] readBytes(InputStream input, int length, String eofMessage) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            offset += read;
        }
        return bytes;
    }

    /// Stores mutable WinZip AES transform state.
    @NotNullByDefault
    private static final class State {
        /// The AES block cipher used to generate CTR keystream blocks.
        private final Cipher cipher;

        /// The HMAC-SHA1 authentication state over encrypted content bytes.
        private final Mac authentication;

        /// The little-endian WinZip AES nonce block.
        private final byte[] nonceBlock = new byte[AES_BLOCK_SIZE];

        /// The current keystream block.
        private final byte[] keyStream = new byte[AES_BLOCK_SIZE];

        /// The next keystream byte offset.
        private int keyStreamOffset = AES_BLOCK_SIZE;

        /// The next WinZip AES nonce value.
        private int nonce = 1;

        /// Creates a mutable transform state.
        private State(Cipher cipher, Mac authentication) {
            this.cipher = Objects.requireNonNull(cipher, "cipher");
            this.authentication = Objects.requireNonNull(authentication, "authentication");
        }

        /// Adds encrypted bytes to the authentication state.
        private void authenticate(byte[] bytes, int offset, int length) {
            authentication.update(bytes, offset, length);
        }

        /// Applies the AES CTR transform to bytes in place.
        private void transform(byte[] bytes, int offset, int length) throws IOException {
            for (int index = 0; index < length; index++) {
                if (keyStreamOffset == AES_BLOCK_SIZE) {
                    refillKeyStream();
                }
                int byteIndex = offset + index;
                bytes[byteIndex] = (byte) (bytes[byteIndex] ^ keyStream[keyStreamOffset]);
                keyStreamOffset++;
            }
        }

        /// Verifies the stored authentication code.
        private boolean verify(byte[] expectedAuthentication) {
            byte[] authenticationCode = authentication.doFinal();
            return MessageDigest.isEqual(
                    Arrays.copyOf(authenticationCode, expectedAuthentication.length),
                    expectedAuthentication
            );
        }

        /// Returns whether a copied authentication state can match after additional encrypted bytes.
        private boolean authenticationCanMatchAfter(
                byte[] additionalEncryptedContent,
                byte[] expectedAuthentication
        ) throws IOException {
            Mac authenticationSnapshot;
            try {
                authenticationSnapshot = (Mac) authentication.clone();
            } catch (CloneNotSupportedException exception) {
                return true;
            }

            authenticationSnapshot.update(additionalEncryptedContent, 0, additionalEncryptedContent.length);
            byte[] authenticationCode = authenticationSnapshot.doFinal();
            return MessageDigest.isEqual(
                    Arrays.copyOf(authenticationCode, expectedAuthentication.length),
                    expectedAuthentication
            );
        }

        /// Returns the completed authentication code.
        private byte[] authenticationCode() {
            return authentication.doFinal();
        }

        /// Refills the current keystream block.
        private void refillKeyStream() throws IOException {
            nonceBlock[0] = (byte) nonce;
            nonceBlock[1] = (byte) (nonce >>> 8);
            nonceBlock[2] = (byte) (nonce >>> 16);
            nonceBlock[3] = (byte) (nonce >>> 24);
            Arrays.fill(nonceBlock, 4, nonceBlock.length, (byte) 0);
            nonce++;

            try {
                int written = cipher.update(nonceBlock, 0, nonceBlock.length, keyStream, 0);
                if (written != AES_BLOCK_SIZE) {
                    throw new IOException("Invalid WinZip AES keystream block length");
                }
            } catch (ShortBufferException exception) {
                throw new IOException("Failed to generate WinZip AES keystream", exception);
            }
            keyStreamOffset = 0;
        }
    }

    /// Decrypts WinZip AES bytes while updating authentication state.
    @NotNullByDefault
    static final class Decryptor {
        /// The mutable decryption state.
        private final State state;

        /// Creates a decryptor with initialized key state.
        private Decryptor(State state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Decrypts bytes in place and authenticates the encrypted input bytes.
        void decrypt(byte[] bytes, int offset, int length) throws IOException {
            state.authenticate(bytes, offset, length);
            state.transform(bytes, offset, length);
        }

        /// Verifies the stored authentication code bytes.
        boolean verify(byte[] expectedAuthentication) {
            return state.verify(expectedAuthentication);
        }

        /// Returns whether the current authentication state can match after additional encrypted bytes.
        boolean authenticationCanMatchAfter(
                byte[] additionalEncryptedContent,
                byte[] expectedAuthentication
        ) throws IOException {
            return state.authenticationCanMatchAfter(additionalEncryptedContent, expectedAuthentication);
        }
    }

    /// Encrypts WinZip AES entry content and writes the trailing authentication code.
    @NotNullByDefault
    static final class EncryptingOutputStream extends FilterOutputStream {
        /// The mutable encryption state.
        private final State state;

        /// Whether the authentication code has already been written.
        private boolean finished;

        /// The generated authentication code, or `null` until first finish attempt.
        private byte @Nullable [] authenticationCode;

        /// Whether this stream has been closed to further entry content writes.
        private boolean closed;

        /// Creates an encrypting stream over ZIP compressed content bytes.
        private EncryptingOutputStream(OutputStream output, State state) {
            super(Objects.requireNonNull(output, "output"));
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Writes one encrypted byte.
        @Override
        public void write(int value) throws IOException {
            byte[] buffer = new byte[]{(byte) value};
            write(buffer, 0, buffer.length);
        }

        /// Writes encrypted bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) {
                throw new IOException("WinZip AES output stream is finished");
            }
            byte[] encrypted = Arrays.copyOfRange(bytes, offset, offset + length);
            state.transform(encrypted, 0, encrypted.length);
            state.authenticate(encrypted, 0, encrypted.length);
            out.write(encrypted);
        }

        /// Writes the trailing authentication code.
        void finish() throws IOException {
            if (finished) {
                return;
            }
            closed = true;
            byte @Nullable [] code = authenticationCode;
            if (code == null) {
                code = state.authenticationCode();
                authenticationCode = code;
            }
            out.write(code, 0, ZipAesExtraField.AUTHENTICATION_CODE_SIZE);
            finished = true;
        }

        /// Finishes this stream without closing the archive output.
        @Override
        public void close() throws IOException {
            finish();
        }
    }

    /// Decrypts WinZip AES entry content and verifies the trailing authentication code.
    @NotNullByDefault
    private static final class DecryptingInputStream extends FilterInputStream {
        /// The decryptor used for this entry data.
        private final Decryptor decryptor;

        /// The remaining encrypted content bytes before the authentication code.
        private long remainingEncryptedSize;

        /// The authentication code size in bytes.
        private final int authenticationCodeSize;

        /// Whether the authentication code has already been verified.
        private boolean verified;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether the wrapped input stream has been closed.
        private boolean inputClosed;

        /// Creates a decrypting stream over encrypted content bytes.
        private DecryptingInputStream(
                InputStream input,
                Decryptor decryptor,
                long encryptedSize,
                int authenticationCodeSize
        ) {
            super(Objects.requireNonNull(input, "input"));
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            this.remainingEncryptedSize = encryptedSize;
            this.authenticationCodeSize = authenticationCodeSize;
        }

        /// Reads one decrypted byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads decrypted bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads decrypted bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remainingEncryptedSize == 0) {
                verifyAuthenticationCode();
                return -1;
            }

            int requested = (int) Math.min(length, remainingEncryptedSize);
            int read = in.read(bytes, offset, requested);
            if (read < 0) {
                throw new EOFException("Unexpected end of WinZip AES encrypted data");
            }
            remainingEncryptedSize -= read;
            decryptor.decrypt(bytes, offset, read);
            return read;
        }

        /// Closes the stream after draining content and verifying authentication.
        @Override
        public void close() throws IOException {
            if (closed && inputClosed) {
                return;
            }

            Throwable failure = null;
            if (!closed) {
                closed = true;
                try {
                    byte[] discard = new byte[8192];
                    while (readUnchecked(discard, 0, discard.length) >= 0) {
                        // Drain remaining encrypted content so the authentication code can be verified.
                    }
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }

            if (!inputClosed) {
                try {
                    in.close();
                    inputClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }
            throwFailure(failure);
        }

        /// Reads and verifies the trailing authentication code.
        private void verifyAuthenticationCode() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            byte[] expectedAuthentication = readBytes(
                    in,
                    authenticationCodeSize,
                    "Unexpected end of WinZip AES authentication code"
            );
            if (!decryptor.verify(expectedAuthentication)) {
                throw new IOException("WinZip AES authentication failed");
            }
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("WinZip AES input stream is closed");
            }
        }
    }
}
