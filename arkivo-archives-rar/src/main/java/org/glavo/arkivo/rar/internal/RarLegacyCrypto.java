// Copyright (c) 1993-2026 Alexander Roshal
// SPDX-License-Identifier: LicenseRef-UnRAR

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Implements the legacy RAR 1.3, 1.5, and 2.x file-data decryption algorithms.
///
/// UnRAR source code may be used in any software to handle RAR archives without limitations free of charge, but cannot
/// be used to develop RAR (WinRAR) compatible archiver and to re-create RAR compression algorithm, which is proprietary.
/// Distribution of modified UnRAR source code in separate form or as a part of other software is permitted, provided that
/// full text of this paragraph, starting from "UnRAR source code" words, is included in license, or in documentation if
/// license is not available, and in source code comments of resulting package.
@NotNullByDefault
final class RarLegacyCrypto {
    /// The extraction version selecting the RAR 1.3 byte cipher.
    private static final int VERSION_13 = 13;

    /// The extraction version selecting the RAR 1.5 stream cipher.
    private static final int VERSION_15 = 15;

    /// The first extraction version selecting the RAR 2.x block cipher.
    private static final int VERSION_20 = 20;

    /// The later extraction version selecting the RAR 2.x block cipher.
    private static final int VERSION_26 = 26;

    /// The internal method selector for RAR 1.3.
    private static final int METHOD_13 = 13;

    /// The internal method selector for RAR 1.5.
    private static final int METHOD_15 = 15;

    /// The internal method selector for RAR 2.x.
    private static final int METHOD_20 = 20;

    /// The RAR 2.x cipher block size.
    private static final int BLOCK_SIZE_20 = 16;

    /// The maximum legacy password length retained by RAR.
    private static final int MAX_PASSWORD_BYTES = 127;

    /// The number of substitution rounds in the RAR 2.x block transform.
    private static final int ROUNDS_20 = 32;

    /// The standard reflected CRC32 lookup table used by legacy key derivation.
    private static final int @Unmodifiable [] CRC_TABLE = createCrcTable();

