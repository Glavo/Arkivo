// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests Arkivo password providers.
@NotNullByDefault
public final class ArkivoPasswordProviderTest {
    /// A representative password request used to exercise context-independent fixed providers.
    private static final PasswordRequest TEST_REQUEST = new PasswordRequest(
            () -> "test",
            PasswordPurpose.ENTRY_CONTENT,
            "/entry.txt",
            PasswordEncryption.of("test"),
            0
    );

    /// Verifies that fixed byte passwords are defensively copied.
    @Test
    public void fixedBytesAreCopied() throws Exception {
        byte[] password = new byte[]{1, 2, 3};
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(password);

        password[0] = 9;
        byte[] firstCopy = Objects.requireNonNull(provider.password(TEST_REQUEST));
        byte[] secondCopy = Objects.requireNonNull(provider.password(TEST_REQUEST));
        firstCopy[1] = 9;

        assertNotSame(firstCopy, secondCopy);
        assertArrayEquals(new byte[]{1, 2, 3}, secondCopy);
    }

    /// Verifies character passwords are encoded eagerly and returned through fresh arrays.
    @Test
    public void fixedCharactersUseCharsetAndSnapshotInput() throws Exception {
        char[] password = new char[]{'A', '\u20ac'};
        byte[] expected = "A\u20ac".getBytes(StandardCharsets.UTF_16LE);
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(password, StandardCharsets.UTF_16LE);

        Arrays.fill(password, 'x');
        byte[] firstCopy = Objects.requireNonNull(provider.password(TEST_REQUEST));
        byte[] secondCopy = Objects.requireNonNull(provider.password(TEST_REQUEST));
        Arrays.fill(firstCopy, (byte) 0);

        assertNotSame(firstCopy, secondCopy);
        assertArrayEquals(expected, secondCopy);
    }

    /// Verifies that fixed providers accept entry password request context.
    @Test
    public void fixedProviderAcceptsEntryRequest() throws Exception {
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, provider.password(TEST_REQUEST));
    }

    /// Verifies the absent provider consistently reports no credential.
    @Test
    public void absentProviderReturnsNull() throws Exception {
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.none();

        assertNull(provider.password(TEST_REQUEST));
        assertNull(provider.password(new PasswordRequest(
                TEST_REQUEST.format(),
                PasswordPurpose.ARCHIVE_METADATA,
                null,
                PasswordEncryption.of("headers", 256),
                2
        )));
    }

    /// Verifies an empty fixed password remains present and is copied for every request.
    @Test
    public void emptyFixedPasswordRemainsDistinctFromAbsence() throws Exception {
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(new byte[0]);

        byte[] first = Objects.requireNonNull(provider.password(TEST_REQUEST));
        byte[] second = Objects.requireNonNull(provider.password(TEST_REQUEST));
        assertEquals(0, first.length);
        assertEquals(0, second.length);
        assertNotSame(first, second);
    }

    /// Verifies fixed providers reject missing password material or character encoding.
    @Test
    public void fixedProviderRejectsNullConfiguration() {
        assertThrows(NullPointerException.class, () -> ArkivoPasswordProvider.fixed((byte[]) null));
        assertThrows(
                NullPointerException.class,
                () -> ArkivoPasswordProvider.fixed((char[]) null, StandardCharsets.UTF_8)
        );
        assertThrows(
                NullPointerException.class,
                () -> ArkivoPasswordProvider.fixed(new char[0], null)
        );
    }
}
