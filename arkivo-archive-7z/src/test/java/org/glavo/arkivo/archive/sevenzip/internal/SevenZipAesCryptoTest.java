// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.PasswordRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies 7z AES property parsing, key derivation, decryption, and sensitive-buffer cleanup.
@NotNullByDefault
final class SevenZipAesCryptoTest {
    /// The password request supplied to direct cryptographic tests.
    private static final PasswordRequest PASSWORD_REQUEST =
            SevenZipPasswordSupport.request(PasswordPurpose.ENTRY_CONTENT, "/entry.bin");

    /// Verifies SHA-256 derivation against fixed vectors before and after the counter's low byte wraps.
    @Test
    void derivesKnownSha256KeysAcrossCounterCarry() throws IOException {
        byte[] salt = {0x00, 0x01, (byte) 0xfe, (byte) 0xff};
        byte[] password = {0x70, 0x00, 0x61, 0x00, 0x73, 0x00, 0x73, 0x00};

        assertArrayEquals(
                HexFormat.of().parseHex("dc20b935d75d71127a4c7b06fab71c99c3d8dead905fd864635afc92899c7d58"),
                SevenZipAesCrypto.deriveKey(0, salt, password)
        );
        assertArrayEquals(
                HexFormat.of().parseHex("6c4621cca2c134320e7ef350f554c3b2d8960b1dc1de885fdbc43538d2253eee"),
                SevenZipAesCrypto.deriveKey(8, salt, password)
        );
        assertArrayEquals(new byte[]{0x00, 0x01, (byte) 0xfe, (byte) 0xff}, salt);
        assertArrayEquals(new byte[]{0x70, 0x00, 0x61, 0x00, 0x73, 0x00, 0x73, 0x00}, password);
    }

    /// Verifies direct-copy derivation truncates at 256 bits and rejects unsupported parameters.
    @Test
    void derivesCopyModeKeyAndValidatesParameters() throws IOException {
        byte[] salt = {1, 2, 3};
        byte[] password = new byte[40];
        for (int index = 0; index < password.length; index++) {
            password[index] = (byte) (index + 4);
        }
        byte[] expected = new byte[32];
        System.arraycopy(salt, 0, expected, 0, salt.length);
        System.arraycopy(password, 0, expected, salt.length, expected.length - salt.length);

        assertArrayEquals(expected, SevenZipAesCrypto.deriveKey(0x3f, salt, password));
        assertThrows(IOException.class, () -> SevenZipAesCrypto.deriveKey(-1, salt, password));
        assertThrows(IOException.class, () -> SevenZipAesCrypto.deriveKey(25, salt, password));
        assertThrows(
                IOException.class,
                () -> SevenZipAesCrypto.deriveKey(0, new byte[17], password)
        );
    }

    /// Verifies the empty property form decrypts correctly and the supplied password buffer is erased eagerly.
    @Test
    void decryptsEmptyPropertyFormAndClearsPassword() throws IOException, GeneralSecurityException {
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] password = "pass".getBytes(StandardCharsets.UTF_16LE);
        byte[] key = SevenZipAesCrypto.deriveKey(0, new byte[0], password);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(new byte[16])
            );
            ciphertext = cipher.doFinal(plaintext);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        byte[] suppliedPassword = password.clone();
        try (InputStream input = SevenZipAesCrypto.openDecryptingStream(
                new ByteArrayInputStream(ciphertext),
                new byte[0],
                request -> suppliedPassword,
                PASSWORD_REQUEST
        )) {
            assertArrayEquals(new byte[suppliedPassword.length], suppliedPassword);
            assertArrayEquals(plaintext, input.readAllBytes());
        }
    }

    /// Verifies malformed coder properties and absent passwords fail before producing plaintext.
    @Test
    void rejectsMalformedPropertiesAndMissingPasswords() {
        List<byte[]> malformedProperties = List.of(
                new byte[]{25},
                new byte[]{0, 0},
                new byte[]{(byte) 0x80},
                new byte[]{(byte) 0xc0, 0}
        );
        for (byte[] properties : malformedProperties) {
            assertThrows(
                    IOException.class,
                    () -> SevenZipAesCrypto.openDecryptingStream(
                            InputStream.nullInputStream(),
                            properties,
                            request -> {
                                throw new AssertionError("Password provider must not run for malformed properties");
                            },
                            PASSWORD_REQUEST
                    )
            );
        }

        IOException failure = assertThrows(
                IOException.class,
                () -> SevenZipAesCrypto.openDecryptingStream(
                        InputStream.nullInputStream(),
                        new byte[]{0},
                        null,
                        PASSWORD_REQUEST
                )
        );
        assertEquals("7z AES encrypted data requires a password", failure.getMessage());
    }
}
