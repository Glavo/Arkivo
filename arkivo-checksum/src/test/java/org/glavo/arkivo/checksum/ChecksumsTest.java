// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.MessageDigestChecksumAlgorithm;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies built-in checksum algorithms against canonical published vectors.
@NotNullByDefault
public final class ChecksumsTest {
    /// The standard CRC catalogue check input.
    private static final byte @Unmodifiable [] CHECK_INPUT =
            "123456789".getBytes(StandardCharsets.US_ASCII);

    /// Verifies catalogue CRC, Adler, and digest algorithms.
    @Test
    public void catalogueAlgorithmsMatchCheckValues() {
        assertEquals(0x091e_01deL, Checksums.ADLER32.computeLong(CHECK_INPUT));
        assertEquals(0xcbf4_3926L, Checksums.CRC32.computeLong(CHECK_INPUT));
        assertEquals(0xe306_9283L, Checksums.CRC32C.computeLong(CHECK_INPUT));
        assertEquals(
                "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
                Checksums.SHA256.compute(CHECK_INPUT).toHexString()
        );
    }

    /// Verifies catalogue metadata, exact widths, and accumulator ownership.
    @Test
    public void catalogueMetadataIsStable() {
        assertEquals("Adler-32", Checksums.ADLER32.name());
        assertEquals("Adler-32", Checksums.ADLER32.toString());
        assertEquals(Integer.SIZE, Checksums.ADLER32.bitSize());
        assertEquals(Integer.BYTES, Checksums.ADLER32.checksumSize());
        assertSame(Checksums.ADLER32, Checksums.ADLER32.newAccumulator().algorithm());

        assertEquals("CRC-32/ISO-HDLC", Checksums.CRC32.name());
        assertEquals("CRC-32/ISO-HDLC", Checksums.CRC32.toString());
        assertEquals("CRC-32C", Checksums.CRC32C.name());
        assertEquals("CRC-32C", Checksums.CRC32C.toString());

        assertEquals("SHA-256", Checksums.SHA256.name());
        assertEquals("SHA-256", Checksums.SHA256.toString());
        assertEquals(32, Checksums.SHA256.checksumSize());
        assertSame(Checksums.SHA256, Checksums.SHA256.newAccumulator().algorithm());

        ChecksumAccumulator sha256 = Checksums.SHA256.newAccumulator();
        sha256.update((byte) 'a');
        assertEquals(Checksums.SHA256.compute(new byte[]{'a'}), sha256.finish());
    }

    /// Verifies message-digest adapters reject invalid names and result widths during construction.
    @Test
    public void messageDigestConfigurationIsValidated() {
        assertThrows(
                NullPointerException.class,
                () -> new MessageDigestChecksumAlgorithm(null, 32)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessageDigestChecksumAlgorithm("SHA-256", 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessageDigestChecksumAlgorithm("SHA-256", 31)
        );
        assertThrows(
                AssertionError.class,
                () -> new MessageDigestChecksumAlgorithm("ARKIVO-MISSING-DIGEST", 32)
        );
    }
}
