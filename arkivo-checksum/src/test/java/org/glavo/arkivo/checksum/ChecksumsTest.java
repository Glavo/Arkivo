// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies built-in checksum algorithms against canonical published vectors.
@NotNullByDefault
public final class ChecksumsTest {
    /// The standard CRC catalogue check input.
    private static final byte[] CHECK_INPUT = "123456789".getBytes(StandardCharsets.US_ASCII);

    /// Verifies catalogue CRC and Adler variants.
    @Test
    public void catalogueAlgorithmsMatchCheckValues() {
        assertEquals(0x091e_01deL, Checksums.ADLER32.computeLong(CHECK_INPUT));
        assertEquals(0xcbf4_3926L, Checksums.CRC32.computeLong(CHECK_INPUT));
        assertEquals(0xe306_9283L, Checksums.CRC32C.computeLong(CHECK_INPUT));
        assertEquals(0xfc89_1918L, Checksums.CRC32_BZIP2.computeLong(CHECK_INPUT));
        assertEquals(0x995d_c9bb_df19_39faL, Checksums.CRC64_XZ.computeLong(CHECK_INPUT));
        assertEquals(
                "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
                Checksums.SHA256.compute(CHECK_INPUT).toHexString()
        );
    }

    /// Verifies zero-seeded XXH32 and XXH64 vectors.
    @Test
    public void xxHashAlgorithmsMatchKnownVectors() {
        assertEquals(0x02cc_5d05L, Checksums.XXH32.computeLong(new byte[0]));
        assertEquals(
                0x32d1_53ffL,
                Checksums.XXH32.computeLong("abc".getBytes(StandardCharsets.US_ASCII))
        );
        assertEquals(0xef46_db37_51d8_e999L, Checksums.XXH64.computeLong(new byte[0]));
        assertEquals(
                0x44bc_2cf5_ad77_0999L,
                Checksums.XXH64.computeLong("abc".getBytes(StandardCharsets.US_ASCII))
        );
    }

    /// Verifies fragmented seeded updates and reset reuse.
    @Test
    public void seededXXHashConfigurationsCreateReusableIndependentState() {
        byte[] input = "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
        XXHash32 algorithm = new XXHash32(0x9747_b28c);
        ChecksumAccumulator.Width32 accumulator = algorithm.newAccumulator();
        for (int offset = 0; offset < input.length; offset += 7) {
            accumulator.update(input, offset, Math.min(7, input.length - offset));
        }
        assertEquals(algorithm.computeLong(input), accumulator.finishLong());

        accumulator.reset();
        ByteBuffer direct = ByteBuffer.allocateDirect(input.length + 4);
        direct.position(2).put(input).limit(input.length + 2).position(2);
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        accumulator.update(readOnly);
        assertEquals(readOnly.limit(), readOnly.position());
        assertEquals(algorithm.computeLong(input), accumulator.finishLong());

        assertSame(algorithm, algorithm.withSeed(algorithm.seed()));
        assertEquals(algorithm, new XXHash32(algorithm.seed()));
    }
}
