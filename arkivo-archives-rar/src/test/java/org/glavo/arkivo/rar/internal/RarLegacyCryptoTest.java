// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests legacy RAR encryption-version dispatch.
@NotNullByDefault
public final class RarLegacyCryptoTest {
    /// Recognizes only the extraction versions selected by the official UnRAR dispatch table.
    @Test
    public void recognizesOfficialLegacyExtractionVersions() {
        assertEquals(true, RarLegacyCrypto.supports(13));
        assertEquals(true, RarLegacyCrypto.supports(15));
        assertEquals(true, RarLegacyCrypto.supports(20));
        assertEquals(true, RarLegacyCrypto.supports(26));

        assertEquals(false, RarLegacyCrypto.supports(19));
        assertEquals(false, RarLegacyCrypto.supports(21));
        assertEquals(false, RarLegacyCrypto.supports(25));
        assertEquals(false, RarLegacyCrypto.supports(27));
    }

    /// Selects block processing only for the two RAR 2.x extraction versions.
    @Test
    public void recognizesRar20BlockCipherVersions() {
        assertEquals(false, RarLegacyCrypto.usesBlockCipher(13));
        assertEquals(false, RarLegacyCrypto.usesBlockCipher(15));
        assertEquals(true, RarLegacyCrypto.usesBlockCipher(20));
        assertEquals(true, RarLegacyCrypto.usesBlockCipher(26));
        assertEquals(false, RarLegacyCrypto.usesBlockCipher(25));
        assertEquals(false, RarLegacyCrypto.usesBlockCipher(29));
    }
}
