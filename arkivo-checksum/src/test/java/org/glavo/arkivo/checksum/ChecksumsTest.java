// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
