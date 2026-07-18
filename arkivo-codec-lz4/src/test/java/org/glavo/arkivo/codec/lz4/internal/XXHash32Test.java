// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies xxHash-32 against published zero-seed test vectors.
@NotNullByDefault
public final class XXHash32Test {
    /// Verifies complete and fragmented hashing produce the canonical digests.
    @Test
    public void canonicalVectorsAndFragmentation() {
        assertHash(0x02cc_5d05L, "");
        assertHash(0x550d_7456L, "a");
        assertHash(0x32d1_53ffL, "abc");
        assertHash(0x7c94_8494L, "message digest");

        byte[] bytes = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.US_ASCII);
        XXHash32 fragmented = new XXHash32();
        for (int offset = 0; offset < bytes.length; offset += 3) {
            fragmented.update(bytes, offset, Math.min(3, bytes.length - offset));
        }
        assertEquals(XXHash32.hash(bytes), fragmented.value());
    }

    /// Verifies one ASCII string against an unsigned expected digest.
    private static void assertHash(long expected, String value) {
        assertEquals(expected, XXHash32.hash(value.getBytes(StandardCharsets.US_ASCII)));
    }
}
