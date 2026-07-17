// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the closed ZIP encryption method set and its stable external identifiers.
@NotNullByDefault
public final class ZipEncryptionTest {
    /// Verifies that every encryption method round-trips through its case-insensitive stable identifier.
    @Test
    public void stableIdentifiersRoundTrip() {
        for (ZipEncryption encryption : ZipEncryption.values()) {
            assertSame(encryption, ZipEncryption.parse(encryption.id().toUpperCase(Locale.ROOT)));
            assertEquals(encryption.id(), encryption.toString());
        }
    }

    /// Verifies that legacy and unrecognized encryption identifiers are rejected.
    @Test
    public void unrecognizedIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ZipEncryption.parse("traditional"));
        assertThrows(IllegalArgumentException.class, () -> ZipEncryption.parse("vendor-extension"));
    }
}
