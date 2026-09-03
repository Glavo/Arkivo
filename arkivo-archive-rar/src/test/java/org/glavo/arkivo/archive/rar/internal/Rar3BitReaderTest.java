// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bounded in-memory RAR3 descriptor bit and encoded-integer reads.
@NotNullByDefault
final class Rar3BitReaderTest {
    /// Verifies reads are most-significant-bit first, bounded, and isolated from source-array mutation.
    @Test
    void readsBoundedBitFieldsFromSnapshot() throws IOException {
        byte[] bytes = {(byte) 0xb2, 0x34, 0x56, 0x78, (byte) 0x9a};
        Rar3BitReader reader = new Rar3BitReader(bytes);
        bytes[0] = 0;

        assertEquals(40, reader.remainingBits());
        assertEquals(0, reader.readBits(0));
        assertEquals(0b101, reader.readBits(3));
        assertEquals(37, reader.remainingBits());
        assertEquals(0b10010, reader.readBits(5));
        assertEquals(0x3456_789a, reader.readBits(32));
        assertEquals(0, reader.remainingBits());
        assertThrows(EOFException.class, () -> reader.readBits(1));
    }

    /// Verifies all four encoded-uint selectors, including the selector-one sign-extension form.
    @Test
    void readsEveryEncodedUint32Form() throws IOException {
        assertEquals(0x0aL, encodedUint("00 1010"));
        assertEquals(0xffff_ff80L, encodedUint("01 0000 10000000"));
        assertEquals(0xa5L, encodedUint("01 1010 0101"));
        assertEquals(0x1234L, encodedUint("10 0001001000110100"));
        assertEquals(0x89ab_cdefL, encodedUint("11 10001001101010111100110111101111"));
    }

    /// Verifies unaligned byte reads retain byte values and consume the exact requested range.
    @Test
    void readsUnalignedBytes() throws IOException {
        Rar3BitReader reader = new Rar3BitReader(bits("101 00000001 11111110"));

        assertEquals(0b101, reader.readBits(3));
        assertArrayEquals(new byte[]{1, (byte) 0xfe}, reader.readBytes(2));
        assertEquals(5, reader.remainingBits());
        assertThrows(EOFException.class, () -> reader.readBytes(1));
    }

    /// Verifies constructor offsets, bit counts, and byte counts reject invalid ranges.
    @Test
    void validatesArgumentsAndOffsets() throws IOException {
        assertThrows(NullPointerException.class, () -> new Rar3BitReader(null));
        assertThrows(IllegalArgumentException.class, () -> new Rar3BitReader(new byte[1], -1));
        assertThrows(IllegalArgumentException.class, () -> new Rar3BitReader(new byte[1], 2));

        Rar3BitReader offsetReader = new Rar3BitReader(new byte[]{0x11, 0x22}, 1);
        assertEquals(8, offsetReader.remainingBits());
        assertEquals(0x22, offsetReader.readBits(8));

        Rar3BitReader reader = new Rar3BitReader(new byte[1]);
        assertThrows(IllegalArgumentException.class, () -> reader.readBits(-1));
        assertThrows(IllegalArgumentException.class, () -> reader.readBits(33));
        assertThrows(IllegalArgumentException.class, () -> reader.readBytes(-1));
    }

    /// Reads one encoded unsigned integer from a textual bit sequence.
    private static long encodedUint(String bits) throws IOException {
        return new Rar3BitReader(bits(bits)).readEncodedUint32();
    }

    /// Packs a whitespace-separated textual bit sequence with zero padding in the final byte.
    private static byte[] bits(String text) {
        String bits = text.replace(" ", "");
        byte[] result = new byte[(bits.length() + 7) >>> 3];
        for (int index = 0; index < bits.length(); index++) {
            char bit = bits.charAt(index);
            if (bit != '0' && bit != '1') {
                throw new IllegalArgumentException("Unexpected bit character: " + bit);
            }
            if (bit == '1') {
                result[index >>> 3] |= (byte) (1 << (7 - (index & 7)));
            }
        }
        return result;
    }
}
