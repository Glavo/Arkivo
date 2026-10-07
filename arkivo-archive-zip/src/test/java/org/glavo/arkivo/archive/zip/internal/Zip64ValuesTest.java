// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies conditional ZIP64 field order and payload boundaries independently of archive I/O.
@NotNullByDefault
final class Zip64ValuesTest {
    /// Resolves every combination of size and location sentinels without consuming omitted fields.
    @Test
    void resolvesAllSentinelCombinations() throws IOException {
        for (int mask = 0; mask < 16; mask++) {
            ByteBuffer payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            if ((mask & 1) != 0) {
                payload.putLong(11);
            }
            if ((mask & 2) != 0) {
                payload.putLong(22);
            }
            if ((mask & 4) != 0) {
                payload.putLong(33);
            }
            if ((mask & 8) != 0) {
                payload.putInt(-1);
            }
            payload.putInt(0x12345678); // Excess producer metadata does not replace ordinary header values.

            Zip64Values values = Zip64Values.read(
                    extraField(Arrays.copyOf(payload.array(), payload.position())),
                    (mask & 1) != 0 ? 0xffff_ffffL : 1,
                    (mask & 2) != 0 ? 0xffff_ffffL : 2,
                    (mask & 4) != 0 ? 0xffff_ffffL : 3,
                    (mask & 8) != 0 ? 0xffff : 4
            );
            assertEquals(new Zip64Values(
                    (mask & 1) != 0 ? 11 : 1,
                    (mask & 2) != 0 ? 22 : 2,
                    (mask & 4) != 0 ? 33 : 3,
                    (mask & 8) != 0 ? 0xffff_ffffL : 4
            ), values, "sentinel mask " + mask);
        }
    }

    /// Does not require or inspect extra data when all header values fit in their ordinary fields.
    @Test
    void leavesOrdinaryHeaderValuesUnchanged() throws IOException {
        assertEquals(new Zip64Values(1, 2, 3, 4), Zip64Values.read(new byte[0], 1, 2, 3, 4));
        assertEquals(new Zip64Values(1, 2, 3, 4), Zip64Values.read(new byte[]{1}, 1, 2, 3, 4));
        assertThrows(IOException.class, () -> Zip64Values.read(new byte[0], 0xffff_ffffL, 2, 3, 4));
    }

    /// Rejects a missing byte at any position without reading bytes from the following extra field.
    @Test
    void rejectsTruncatedRequiredValues() {
        for (int length = 0; length < 28; length++) {
            byte[] zip64 = extraField(new byte[length]);
            byte[] extraData = Arrays.copyOf(zip64, zip64.length + 32);
            extraData[zip64.length] = 0x34;
            extraData[zip64.length + 1] = 0x12;
            extraData[zip64.length + 2] = 28;
            assertThrows(IOException.class, () -> Zip64Values.read(
                    extraData, 0xffff_ffffL, 0xffff_ffffL, 0xffff_ffffL, 0xffff));
        }
    }

    /// Accepts the largest signed long and rejects unsigned size or offset values beyond that range.
    @Test
    void checksLongRangeForEachRequiredValue() throws IOException {
        ByteBuffer payload = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(Long.MAX_VALUE).putLong(Long.MAX_VALUE).putLong(Long.MAX_VALUE);
        assertEquals(new Zip64Values(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0), Zip64Values.read(
                extraField(payload.array()), 0xffff_ffffL, 0xffff_ffffL, 0xffff_ffffL, 0));
        for (int index = 0; index < 3; index++) {
            payload.putLong(index * Long.BYTES, Long.MIN_VALUE);
            byte[] extraData = extraField(payload.array());
            assertThrows(IOException.class, () -> Zip64Values.read(
                    extraData, 0xffff_ffffL, 0xffff_ffffL, 0xffff_ffffL, 0));
            payload.putLong(index * Long.BYTES, Long.MAX_VALUE);
        }
    }

    /// Encodes one ZIP64 extra field with the specified payload.
    private static byte[] extraField(byte[] payload) {
        return ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1).putShort((short) payload.length).put(payload).array();
    }
}
