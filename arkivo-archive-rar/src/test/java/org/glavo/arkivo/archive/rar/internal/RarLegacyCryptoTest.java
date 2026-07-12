// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests legacy RAR encryption-version dispatch.
@NotNullByDefault
public final class RarLegacyCryptoTest {
    /// Recognizes only extraction versions that define one of the three legacy cipher families.
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

    /// Ignores password bytes following the first zero terminator for every legacy family.
    @Test
    public void normalizesZeroTerminatedPasswords() throws IOException {
        for (int version : new int[]{13, 15, 20}) {
            int length = RarLegacyCrypto.usesBlockCipher(version) ? 16 : 19;
            byte[] first = sequentialBytes(length);
            byte[] second = first.clone();
            try (RarLegacyCrypto.Decryptor firstDecryptor =
                         RarLegacyCrypto.decryptor(version, new byte[]{1, 2, 0, 3});
                 RarLegacyCrypto.Decryptor secondDecryptor =
                         RarLegacyCrypto.decryptor(version, new byte[]{1, 2})) {
                firstDecryptor.decrypt(first, 0, first.length);
                secondDecryptor.decrypt(second, 0, second.length);
            }
            assertArrayEquals(first, second);
        }
    }

    /// Produces identical evolving state when ciphertext arrives in multiple processing calls.
    @Test
    public void preservesStateAcrossProcessingCalls() throws IOException {
        byte[] password = {4, 3, 2, 1};
        for (int version : new int[]{13, 15, 20}) {
            int unit = RarLegacyCrypto.usesBlockCipher(version) ? 16 : 7;
            byte[] combined = sequentialBytes(unit * 2);
            byte[] split = combined.clone();
            try (RarLegacyCrypto.Decryptor combinedDecryptor = RarLegacyCrypto.decryptor(version, password);
                 RarLegacyCrypto.Decryptor splitDecryptor = RarLegacyCrypto.decryptor(version, password)) {
                combinedDecryptor.decrypt(combined, 0, combined.length);
                splitDecryptor.decrypt(split, 0, unit);
                splitDecryptor.decrypt(split, unit, unit);
            }
            assertArrayEquals(combined, split);
        }
    }

    /// Rejects passwords beyond the legacy 127-byte key-schedule limit.
    @Test
    public void rejectsOversizedPassword() {
        byte[] password = new byte[128];
        Arrays.fill(password, (byte) 1);
        assertThrows(IOException.class, () -> RarLegacyCrypto.decryptor(20, password));
    }

    /// Rejects decryption after password-derived state has been cleared.
    @Test
    public void rejectsUseAfterClose() throws IOException {
        RarLegacyCrypto.Decryptor decryptor = RarLegacyCrypto.decryptor(15, new byte[]{1});
        decryptor.close();
        assertThrows(IllegalStateException.class, () -> decryptor.decrypt(new byte[1], 0, 1));
    }

    /// Returns deterministic nonuniform bytes for cipher lifecycle tests.
    private static byte[] sequentialBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (index * 17 + 3);
        return bytes;
    }
}
