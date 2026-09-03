// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies native RAR3 standard-filter identification, transforms, and parameter validation.
@NotNullByDefault
final class Rar3StandardFiltersTest {
    /// Verifies every standard-filter fingerprint is recognized at its prescribed bytecode length.
    @Test
    void identifiesStandardFilterFingerprints() {
        assertEquals(0, Rar3StandardFilters.identify(codeWithCrc32(53, 0xad57_6887L)));
        assertEquals(1, Rar3StandardFilters.identify(codeWithCrc32(57, 0x3cd7_e57eL)));
        assertEquals(2, Rar3StandardFilters.identify(codeWithCrc32(120, 0x3769_893fL)));
        assertEquals(3, Rar3StandardFilters.identify(codeWithCrc32(29, 0x0e06_077dL)));
        assertEquals(4, Rar3StandardFilters.identify(codeWithCrc32(149, 0x1c2c_5dc8L)));
        assertEquals(5, Rar3StandardFilters.identify(codeWithCrc32(216, 0xbc85_e701L)));
    }

    /// Verifies recognized bytecode lengths still require the exact standard-filter CRC32 fingerprint.
    @Test
    void rejectsUnknownFilterPrograms() {
        assertEquals(-1, Rar3StandardFilters.identify(new byte[0]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[53]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[57]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[120]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[29]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[149]));
        assertEquals(-1, Rar3StandardFilters.identify(new byte[216]));
    }

    /// Verifies x86 filters rewrite eligible E8/E9 addresses and preserve ineligible addresses and source bytes.
    @Test
    void appliesX86AddressFilters() throws IOException {
        int[] registers = new int[7];

        byte[] e8 = x86Instruction(0xe8, 5);
        byte[] e8Source = e8.clone();
        assertArrayEquals(x86Instruction(0xe8, 4), Rar3StandardFilters.apply(0, registers, e8, 0L));
        assertArrayEquals(e8Source, e8);

        byte[] e9 = x86Instruction(0xe9, 5);
        assertArrayEquals(e9, Rar3StandardFilters.apply(0, registers, e9, 0L));
        assertArrayEquals(x86Instruction(0xe9, 4), Rar3StandardFilters.apply(1, registers, e9, 0L));

        assertArrayEquals(
                x86Instruction(0xe8, 0x00ff_ffff),
                Rar3StandardFilters.apply(0, registers, x86Instruction(0xe8, -1), 0L)
        );
        assertArrayEquals(
                x86Instruction(0xe8, -2),
                Rar3StandardFilters.apply(0, registers, x86Instruction(0xe8, -2), 0L)
        );
        assertArrayEquals(
                x86Instruction(0xe8, 0x0100_0000),
                Rar3StandardFilters.apply(0, registers, x86Instruction(0xe8, 0x0100_0000), 0L)
        );
        assertArrayEquals(
                new byte[]{0x44, 0x33, 0x22, 0x11},
                Rar3StandardFilters.apply(0, registers, new byte[]{0x44, 0x33, 0x22, 0x11}, 0L)
        );
    }

    /// Verifies the Itanium filter rewrites a selected branch slot relative to the bundle offset.
    @Test
    void appliesItaniumBranchFilter() throws IOException {
        byte[] input = new byte[32];
        input[0] = 0x10;
        setLittleEndianBits(input, 100, 20, 100);
        setLittleEndianBits(input, 124, 4, 5);

        byte[] result = Rar3StandardFilters.apply(2, new int[7], input, 16L);

        assertEquals(100, getLittleEndianBits(input, 100, 20));
        assertEquals(99, getLittleEndianBits(result, 100, 20));
    }

    /// Verifies channel delta, RGB decorrelation, and adaptive audio filters against fixed vectors.
    @Test
    void appliesChannelFilters() throws IOException {
        int[] registers = new int[7];
        registers[0] = 1;
        assertArrayEquals(
                new byte[]{-1, -3, -6},
                Rar3StandardFilters.apply(3, registers, new byte[]{1, 2, 3}, 0L)
        );

        registers[0] = 6;
        registers[1] = 0;
        assertArrayEquals(
                new byte[]{-4, -3, -8, -10, -7, -18},
                Rar3StandardFilters.apply(4, registers, new byte[]{1, 2, 3, 4, 5, 6}, 0L)
        );

        registers[0] = 2;
        assertArrayEquals(
                new byte[]{-1, -3, -3, -7},
                Rar3StandardFilters.apply(5, registers, new byte[]{1, 2, 3, 4}, 0L)
        );
    }

