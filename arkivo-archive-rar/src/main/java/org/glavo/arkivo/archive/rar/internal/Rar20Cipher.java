// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Implements the 16-byte substitution cipher selected by RAR extraction versions 20 and 26.
@NotNullByDefault
final class Rar20Cipher implements RarLegacyCipher {
    /// The cipher block size.
    private static final int BLOCK_SIZE = 16;
    /// The number of Feistel-style word rounds.
    private static final int ROUND_COUNT = 32;
    /// The initial byte substitution permutation.
    private static final int @Unmodifiable [] INITIAL_PERMUTATION = {
            215, 19, 149, 35, 73, 197, 192, 205, 249, 28, 16, 119, 48, 221, 2, 42,
            232, 1, 177, 233, 14, 88, 219, 25, 223, 195, 244, 90, 87, 239, 153, 137,
            255, 199, 147, 70, 92, 66, 246, 13, 216, 40, 62, 29, 217, 230, 86, 6,
            71, 24, 171, 196, 101, 113, 218, 123, 93, 91, 163, 178, 202, 67, 44, 235,
            107, 250, 75, 234, 49, 167, 125, 211, 83, 114, 157, 144, 32, 193, 143, 36,
            158, 124, 247, 187, 89, 214, 141, 47, 121, 228, 61, 130, 213, 194, 174, 251,
            97, 110, 54, 229, 115, 57, 152, 94, 105, 243, 212, 55, 209, 245, 63, 11,
            164, 200, 31, 156, 81, 176, 227, 21, 76, 99, 139, 188, 127, 17, 248, 51,
            207, 120, 189, 210, 8, 226, 41, 72, 183, 203, 135, 165, 166, 60, 98, 7,
            122, 38, 155, 170, 69, 172, 252, 238, 39, 134, 59, 128, 236, 27, 240, 80,
            131, 3, 85, 206, 145, 79, 154, 142, 159, 220, 201, 133, 74, 64, 20, 129,
            224, 185, 138, 103, 173, 182, 43, 34, 254, 82, 198, 151, 231, 180, 58, 10,
            118, 26, 102, 12, 50, 132, 22, 191, 136, 111, 162, 179, 45, 4, 148, 108,
            161, 56, 78, 126, 242, 222, 15, 175, 146, 23, 33, 241, 181, 190, 77, 225,
            0, 46, 169, 186, 68, 95, 237, 65, 53, 208, 253, 168, 9, 18, 100, 52,
            116, 184, 160, 96, 109, 37, 30, 106, 140, 104, 150, 5, 204, 117, 112, 84
    };
    /// The four evolving 32-bit block keys.
    private final int[] keys = {0xd3a3_b879, 0x3f6d_12f7, 0x7515_a235, 0xa4e7_f123};
    /// The password-dependent substitution permutation.
    private final int[] permutation = INITIAL_PERMUTATION.clone();
    /// Reusable storage preserving ciphertext for post-decryption key updates.
    private final byte[] savedCiphertext = new byte[BLOCK_SIZE];
    /// Whether mutable state has been cleared.
    private boolean cleared;

    /// Derives the substitution permutation and evolving keys from raw password bytes.
    Rar20Cipher(byte[] password) {
        Objects.requireNonNull(password, "password");
        derivePermutation(password);
        absorbPassword(password);
    }

    /// Returns the 16-byte processing unit.
    @Override
    public int blockSize() {
        return BLOCK_SIZE;
    }

    /// Decrypts complete blocks and updates keys from each original ciphertext block.
    @Override
    public void decrypt(byte[] data, int offset, int length) {
        requireState();
        Objects.checkFromIndexSize(offset, length, data.length);
        if (length % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("RAR 2.x ciphertext must contain complete blocks");
        }
        int limit = offset + length;
        for (int position = offset; position < limit; position += BLOCK_SIZE) {
            System.arraycopy(data, position, savedCiphertext, 0, BLOCK_SIZE);
            transformBlock(data, position, ROUND_COUNT - 1, -1);
            updateKeys(savedCiphertext, 0);
            Arrays.fill(savedCiphertext, (byte) 0);
        }
    }

