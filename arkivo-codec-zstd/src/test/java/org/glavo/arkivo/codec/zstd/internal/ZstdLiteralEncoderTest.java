// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies literal-section representation selection and entropy boundaries directly.
@NotNullByDefault
public final class ZstdLiteralEncoderTest {
    /// Verifies a depth-13 unrestricted tree is limited and described through compressed weights.
    @Test
    public void limitsDeepHighByteHuffmanTree() throws IOException {
        byte[] literals = deepTreeFixture();
        byte[] section = ZstdLiteralEncoder.encode(literals);

        assertEquals(2, section[0] & 3);
        int sizeFormat = Byte.toUnsignedInt(section[0]) >>> 2 & 3;
        int headerSize = sizeFormat <= 1 ? 3 : sizeFormat == 2 ? 4 : 5;
        assertTrue(Byte.toUnsignedInt(section[headerSize]) < 128);

        byte[] block = new byte[section.length + 1];
        System.arraycopy(section, 0, block, 0, section.length);
        byte[] decoded = new ZstdBlockDecoder(1 << 17, ZstdDictionary.NONE).decodeCompressed(block);
        assertArrayEquals(literals, decoded);
    }

    /// Creates frequencies 1, 1, 2, 4, ..., 4096 across symbols including 255.
    private static byte[] deepTreeFixture() {
        byte[] literals = new byte[8_192];
        int offset = 0;
        for (int index = 0; index < 14; index++) {
            int frequency = index < 2 ? 1 : 1 << (index - 1);
            byte symbol = index == 13 ? (byte) 255 : (byte) index;
            for (int count = 0; count < frequency; count++) {
                literals[offset++] = symbol;
            }
        }
        if (offset != literals.length) {
            throw new AssertionError("Invalid deep Huffman fixture size");
        }

        Random random = new Random(0x11b0_0ded_2026L);
        for (int index = literals.length - 1; index > 0; index--) {
            int selected = random.nextInt(index + 1);
            byte value = literals[index];
            literals[index] = literals[selected];
            literals[selected] = value;
        }
        return literals;
    }
}
