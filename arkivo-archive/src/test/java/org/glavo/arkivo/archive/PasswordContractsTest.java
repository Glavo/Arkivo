// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies password request and encryption descriptor value contracts.
@NotNullByDefault
public final class PasswordContractsTest {
    /// The format identity retained by valid password requests.
    private static final ArkivoFormat FORMAT = () -> "test";

    /// Verifies password requests retain archive-wide and entry-specific context.
    @Test
    public void requestRetainsValidatedContext() {
        PasswordEncryption encryption = PasswordEncryption.of("aes", 256);
        PasswordRequest entryRequest = new PasswordRequest(
                FORMAT,
                PasswordPurpose.ENTRY_CONTENT,
                "/directory/entry.bin",
                encryption,
                3
        );

        assertSame(FORMAT, entryRequest.format());
        assertSame(PasswordPurpose.ENTRY_CONTENT, entryRequest.purpose());
        assertEquals("/directory/entry.bin", entryRequest.entryPath());
        assertSame(encryption, entryRequest.encryption());
        assertEquals(3, entryRequest.attempt());

        PasswordRequest archiveRequest = new PasswordRequest(
                FORMAT,
                PasswordPurpose.ARCHIVE,
                null,
                PasswordEncryption.of("archive"),
                0
        );
        assertNull(archiveRequest.entryPath());
    }

    /// Verifies requests reject relative entry paths and negative attempt numbers.
    @Test
    public void requestRejectsInvalidContext() {
        PasswordEncryption encryption = PasswordEncryption.of("test");

        assertThrows(
                NullPointerException.class,
                () -> new PasswordRequest(
                        null,
                        PasswordPurpose.ARCHIVE,
                        null,
                        encryption,
                        0
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new PasswordRequest(FORMAT, null, null, encryption, 0)
        );
        assertThrows(
                NullPointerException.class,
                () -> new PasswordRequest(FORMAT, PasswordPurpose.ARCHIVE, null, null, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PasswordRequest(
                        FORMAT,
                        PasswordPurpose.ENTRY_CONTENT,
                        "relative/path",
                        encryption,
                        0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PasswordRequest(
                        FORMAT,
                        PasswordPurpose.ARCHIVE,
                        null,
                        encryption,
                        -1
                )
        );
    }

    /// Verifies encryption descriptors expose known and unknown key lengths.
    @Test
    public void encryptionFactoriesRetainSchemeAndKeyLength() {
        PasswordEncryption unknown = PasswordEncryption.of("zip-crypto");
        assertEquals("zip-crypto", unknown.scheme());
        assertEquals(PasswordEncryption.UNKNOWN_KEY_LENGTH, unknown.keyLengthBits());

        PasswordEncryption known = PasswordEncryption.of("aes", 128);
        assertEquals("aes", known.scheme());
        assertEquals(128, known.keyLengthBits());
        assertEquals(new PasswordEncryption("aes", 128), known);
    }

    /// Verifies encryption descriptors reject blank schemes and invalid key lengths.
    @Test
    public void encryptionRejectsInvalidValues() {
        assertThrows(NullPointerException.class, () -> PasswordEncryption.of(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordEncryption.of(" "));
        assertThrows(IllegalArgumentException.class, () -> PasswordEncryption.of("aes", 0));
        assertThrows(IllegalArgumentException.class, () -> PasswordEncryption.of("aes", -2));
    }
}
