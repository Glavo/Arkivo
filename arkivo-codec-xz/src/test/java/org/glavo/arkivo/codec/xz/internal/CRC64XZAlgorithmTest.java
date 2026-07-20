// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the format-local XZ CRC-64 implementation.
@NotNullByDefault
public final class CRC64XZAlgorithmTest {
    /// Verifies the canonical CRC-64/XZ check value.
    @Test
    public void matchesCanonicalCheckValue() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x995d_c9bb_df19_39faL, CRC64XZAlgorithm.INSTANCE.computeLong(input));
    }
}
