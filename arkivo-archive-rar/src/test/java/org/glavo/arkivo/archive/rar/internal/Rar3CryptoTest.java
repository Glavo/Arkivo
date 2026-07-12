// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests RAR 3.x AES key derivation compatibility.
@NotNullByDefault
public final class Rar3CryptoTest {
    /// Verifies a normal salted key derivation vector.
    @Test
    public void derivesShortPasswordVector() throws IOException {
        byte[] password = "test".getBytes(StandardCharsets.UTF_16LE);
        byte[] salt = HexFormat.of().parseHex("0011223344556677");
        try (Rar3Crypto.DerivedKeys keys = Rar3Crypto.deriveKeys(password, salt)) {
            assertArrayEquals(HexFormat.of().parseHex("0b11fd0bca1d527678598f8abbd17f48"), keys.key());
            assertArrayEquals(
                    HexFormat.of().parseHex("b1c5560270bdebdefe2dea14dc647b37"),
                    keys.initializationVector()
            );
        }
    }

    /// Verifies the historical SHA-1 workspace mutation for passwords spanning direct compression blocks.
    @Test
    public void derivesLongPasswordCompatibilityVector() throws IOException {
        byte[] password = "abcdefghijklmnopqrstuvwxyz0123456789ABCD".getBytes(StandardCharsets.UTF_16LE);
        byte[] salt = HexFormat.of().parseHex("0001020304050607");
        try (Rar3Crypto.DerivedKeys keys = Rar3Crypto.deriveKeys(password, salt)) {
            assertArrayEquals(HexFormat.of().parseHex("8ccbf2afa8656c0c5d91e7d152da5b7d"), keys.key());
            assertArrayEquals(
                    HexFormat.of().parseHex("8f5e987b17813a2a42d01ceee4b6bb0b"),
                    keys.initializationVector()
            );
        }
    }

    /// Verifies strict password and salt byte boundaries.
    @Test
    public void rejectsInvalidDerivationInputs() {
        assertInvalidDerivation(new byte[1], new byte[Rar3Crypto.SALT_SIZE]);
        assertInvalidDerivation(new byte[256], new byte[Rar3Crypto.SALT_SIZE]);
        assertInvalidDerivation(new byte[0], new byte[7]);
    }

    /// Requires key derivation to reject the supplied password and salt bytes.
    private static void assertInvalidDerivation(byte[] password, byte[] salt) {
        assertThrows(IOException.class, () -> {
            try (Rar3Crypto.DerivedKeys ignored = Rar3Crypto.deriveKeys(password, salt)) {
                throw new AssertionError("Invalid RAR3 derivation input was accepted");
            }
        });
    }
}