    /// Clears keys, permutation, and saved ciphertext.
    @Override
    public void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(permutation, 0);
        Arrays.fill(savedCiphertext, (byte) 0);
        cleared = true;
    }

    /// Applies 256 password-dependent swap passes to the initial permutation.
    private void derivePermutation(byte[] password) {
        for (int pass = 0; pass < 256; pass++) {
            for (int passwordOffset = 0; passwordOffset < password.length; passwordOffset += 2) {
                int cursor = RarLegacyCRC.word(Byte.toUnsignedInt(password[passwordOffset]) - pass) & 0xff;
                int secondByte = passwordOffset + 1 < password.length
                        ? Byte.toUnsignedInt(password[passwordOffset + 1])
                        : 0;
                int target = RarLegacyCRC.word(secondByte + pass) & 0xff;
                int distance = 1;
                while (cursor != target) {
                    int swapIndex = cursor + passwordOffset + distance & 0xff;
                    int value = permutation[cursor];
                    permutation[cursor] = permutation[swapIndex];
                    permutation[swapIndex] = value;
                    cursor = cursor + 1 & 0xff;
                    distance++;
                }
            }
        }
    }

    /// Pads and encrypts password blocks so their ciphertext feeds the evolving keys.
    private void absorbPassword(byte[] password) {
        byte[] paddedPassword = Arrays.copyOf(password, alignedLength(password.length));
        try {
            for (int offset = 0; offset < password.length; offset += BLOCK_SIZE) {
                transformBlock(paddedPassword, offset, 0, 1);
                updateKeys(paddedPassword, offset);
            }
        } finally {
            Arrays.fill(paddedPassword, (byte) 0);
        }
    }

    /// Runs the word recurrence in ascending order for setup or descending order for decryption.
    private void transformBlock(byte[] data, int offset, int firstRound, int roundStep) {
        int first = readInt32(data, offset) ^ keys[0];
        int second = readInt32(data, offset + 4) ^ keys[1];
        int third = readInt32(data, offset + 8) ^ keys[2];
        int fourth = readInt32(data, offset + 12) ^ keys[3];
        int round = firstRound;
        for (int count = 0; count < ROUND_COUNT; count++, round += roundStep) {
            int roundKey = keys[round & 3];
            int nextThird = first ^ substitute(third + Integer.rotateLeft(fourth, 11) ^ roundKey);
            int nextFourth = second ^ substitute((fourth ^ Integer.rotateLeft(third, 17)) + roundKey);
            first = third;
            second = fourth;
            third = nextThird;
            fourth = nextFourth;
        }
        writeInt32(data, offset, third ^ keys[0]);
        writeInt32(data, offset + 4, fourth ^ keys[1]);
        writeInt32(data, offset + 8, first ^ keys[2]);
        writeInt32(data, offset + 12, second ^ keys[3]);
    }

    /// XORs four CRC words into the key selected by each byte position.
    private void updateKeys(byte[] data, int offset) {
        for (int index = 0; index < BLOCK_SIZE; index++) {
            keys[index & 3] ^= RarLegacyCRC.word(Byte.toUnsignedInt(data[offset + index]));
        }
    }

    /// Substitutes every byte of one little-endian word independently.
    private int substitute(int value) {
        return permutation[value & 0xff]
                | permutation[value >>> 8 & 0xff] << 8
                | permutation[value >>> 16 & 0xff] << 16
                | permutation[value >>> 24] << 24;
    }

    /// Returns a byte count rounded up to a complete cipher block.
    private static int alignedLength(int length) {
        return length + BLOCK_SIZE - 1 & -BLOCK_SIZE;
    }

    /// Reads one little-endian 32-bit word.
    private static int readInt32(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset])
                | Byte.toUnsignedInt(data[offset + 1]) << 8
                | Byte.toUnsignedInt(data[offset + 2]) << 16
                | Byte.toUnsignedInt(data[offset + 3]) << 24;
    }

    /// Writes one little-endian 32-bit word.
    private static void writeInt32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    /// Rejects use after state destruction.
    private void requireState() {
        if (cleared) throw new IllegalStateException("Legacy RAR cipher has been cleared");
    }
}
