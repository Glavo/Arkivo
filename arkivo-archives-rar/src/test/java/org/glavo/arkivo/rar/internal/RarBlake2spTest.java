// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the RAR5 BLAKE2sp digest implementation against independent known answers.
@NotNullByDefault
public final class RarBlake2spTest {
    /// Input lengths covering leaf-block and eight-way stripe boundaries.
    private static final int @Unmodifiable [] LENGTHS = {
            0, 1, 63, 64, 65, 511, 512, 513, 2048
    };

    /// BLAKE2sp-256 digests for byte sequences whose byte at index n is n modulo 256.
    private static final String @Unmodifiable [] DIGESTS = {
            "dd0e891776933f43c7d032b08a917e25741f8aa9a12c12e1cac8801500f2ca4f",
            "a6b9eecc25227ad788c99d3f236debc8da408849e9a5178978727a81457f7239",
            "1024c940be7341449b5010522b509f65bbdc1287b455c2bb7f72b2c92fd0d189",
            "52603b6cbfad4966cb044cb267568385cf35f21e6c45cf30aed19832cb51e9f5",
            "fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae",
            "50285271956932d39b0967202b56006cbb6d738ee29e5a867edf72c8c4386f1b",
            "322ce06cc141a0b3d89bcdcfcb385975dbca56e5719a78c34000fcec2e15b55d",
            "1336628c7f1541c7815fc0ff1fb5dfb07a85cf5a17a2872a3ce4b322d4a03d0b",
            "2f2446d594620c85b60de83a27275f6145fc45cbfc2f4483a8592fdcc66111c3"
    };

    /// Verifies one-shot updates around every BLAKE2sp distribution boundary.
    @Test
    public void matchesKnownVectors() {
        for (int index = 0; index < LENGTHS.length; index++) {
            byte[] input = sequence(LENGTHS[index]);
            RarBlake2sp hash = new RarBlake2sp();
            hash.update(input, 0, input.length);
            assertArrayEquals(HexFormat.of().parseHex(DIGESTS[index]), hash.digest());
        }
    }

    /// Verifies fragmented and single-byte updates are equivalent to one contiguous input.
    @Test
    public void supportsFragmentedUpdates() {
        byte[] input = sequence(2048);
        RarBlake2sp hash = new RarBlake2sp();
        int position = 0;
        int chunkSize = 1;
        while (position < input.length) {
            int count = Math.min(chunkSize, input.length - position);
            if ((chunkSize & 1) == 0) {
                hash.update(input, position, count);
            } else {
                for (int index = 0; index < count; index++) {
                    hash.update(input[position + index]);
                }
            }
            position += count;
            chunkSize = chunkSize % 79 + 1;
        }
        assertArrayEquals(HexFormat.of().parseHex(DIGESTS[DIGESTS.length - 1]), hash.digest());
    }

    /// Verifies reset restores the empty-message state after finalization.
    @Test
    public void resetsFinalizedState() {
        RarBlake2sp hash = new RarBlake2sp();
        hash.update(1);
        hash.digest();
        assertThrows(IllegalStateException.class, hash::digest);
        assertThrows(IllegalStateException.class, () -> hash.update(2));

        hash.reset();
        assertArrayEquals(HexFormat.of().parseHex(DIGESTS[0]), hash.digest());
    }

    /// Creates a deterministic byte sequence for one known-answer vector.
    private static byte[] sequence(int length) {
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) index;
        }
        return input;
    }
}
