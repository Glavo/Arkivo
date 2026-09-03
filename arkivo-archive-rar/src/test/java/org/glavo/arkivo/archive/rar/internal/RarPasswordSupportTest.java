// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.PasswordEncryption;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.PasswordRequest;
import org.glavo.arkivo.archive.rar.RarArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies password requests for every supported RAR encryption generation and path form.
@NotNullByDefault
final class RarPasswordSupportTest {
    /// Verifies legacy entry requests include extraction-version-specific encryption metadata.
    @Test
    void createsLegacyEntryRequest() {
        PasswordRequest request = RarPasswordSupport.legacyEntry("directory/file.bin", 20);

        assertRequest(
                request,
                PasswordPurpose.ENTRY_CONTENT,
                "/directory/file.bin",
                "rar-legacy-20",
                PasswordEncryption.UNKNOWN_KEY_LENGTH
        );
    }

    /// Verifies RAR3 requests preserve absolute entry paths and advertise AES-128.
    @Test
    void createsRar3Request() {
        PasswordRequest request = RarPasswordSupport.rar3(PasswordPurpose.ENTRY_CONTENT, "/file.bin");

        assertRequest(request, PasswordPurpose.ENTRY_CONTENT, "/file.bin", "rar3-aes-128", 128);
    }

    /// Verifies archive-level RAR5 requests have no entry path and advertise AES-256.
    @Test
    void createsRar5ArchiveRequest() {
        PasswordRequest request = RarPasswordSupport.rar5(PasswordPurpose.ARCHIVE_METADATA, null);

        assertRequest(request, PasswordPurpose.ARCHIVE_METADATA, null, "rar5-aes-256", 256);
    }

    /// Verifies one password request's common format, purpose, path, encryption, and attempt fields.
    private static void assertRequest(
            PasswordRequest request,
            PasswordPurpose purpose,
            @Nullable String expectedPath,
            String scheme,
            int keyLengthBits
    ) {
        assertSame(RarArkivoFormat.instance(), request.format());
        assertSame(purpose, request.purpose());
        if (expectedPath == null) {
            assertNull(request.entryPath());
        } else {
            assertEquals(expectedPath, request.entryPath());
        }
        assertEquals(new PasswordEncryption(scheme, keyLengthBits), request.encryption());
        assertEquals(0, request.attempt());
    }
}
