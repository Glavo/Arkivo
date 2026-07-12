// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Implements the byte-oriented file-data cipher selected by RAR extraction version 13.
@NotNullByDefault
final class Rar13Cipher implements RarLegacyCipher {
    /// The additive output key.
    private int outputKey;
    /// The accumulator added to the output key before every byte.
    private int accumulator;
    /// The rotated password-derived increment.
    private int increment;
    /// Whether mutable state has been cleared.
    private boolean cleared;

    /// Derives the three unsigned byte keys from raw password bytes.
    Rar13Cipher(byte[] password) {
        Objects.requireNonNull(password, "password");
        for (byte value : password) {
            int unsigned = Byte.toUnsignedInt(value);
            outputKey = outputKey + unsigned & 0xff;
            accumulator ^= unsigned;
            increment = increment + unsigned & 0xff;
            increment = (increment << 1 | increment >>> 7) & 0xff;
        }
    }

    /// Returns the one-byte processing unit.
    @Override
    public int blockSize() {
        return 1;
    }

    /// Decrypts bytes by advancing both additive accumulators before subtraction.
    @Override
    public void decrypt(byte[] data, int offset, int length) {
        requireState();
        Objects.checkFromIndexSize(offset, length, data.length);
        int limit = offset + length;
        for (int position = offset; position < limit; position++) {
            accumulator = accumulator + increment & 0xff;
            outputKey = outputKey + accumulator & 0xff;
            data[position] = (byte) (Byte.toUnsignedInt(data[position]) - outputKey);
        }
    }

    /// Clears all three byte keys.
    @Override
    public void clear() {
        outputKey = 0;
        accumulator = 0;
        increment = 0;
        cleared = true;
    }

    /// Rejects use after state destruction.
    private void requireState() {
        if (cleared) throw new IllegalStateException("Legacy RAR cipher has been cleared");
    }
}