    /// Verifies invalid identifiers, channel counts, widths, and color positions are rejected.
    @Test
    void rejectsInvalidFilterParameters() {
        int[] registers = new int[7];
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(-1, registers, new byte[0], 0L));

        registers[0] = 0;
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(3, registers, new byte[0], 0L));
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(5, registers, new byte[0], 0L));
        registers[0] = 257;
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(3, registers, new byte[0], 0L));
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(5, registers, new byte[0], 0L));

        registers[0] = 2;
        registers[1] = 0;
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(4, registers, new byte[0], 0L));
        registers[0] = 3;
        registers[1] = -1;
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(4, registers, new byte[0], 0L));
        registers[1] = 3;
        assertThrows(IOException.class, () -> Rar3StandardFilters.apply(4, registers, new byte[0], 0L));
    }

    /// Creates one five-byte x86 relative-address instruction.
    private static byte[] x86Instruction(int opcode, int address) {
        byte[] instruction = new byte[5];
        instruction[0] = (byte) opcode;
        ByteArrayAccess.writeIntLittleEndian(instruction, 1, address);
        return instruction;
    }

    /// Creates a zero-prefixed byte array whose final four bytes produce the requested CRC-32 value.
    private static byte[] codeWithCrc32(int length, long expectedCrc32) {
        byte[] code = new byte[length];
        int baseline = crc32(code);
        int[] basis = new int[32];
        int[] combinations = new int[32];
        for (int inputBit = 0; inputBit < 32; inputBit++) {
            byte[] candidate = code.clone();
            candidate[length - 4 + inputBit / 8] = (byte) (1 << inputBit % 8);
            int value = crc32(candidate) ^ baseline;
            int combination = 1 << inputBit;
            for (int outputBit = 31; outputBit >= 0; outputBit--) {
                if ((value & 1 << outputBit) == 0) continue;
                if (basis[outputBit] == 0) {
                    basis[outputBit] = value;
                    combinations[outputBit] = combination;
                    break;
                }
                value ^= basis[outputBit];
                combination ^= combinations[outputBit];
            }
        }

        int remaining = (int) expectedCrc32 ^ baseline;
        int selectedBits = 0;
        for (int outputBit = 31; outputBit >= 0; outputBit--) {
            if ((remaining & 1 << outputBit) == 0) continue;
            if (basis[outputBit] == 0) {
                throw new AssertionError("CRC-32 suffix matrix is singular");
            }
            remaining ^= basis[outputBit];
            selectedBits ^= combinations[outputBit];
        }
        for (int inputBit = 0; inputBit < 32; inputBit++) {
            if ((selectedBits & 1 << inputBit) != 0) {
                code[length - 4 + inputBit / 8] |= (byte) (1 << inputBit % 8);
            }
        }
        assertEquals(expectedCrc32, Integer.toUnsignedLong(crc32(code)));
        return code;
    }

    /// Returns the unsigned CRC-32 of one byte array as a signed bit pattern.
    private static int crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return (int) crc32.getValue();
    }

    /// Reads a little-endian bit field used by an Itanium bundle.
    private static int getLittleEndianBits(byte[] bytes, int position, int count) {
        int result = 0;
        for (int bit = 0; bit < count; bit++) {
            int absoluteBit = position + bit;
            result |= (bytes[absoluteBit >>> 3] >>> (absoluteBit & 7) & 1) << bit;
        }
        return result;
    }

    /// Writes a little-endian bit field used by an Itanium bundle.
    private static void setLittleEndianBits(byte[] bytes, int position, int count, int value) {
        for (int bit = 0; bit < count; bit++) {
            int absoluteBit = position + bit;
            int mask = 1 << (absoluteBit & 7);
            int index = absoluteBit >>> 3;
            if ((value >>> bit & 1) != 0) {
                bytes[index] |= (byte) mask;
            } else {
                bytes[index] &= (byte) ~mask;
            }
        }
    }
}
