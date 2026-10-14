// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.MessageDigestChecksum;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies built-in checksum algorithms against canonical published vectors.
@NotNullByDefault
public final class ChecksumsTest {
    /// The standard CRC check input.
    private static final byte @Unmodifiable [] CHECK_INPUT =
            "123456789".getBytes(StandardCharsets.US_ASCII);

    /// Verifies built-in CRC, Adler, and digest algorithms.
    @Test
    public void staticComputationsMatchCheckValues() {
        assertEquals(0x091e_01deL, Integer.toUnsignedLong(Checksums.adler32(CHECK_INPUT)));
        assertEquals(0xcbf4_3926L, Integer.toUnsignedLong(Checksums.crc32(CHECK_INPUT)));
        assertEquals(0xe306_9283L, Integer.toUnsignedLong(Checksums.crc32c(CHECK_INPUT)));
        assertEquals(
                "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
                Checksums.sha256(CHECK_INPUT).toHexString()
        );
    }

    /// Verifies built-in metadata, exact widths, and accumulator ownership.
    @Test
    public void factoriesReturnIndependentComputations() {
        assertEquals("Adler-32", Checksums.adler32().name());
        assertEquals(Integer.SIZE, Checksums.adler32().bitSize());
        assertEquals(Integer.BYTES, Checksums.adler32().checksumSize());
        assertNotSame(Checksums.adler32(), Checksums.adler32());

        assertEquals("CRC-32/ISO-HDLC", Checksums.crc32().name());
        assertEquals("CRC-32C", Checksums.crc32c().name());

        assertEquals("SHA-256", Checksums.sha256().name());
        assertEquals(32, Checksums.sha256().checksumSize());
        assertNotSame(Checksums.sha256(), Checksums.sha256());

        ChecksumAccumulator sha256 = Checksums.sha256();
        sha256.update((byte) 'a');
        assertEquals(Checksums.sha256(new byte[]{'a'}), sha256.finish());
    }

    /// Static array-range and buffer overloads hash only the selected input.
    @Test
    public void staticComputationsRespectRanges() {
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x091e_01de, Checksums.adler32(padded, 2, 9));
        assertEquals(0xcbf4_3926, Checksums.crc32(padded, 2, 9));
        assertEquals(0xe306_9283, Checksums.crc32c(padded, 2, 9));
        assertEquals(Checksums.sha256(CHECK_INPUT), Checksums.sha256(padded, 2, 9));
        ByteBuffer source = ByteBuffer.allocateDirect(padded.length).put(padded).flip().position(2).limit(11);
        assertEquals(0x091e_01de, Checksums.adler32(source));
        assertEquals(11, source.position());
        source.position(2);
        assertEquals(0xcbf4_3926, Checksums.crc32(source));
        source.position(2);
        assertEquals(0xe306_9283, Checksums.crc32c(source));
        source.position(2);
        assertEquals(Checksums.sha256(CHECK_INPUT), Checksums.sha256(source.asReadOnlyBuffer()));
        assertThrows(IndexOutOfBoundsException.class, () -> Checksums.crc32(padded, -1, 1));
    }

    /// Verifies message-digest adapters reject invalid names and result widths during construction.
    @Test
    public void messageDigestConfigurationIsValidated() {
        assertThrows(
                NullPointerException.class,
                () -> new MessageDigestChecksum(null, 32)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessageDigestChecksum("SHA-256", 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessageDigestChecksum("SHA-256", 31)
        );
        assertThrows(
                AssertionError.class,
                () -> new MessageDigestChecksum("ARKIVO-MISSING-DIGEST", 32)
        );
    }
}
