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
public final class StandardChecksumTest {
    /// The standard CRC check input.
    private static final byte @Unmodifiable [] CHECK_INPUT =
            "123456789".getBytes(StandardCharsets.US_ASCII);

    /// Verifies built-in CRC, Adler, and digest algorithms.
    @Test
    public void staticComputationsMatchCheckValues() {
        assertEquals(0x091e_01deL, Integer.toUnsignedLong(Adler32.computeInt(CHECK_INPUT)));
        assertEquals(0xcbf4_3926L, Integer.toUnsignedLong(CRC32.computeInt(CHECK_INPUT)));
        assertEquals(0xe306_9283L, Integer.toUnsignedLong(CRC32C.computeInt(CHECK_INPUT)));
        assertEquals(
                "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
                SHA256.compute(CHECK_INPUT).toHexString()
        );
    }

    /// Verifies built-in metadata, exact widths, and accumulator ownership.
    @Test
    public void factoriesReturnIndependentComputations() {
        assertEquals("Adler-32", Adler32.newAccumulator().name());
        assertEquals(Integer.SIZE, Adler32.newAccumulator().bitSize());
        assertEquals(Integer.BYTES, Adler32.newAccumulator().checksumSize());
        assertNotSame(Adler32.newAccumulator(), Adler32.newAccumulator());

        assertEquals("CRC-32/ISO-HDLC", CRC32.newAccumulator().name());
        assertEquals("CRC-32C", CRC32C.newAccumulator().name());
        assertNotSame(CRC32.newAccumulator(), CRC32.newAccumulator());
        assertNotSame(CRC32C.newAccumulator(), CRC32C.newAccumulator());

        assertEquals("SHA-256", SHA256.newAccumulator().name());
        assertEquals(32, SHA256.newAccumulator().checksumSize());
        assertNotSame(SHA256.newAccumulator(), SHA256.newAccumulator());

        ChecksumAccumulator sha256 = SHA256.newAccumulator();
        sha256.update((byte) 'a');
        assertEquals(SHA256.compute(new byte[]{'a'}), sha256.finish());
    }

    /// Static array-range and buffer overloads hash only the selected input.
    @Test
    public void staticComputationsRespectRanges() {
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x091e_01de, Adler32.computeInt(padded, 2, 9));
        assertEquals(0xcbf4_3926, CRC32.computeInt(padded, 2, 9));
        assertEquals(0xe306_9283, CRC32C.computeInt(padded, 2, 9));
        assertEquals(SHA256.compute(CHECK_INPUT), SHA256.compute(padded, 2, 9));
        ByteBuffer source = ByteBuffer.allocateDirect(padded.length).put(padded).flip().position(2).limit(11);
        assertEquals(0x091e_01de, Adler32.computeInt(source));
        assertEquals(11, source.position());
        source.position(2);
        assertEquals(0xcbf4_3926, CRC32.computeInt(source));
        source.position(2);
        assertEquals(0xe306_9283, CRC32C.computeInt(source));
        source.position(2);
        assertEquals(SHA256.compute(CHECK_INPUT), SHA256.compute(source.asReadOnlyBuffer()));
        assertThrows(IndexOutOfBoundsException.class, () -> CRC32.computeInt(padded, -1, 1));
    }

    /// Verifies the published empty-input result of each static computation.
    @Test
    public void staticComputationsMatchEmptyValues() {
        byte[] empty = new byte[0];
        assertEquals(1, Adler32.computeInt(empty));
        assertEquals(0, CRC32.computeInt(empty));
        assertEquals(0, CRC32C.computeInt(empty));
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SHA256.compute(empty).toHexString()
        );
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
