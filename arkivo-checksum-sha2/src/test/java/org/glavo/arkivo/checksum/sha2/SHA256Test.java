// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.sha2;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.sha2.internal.MessageDigestChecksum;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies SHA-256 vectors, factory isolation, and one-shot input boundaries.
@NotNullByDefault
public final class SHA256Test {
    /// Verifies published vectors for empty and nonempty input.
    @Test
    public void staticComputationsMatchCheckValues() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SHA256.compute(new byte[0]).toHexString()
        );
        assertEquals(
                "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
                SHA256.compute("123456789".getBytes(StandardCharsets.US_ASCII)).toHexString()
        );
    }

    /// Verifies each factory call creates independent mutable state.
    @Test
    public void factoriesReturnIndependentComputations() {
        ChecksumAccumulator first = SHA256.newAccumulator();
        ChecksumAccumulator second = SHA256.newAccumulator();
        assertNotSame(first, second);
        assertEquals("SHA-256", first.name());
        assertEquals(32, first.checksumSize());
        first.update((byte) 'a');
        assertEquals(SHA256.compute(new byte[]{'a'}), first.finish());
        assertEquals(SHA256.compute(new byte[0]), second.finish());
    }

    /// Verifies array ranges and read-only direct buffers hash only the selected bytes.
    @Test
    public void staticComputationsRespectRanges() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(SHA256.compute(input), SHA256.compute(padded, 2, 9));
        ByteBuffer parent = ByteBuffer.allocateDirect(padded.length).put(padded).flip().position(2).limit(11);
        ByteBuffer source = parent.asReadOnlyBuffer();
        assertEquals(SHA256.compute(input), SHA256.compute(source));
        assertEquals(11, source.position());
        assertEquals(11, source.limit());
        assertEquals(2, parent.position());
        assertThrows(IndexOutOfBoundsException.class, () -> SHA256.compute(padded, -1, 1));
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
