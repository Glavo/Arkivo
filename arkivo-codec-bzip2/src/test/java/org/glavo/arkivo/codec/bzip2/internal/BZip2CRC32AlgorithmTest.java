// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the format-local BZip2 block CRC implementation.
@NotNullByDefault
public final class BZip2CRC32AlgorithmTest {
    /// Verifies the canonical CRC-32/BZIP2 check value.
    @Test
    public void matchesCanonicalCheckValue() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xfc89_1918L, BZip2CRC32Algorithm.INSTANCE.computeLong(input));
    }
}
