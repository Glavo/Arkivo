// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies legacy and RAR5 bit readers at field, alignment, block, and input boundaries.
@NotNullByDefault
final class RarBitInputTest {
    /// Verifies RAR4 peeking and reads preserve most-significant-bit-first ordering across byte boundaries.
    @Test
    void readsLegacyBitFields() throws IOException {
        Rar4BitInput input = legacyInput(0xb2, 0x34, 0x56, 0x78, 0x9a);

        assertEquals(0, input.readBits(0));
        assertEquals(0b101, input.peekBits(3));
        assertEquals(0b101, input.peekBits(3));
        assertEquals(0b101, input.readBits(3));
        assertEquals(0b10010, input.readBits(5));
        assertEquals(0x3456_789a, input.peekBits(32));
        assertEquals(0x3456_789a, input.readBits(32));
        assertThrows(IOException.class, () -> input.readBits(1));
    }

    /// Verifies legacy skipping and alignment consume only the current byte remainder.
    @Test
    void skipsAndAlignsLegacyBits() throws IOException {
        Rar4BitInput input = legacyInput(0xeb, 0x7f);

        input.skipBits(0);
        input.skipBits(3);
        assertEquals(0b01, input.readBits(2));
        input.alignToByte();
        assertEquals(0x7f, input.readBits(8));
    }

    /// Verifies legacy bit-count and physical-end validation.
    @Test
    void validatesLegacyArgumentsAndEndOfInput() {
        assertThrows(NullPointerException.class, () -> new Rar4BitInput(null));
        Rar4BitInput input = legacyInput();

        assertThrows(IllegalArgumentException.class, () -> input.peekBits(-1));
        assertThrows(IllegalArgumentException.class, () -> input.peekBits(33));
        assertThrows(IllegalArgumentException.class, () -> input.skipBits(-1));
        assertThrows(IllegalArgumentException.class, () -> input.skipBits(33));
        assertThrows(IOException.class, () -> input.peekBits(1));
    }

    /// Verifies RAR5 positions and exact active-block boundaries while retaining prefetched bytes.
    @Test
    void enforcesRar5BlockBoundaries() throws IOException {
        Rar5BitInput input = rar5Input(0xab, 0xcd, 0x00);

        assertEquals(0L, input.positionBits());
        assertFalse(input.atBlockEnd());
        assertThrows(IOException.class, input::remainingBlockBits);
        input.setBlockEndBit(12L);
        assertEquals(0xa, input.peekBits(4));
        assertEquals(0L, input.positionBits());
        assertEquals(0xa, input.readBits(4));
        assertEquals(8L, input.remainingBlockBits());
        input.skipBits(4);
        assertEquals(0xc, input.readBits(4));
        assertTrue(input.atBlockEnd());
        assertEquals(0L, input.remainingBlockBits());
        assertThrows(IOException.class, () -> input.readBits(1));
        assertThrows(IOException.class, () -> input.setBlockEndBit(11L));

        input.clearBlockEnd();
        assertFalse(input.atBlockEnd());
        assertEquals(0xd, input.readBits(4));
        assertEquals(0, input.readAlignedByte());
        assertThrows(IOException.class, () -> input.readBits(1));
    }

    /// Verifies byte alignment accepts zero padding and rejects non-zero padding and unaligned headers.
    @Test
    void validatesRar5AlignmentPadding() throws IOException {
        Rar5BitInput zeroPadding = rar5Input(0xa0, 0x5a);
        assertEquals(0b101, zeroPadding.readBits(3));
        assertThrows(IOException.class, zeroPadding::readAlignedByte);
        zeroPadding.alignToByte();
        assertEquals(0x5a, zeroPadding.readAlignedByte());

        Rar5BitInput nonZeroPadding = rar5Input(0xa1);
        assertEquals(0b101, nonZeroPadding.readBits(3));
        assertThrows(IOException.class, nonZeroPadding::alignToByte);
    }

    /// Verifies RAR5 bit-count, block-start, and physical-end validation.
    @Test
    void validatesRar5ArgumentsAndEndOfInput() throws IOException {
        assertThrows(NullPointerException.class, () -> new Rar5BitInput(null));
        Rar5BitInput input = rar5Input();

        assertEquals(0, input.readBits(0));
        input.skipBits(0);
        input.alignToByte();
        assertThrows(IllegalArgumentException.class, () -> input.peekBits(-1));
        assertThrows(IllegalArgumentException.class, () -> input.peekBits(33));
        assertThrows(IllegalArgumentException.class, () -> input.skipBits(-1));
        assertThrows(IllegalArgumentException.class, () -> input.skipBits(33));
        assertThrows(IOException.class, () -> input.setBlockEndBit(-1L));
        input.setBlockEndBit(1L);
        assertThrows(IOException.class, () -> input.peekBits(2));
        assertThrows(IOException.class, () -> input.peekBits(1));
    }

    /// Creates a legacy bit input containing the supplied unsigned byte values.
    private static Rar4BitInput legacyInput(int... values) {
        return new Rar4BitInput(new ByteArrayInputStream(bytes(values)));
    }

    /// Creates a RAR5 bit input containing the supplied unsigned byte values.
    private static Rar5BitInput rar5Input(int... values) {
        return new Rar5BitInput(new ByteArrayInputStream(bytes(values)));
    }

    /// Narrows unsigned integer values to a byte array.
    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