    /// The initial RAR 2.x byte substitution permutation.
    private static final int @Unmodifiable [] INITIAL_SUBSTITUTION = {
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

    /// Prevents instantiation.
    private RarLegacyCrypto() {
    }

    /// Returns whether an extraction version selects a supported legacy cipher.
    static boolean supports(int extractionVersion) {
        return extractionVersion == VERSION_13
                || extractionVersion == VERSION_15
                || extractionVersion == VERSION_20
                || extractionVersion == VERSION_26;
    }

    /// Returns whether an extraction version uses the block-oriented RAR 2.x cipher.
    static boolean usesBlockCipher(int extractionVersion) {
        return extractionVersion == VERSION_20 || extractionVersion == VERSION_26;
    }

    /// Creates a stateful decryptor from raw single-byte legacy password data.
    static Decryptor decryptor(int extractionVersion, byte[] password) throws IOException {
        if (!supports(extractionVersion)) {
            throw new IOException("Unsupported legacy RAR encryption version: " + extractionVersion);
        }
        int length = passwordLength(password);
        if (length > MAX_PASSWORD_BYTES) {
            throw new IOException("Legacy RAR password must contain at most 127 bytes");
        }
        byte[] normalizedPassword = Arrays.copyOf(password, length);
        try {
            return new Decryptor(method(extractionVersion), normalizedPassword);
        } finally {
            Arrays.fill(normalizedPassword, (byte) 0);
        }
    }

    /// Returns the effective password length before its first zero terminator.
    private static int passwordLength(byte[] password) {
        int length = 0;
        while (length < password.length && password[length] != 0) {
            length++;
        }
        return length;
    }

    /// Maps an extraction version to the internal cipher selector.
    private static int method(int extractionVersion) {
        if (extractionVersion == VERSION_13) {
            return METHOD_13;
        }
        return extractionVersion == VERSION_15 ? METHOD_15 : METHOD_20;
    }

    /// Builds the reflected CRC32 table used by RAR 1.5 and RAR 2.x key setup.
    private static int[] createCrcTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index++) {
            int value = index;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                value = (value & 1) != 0 ? value >>> 1 ^ 0xedb8_8320 : value >>> 1;
            }
            table[index] = value;
        }
        return table;
    }

    /// Decrypts one legacy RAR file-data stream and retains its evolving key state.
    @NotNullByDefault
    static final class Decryptor implements AutoCloseable {
        /// The selected legacy cipher method.
        private final int method;

        /// Mutable key words used by all legacy cipher variants.
        private final int[] keys = new int[4];

        /// The mutable RAR 2.x substitution permutation.
        private final int[] substitution = new int[256];

        /// A reusable copy of one RAR 2.x ciphertext block.
        private final byte[] ciphertext = new byte[BLOCK_SIZE_20];

        /// Whether all mutable key state has been cleared.
        private boolean closed;

        /// Initializes the selected legacy cipher from normalized password bytes.
        private Decryptor(int method, byte[] password) {
            this.method = method;
            switch (method) {
                case METHOD_13 -> initialize13(password);
                case METHOD_15 -> initialize15(password);
                case METHOD_20 -> initialize20(password);
                default -> throw new AssertionError("Unknown legacy RAR cipher method");
            }
        }

        /// Returns the input alignment required by this cipher.
        int blockSize() {
            return method == METHOD_20 ? BLOCK_SIZE_20 : 1;
        }

        /// Decrypts bytes in place while advancing the cipher state.
        void decrypt(byte[] data, int offset, int length) {
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, data.length);
            if (method == METHOD_20 && length % BLOCK_SIZE_20 != 0) {
                throw new IllegalArgumentException("RAR 2.x ciphertext must contain complete blocks");
            }
            switch (method) {
                case METHOD_13 -> decrypt13(data, offset, length);
                case METHOD_15 -> decrypt15(data, offset, length);
                case METHOD_20 -> {
                    int end = offset + length;
                    for (int position = offset; position < end; position += BLOCK_SIZE_20) {
                        decryptBlock20(data, position);
                    }
                }
                default -> throw new AssertionError("Unknown legacy RAR cipher method");
            }
        }

        /// Clears all mutable key and substitution state.
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(keys, 0);
            Arrays.fill(substitution, 0);
            Arrays.fill(ciphertext, (byte) 0);
        }

        /// Initializes the RAR 1.3 byte cipher.
        private void initialize13(byte[] password) {
            for (byte value : password) {
                int unsigned = Byte.toUnsignedInt(value);
                keys[0] = keys[0] + unsigned & 0xff;
                keys[1] = keys[1] ^ unsigned;
                keys[2] = keys[2] + unsigned & 0xff;
                keys[2] = (keys[2] << 1 | keys[2] >>> 7) & 0xff;
            }
        }

        /// Initializes the RAR 1.5 stream cipher.
        private void initialize15(byte[] password) {
            int passwordCrc = 0xffff_ffff;
            for (byte value : password) {
                passwordCrc = CRC_TABLE[(passwordCrc ^ value) & 0xff] ^ passwordCrc >>> 8;
            }
            keys[0] = passwordCrc & 0xffff;
            keys[1] = passwordCrc >>> 16 & 0xffff;
            for (byte value : password) {
                int unsigned = Byte.toUnsignedInt(value);
                int crc = CRC_TABLE[unsigned];
                keys[2] = (keys[2] ^ unsigned ^ crc) & 0xffff;
                keys[3] = keys[3] + unsigned + (crc >>> 16) & 0xffff;
            }
        }

        /// Initializes the RAR 2.x block cipher and password-dependent substitution table.
        private void initialize20(byte[] password) {
            keys[0] = 0xd3a3_b879;
            keys[1] = 0x3f6d_12f7;
            keys[2] = 0x7515_a235;
            keys[3] = 0xa4e7_f123;
            System.arraycopy(INITIAL_SUBSTITUTION, 0, substitution, 0, substitution.length);

            for (int round = 0; round < 256; round++) {
                for (int offset = 0; offset < password.length; offset += 2) {
                    int first = CRC_TABLE[(Byte.toUnsignedInt(password[offset]) - round) & 0xff] & 0xff;
                    int secondByte = offset + 1 < password.length ? Byte.toUnsignedInt(password[offset + 1]) : 0;
                    int second = CRC_TABLE[(secondByte + round) & 0xff] & 0xff;
                    for (int step = 1; first != second; step++, first = first + 1 & 0xff) {
                        int swapIndex = first + offset + step & 0xff;
                        int value = substitution[first];
                        substitution[first] = substitution[swapIndex];
                        substitution[swapIndex] = value;
                    }
                }
            }

            byte[] paddedPassword = Arrays.copyOf(password, alignToBlock(password.length));
            try {
                for (int offset = 0; offset < password.length; offset += BLOCK_SIZE_20) {
                    encryptBlock20(paddedPassword, offset);
                }
            } finally {
                Arrays.fill(paddedPassword, (byte) 0);
            }
        }

        /// Decrypts RAR 1.3 bytes in place.
        private void decrypt13(byte[] data, int offset, int length) {
            int end = offset + length;
            for (int position = offset; position < end; position++) {
                keys[1] = keys[1] + keys[2] & 0xff;
                keys[0] = keys[0] + keys[1] & 0xff;
                data[position] = (byte) (Byte.toUnsignedInt(data[position]) - keys[0]);
            }
        }

        /// Applies the symmetric RAR 1.5 stream transform in place.
        private void decrypt15(byte[] data, int offset, int length) {
            int end = offset + length;
            for (int position = offset; position < end; position++) {
                keys[0] = keys[0] + 0x1234 & 0xffff;
                int crc = CRC_TABLE[(keys[0] & 0x1fe) >>> 1];
                keys[1] = (keys[1] ^ crc) & 0xffff;
                keys[2] = keys[2] - (crc >>> 16) & 0xffff;
                keys[0] = (keys[0] ^ keys[2]) & 0xffff;
                keys[3] = (rotateRight16(keys[3]) ^ keys[1]) & 0xffff;
                keys[3] = rotateRight16(keys[3]);
                keys[0] = (keys[0] ^ keys[3]) & 0xffff;
                data[position] = (byte) (data[position] ^ keys[0] >>> 8);
            }
        }

        /// Encrypts one RAR 2.x block during password-based key setup.
        private void encryptBlock20(byte[] data, int offset) {
            int first = littleEndianInt(data, offset) ^ keys[0];
            int second = littleEndianInt(data, offset + 4) ^ keys[1];
            int third = littleEndianInt(data, offset + 8) ^ keys[2];
            int fourth = littleEndianInt(data, offset + 12) ^ keys[3];
            for (int round = 0; round < ROUNDS_20; round++) {
                int value = third + Integer.rotateLeft(fourth, 11) ^ keys[round & 3];
                int nextThird = first ^ substitute(value);
                value = (fourth ^ Integer.rotateLeft(third, 17)) + keys[round & 3];
                int nextFourth = second ^ substitute(value);
                first = third;
                second = fourth;
                third = nextThird;
                fourth = nextFourth;
            }
            putLittleEndianInt(data, offset, third ^ keys[0]);
            putLittleEndianInt(data, offset + 4, fourth ^ keys[1]);
            putLittleEndianInt(data, offset + 8, first ^ keys[2]);
            putLittleEndianInt(data, offset + 12, second ^ keys[3]);
            updateKeys20(data, offset);
        }

        /// Decrypts one RAR 2.x ciphertext block in place.
        private void decryptBlock20(byte[] data, int offset) {
            System.arraycopy(data, offset, ciphertext, 0, ciphertext.length);
            int first = littleEndianInt(data, offset) ^ keys[0];
            int second = littleEndianInt(data, offset + 4) ^ keys[1];
            int third = littleEndianInt(data, offset + 8) ^ keys[2];
            int fourth = littleEndianInt(data, offset + 12) ^ keys[3];
            for (int round = ROUNDS_20 - 1; round >= 0; round--) {
                int value = third + Integer.rotateLeft(fourth, 11) ^ keys[round & 3];
                int nextThird = first ^ substitute(value);
                value = (fourth ^ Integer.rotateLeft(third, 17)) + keys[round & 3];
                int nextFourth = second ^ substitute(value);
                first = third;
                second = fourth;
                third = nextThird;
                fourth = nextFourth;
            }
            putLittleEndianInt(data, offset, third ^ keys[0]);
            putLittleEndianInt(data, offset + 4, fourth ^ keys[1]);
            putLittleEndianInt(data, offset + 8, first ^ keys[2]);
            putLittleEndianInt(data, offset + 12, second ^ keys[3]);
            updateKeys20(ciphertext, 0);
            Arrays.fill(ciphertext, (byte) 0);
        }

        /// Updates RAR 2.x key words from one ciphertext or setup block.
        private void updateKeys20(byte[] data, int offset) {
            for (int index = 0; index < BLOCK_SIZE_20; index++) {
                keys[index & 3] = keys[index & 3] ^ CRC_TABLE[Byte.toUnsignedInt(data[offset + index])];
            }
        }

        /// Applies the current RAR 2.x substitution table to all four bytes of a word.
        private int substitute(int value) {
            return substitution[value & 0xff]
                    | substitution[value >>> 8 & 0xff] << 8
                    | substitution[value >>> 16 & 0xff] << 16
                    | substitution[value >>> 24] << 24;
        }

        /// Returns a password buffer size aligned to one RAR 2.x block.
        private static int alignToBlock(int length) {
            return length + BLOCK_SIZE_20 - 1 & -BLOCK_SIZE_20;
        }

        /// Rotates a 16-bit key word right by one bit.
        private static int rotateRight16(int value) {
            return (value >>> 1 | value << 15) & 0xffff;
        }

        /// Reads one little-endian 32-bit word.
        private static int littleEndianInt(byte[] data, int offset) {
            return Byte.toUnsignedInt(data[offset])
                    | Byte.toUnsignedInt(data[offset + 1]) << 8
                    | Byte.toUnsignedInt(data[offset + 2]) << 16
                    | Byte.toUnsignedInt(data[offset + 3]) << 24;
        }

        /// Writes one little-endian 32-bit word.
        private static void putLittleEndianInt(byte[] data, int offset, int value) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
            data[offset + 2] = (byte) (value >>> 16);
            data[offset + 3] = (byte) (value >>> 24);
        }

        /// Requires mutable cipher state to remain available.
        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Legacy RAR decryptor has been cleared");
            }
        }
    }
}
