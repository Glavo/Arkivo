// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests temporary 7z write-password validation and cleanup.
@NotNullByDefault
public final class SevenZipWritePasswordTest {
    /// Verifies that an absent provider disables encryption.
    @Test
    public void absentProviderDisablesEncryption() throws IOException {
        SevenZipWritePassword password = SevenZipWritePassword.open(null);

        assertNull(password.bytes());
        password.close();
        assertThrows(IllegalStateException.class, password::bytes);
    }

    /// Verifies strict UTF-16LE validation and deterministic byte cleanup.
    @Test
    public void decodesAndClearsPassword() throws IOException {
        String text = "p\u00e4ss-\u5bc6\u7801";
        byte[] suppliedBytes = text.getBytes(StandardCharsets.UTF_16LE);
        SevenZipWritePassword password = SevenZipWritePassword.open(() -> suppliedBytes);
        byte[] bytes = Objects.requireNonNull(password.bytes());

        assertArrayEquals(suppliedBytes, bytes);
        password.close();
        assertArrayEquals(new byte[bytes.length], bytes);
        password.close();
    }

    /// Verifies that an empty password remains distinct from missing password data.
    @Test
    public void acceptsEmptyPassword() throws IOException {
        SevenZipWritePassword password = SevenZipWritePassword.open(ArkivoPasswordProvider.fixed(new byte[0]));
        byte[] bytes = Objects.requireNonNull(password.bytes());

        assertArrayEquals(new byte[0], bytes);
        password.close();
    }

    /// Verifies that a configured provider must actually supply a password.
    @Test
    public void rejectsMissingProviderResult() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipWritePassword.open(ArkivoPasswordProvider.none())
        );

        assertEquals("7z encrypted archive write requires a password", exception.getMessage());
    }

    /// Verifies that malformed and truncated UTF-16LE byte sequences are rejected.
    @Test
    public void rejectsInvalidUtf16Le() {
        byte[] oddLength = new byte[]{1};
        byte[] truncatedSurrogate = new byte[]{0, (byte) 0xd8};
        assertThrows(
                IOException.class,
                () -> SevenZipWritePassword.open(() -> oddLength)
        );
        assertThrows(
                IOException.class,
                () -> SevenZipWritePassword.open(() -> truncatedSurrogate)
        );
        assertArrayEquals(new byte[oddLength.length], oddLength);
        assertArrayEquals(new byte[truncatedSurrogate.length], truncatedSurrogate);
    }
}
