// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bulk history transfers against the logical unbounded byte stream.
@NotNullByDefault
final class ZstdHistoryTest {
    /// Checks ring rollover, segment boundaries and windows larger than a Java array.
    ///
    /// @param capacity the logical window size
    @ParameterizedTest
    @ValueSource(longs = {1, 7, 32768, 16_777_233, 4_294_967_299L})
    void copiesAcrossStorageBoundaries(long capacity) throws IOException {
        ZstdHistory history = new ZstdHistory(capacity);
        int total = (int) Math.min(capacity + 37, 18L * 1024 * 1024);
        int written = 0;
        while (written < total) {
            int count = Math.min(total - written, 131_071);
            byte[] block = new byte[count + 17];
            Arrays.fill(block, (byte) 0x5a);
            for (int i = 0; i < count; i++) block[i] = valueAt(written + i);
            history.append(block, count);
            Arrays.fill(block, (byte) 0);
            written += count;
            assertEquals(Math.min(capacity, written), history.size());
            for (long requested : new long[]{1, 7, 131_072, 1_048_575, 1_048_577, history.size()}) {
                long distance = Math.min(history.size(), requested);
                int length = (int) Math.min(distance, 131_073);
                byte[] actual = new byte[length + 6];
                byte[] expected = new byte[actual.length];
                Arrays.fill(actual, (byte) 0x5a);
                Arrays.fill(expected, (byte) 0x5a);
                for (int i = 0; i < length; i++) expected[i + 3] = valueAt(written - distance + i);
                history.copy(distance, actual, 3, length);
                assertArrayEquals(expected, actual);
            }
        }
        assertEquals(capacity, history.capacity());
        assertThrows(IOException.class, () -> history.copy(history.size() + 1, new byte[1], 0, 1));
        assertThrows(IOException.class, () -> history.copy(0, new byte[1], 0, 1));
        assertThrows(IOException.class, () -> history.copy(1, new byte[2], 0, 2));
        history.reset();
        history.reset();
        assertEquals(0, history.size());
        assertThrows(IOException.class, () -> history.copy(1, new byte[1], 0, 1));
        byte[] fresh = {91, 92, 93};
        int length = (int) Math.min(capacity, fresh.length);
        history.append(fresh, length);
        byte[] restored = new byte[length];
        history.copy(length, restored, 0, length);
        assertArrayEquals(Arrays.copyOf(fresh, length), restored);
        assertThrows(IOException.class, () -> history.copy(length + 1, new byte[1], 0, 1));
    }

    /// Allows empty appends but rejects output in a zero-capacity window.
    @Test
    void rejectsOutputWithoutAWindow() throws IOException {
        ZstdHistory history = new ZstdHistory(0);
        history.append(new byte[5], 0);
        assertEquals(0, history.size());
        assertThrows(IOException.class, () -> history.append(new byte[1], 1));
        assertEquals(0, history.size());
    }

    /// Rejects invalid prefix lengths without changing history.
    @Test
    void validatesPrefixLengthBeforeAppending() throws IOException {
        ZstdHistory history = new ZstdHistory(7);
        history.append(new byte[]{1, 2, 3, 99}, 3);
        assertThrows(IndexOutOfBoundsException.class, () -> history.append(new byte[2], -1));
        assertThrows(IndexOutOfBoundsException.class, () -> history.append(new byte[2], 3));
        assertEquals(3, history.size());
        byte[] result = new byte[3];
        history.copy(3, result, 0, result.length);
        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    /// Produces distinct patterns on both sides of segment and ring boundaries.
    private static byte valueAt(long position) {
        return (byte) (position * 31 + (position >>> 8) + (position >>> 20));
    }
}
