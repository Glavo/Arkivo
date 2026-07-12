// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/// Implements the symmetric stream cipher selected by RAR extraction version 15.
@NotNullByDefault
final class Rar15Cipher implements RarLegacyCipher {
    /// The four unsigned 16-bit evolving keys.
    private final int[] keys = new int[4];
    /// Whether mutable state has been cleared.
    private boolean cleared;

    /// Derives stream keys from the password CRC and byte-indexed CRC words.
    Rar15Cipher(byte[] password) {
        Objects.requireNonNull(password, "password");
        int passwordCrc = 0xffff_ffff;
        for (byte value : password) passwordCrc = RarLegacyCrc.update(passwordCrc, value);
        keys[0] = passwordCrc & 0xffff;
        keys[1] = passwordCrc >>> 16;
        for (byte value : password) {
            int unsigned = Byte.toUnsignedInt(value);
            int crcWord = RarLegacyCrc.word(unsigned);
            keys[2] = (keys[2] ^ unsigned ^ crcWord) & 0xffff;
            keys[3] = keys[3] + unsigned + (crcWord >>> 16) & 0xffff;
        }
    }

    /// Returns the one-byte processing unit.
    @Override
    public int blockSize() {
        return 1;
    }

    /// Applies the format-defined keystream byte and advances all four keys.
    @Override
    public void decrypt(byte[] data, int offset, int length) {
        requireState();
        Objects.checkFromIndexSize(offset, length, data.length);
        int limit = offset + length;
        for (int position = offset; position < limit; position++) {
            keys[0] = keys[0] + 0x1234 & 0xffff;
            int crcWord = RarLegacyCrc.word((keys[0] & 0x1fe) >>> 1);
            keys[1] = (keys[1] ^ crcWord) & 0xffff;
            keys[2] = keys[2] - (crcWord >>> 16) & 0xffff;
            keys[0] = (keys[0] ^ keys[2]) & 0xffff;
            keys[3] = (rotateRight16(keys[3]) ^ keys[1]) & 0xffff;
            keys[3] = rotateRight16(keys[3]);
            keys[0] = (keys[0] ^ keys[3]) & 0xffff;
            data[position] ^= (byte) (keys[0] >>> 8);
        }
    }

    /// Clears all four stream keys.
    @Override
    public void clear() {
        Arrays.fill(keys, 0);
        cleared = true;
    }

    /// Rotates one unsigned 16-bit key right by one bit.
    private static int rotateRight16(int value) {
        return (value >>> 1 | value << 15) & 0xffff;
    }

    /// Rejects use after state destruction.
    private void requireState() {
        if (cleared) throw new IllegalStateException("Legacy RAR cipher has been cleared");
    }
}
