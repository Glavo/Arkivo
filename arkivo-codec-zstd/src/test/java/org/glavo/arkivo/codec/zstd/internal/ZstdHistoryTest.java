// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bulk history transfers against the logical unbounded byte stream.
@NotNullByDefault
final class ZstdHistoryTest {
    /// Keeps abandoned block writes outside committed history and preserves history when switching to a ring.
    @ParameterizedTest
    @ValueSource(ints = {131072, 262144, 1048576})
    void commitsSharedBlocksOnlyAfterValidation(int capacity) throws IOException {
        var history = new ZstdHistory(capacity);
        byte[] stream = new byte[capacity + 131072 * 3];
        for (int i = 0; i < stream.length; i++) stream[i] = valueAt(i);
        history.append(stream, capacity);
        int total = capacity;
        for (int length : new int[]{0, 3, 131069, 1, 131071, 7, 131065}) {
            if (total > capacity) {
                assertNull(history.beginBlock(131072));
                history.append(Arrays.copyOfRange(stream, total, total + length), length);
                total += length;
                byte[] retained = new byte[capacity];
                history.copy(capacity, retained, 0, capacity);
                assertArrayEquals(Arrays.copyOfRange(stream, total - capacity, total), retained);
                continue;
            }
            byte[] storage = assertInstanceOf(byte[].class, history.beginBlock(131072));
            int start = history.blockOffset();
            Arrays.fill(storage, start, start + 131072, (byte) 0xa5);
            byte[] retained = new byte[capacity];
            history.copy(capacity, retained, 0, capacity);
            assertArrayEquals(Arrays.copyOfRange(stream, total - capacity, total), retained);
            assertThrows(IndexOutOfBoundsException.class, () -> history.commitBlock(131073));
            assertEquals(capacity, history.size());
            assertSame(storage, history.beginBlock(131072));
            assertEquals(start, history.blockOffset());
            System.arraycopy(stream, total, storage, start, length);
            history.commitBlock(length);
            total += length;
            history.copy(capacity, retained, 0, capacity);
            assertArrayEquals(Arrays.copyOfRange(stream, total - capacity, total), retained);
        }
        history.reset();
        assertEquals(0, history.size());
        byte[] storage = assertInstanceOf(byte[].class, history.beginBlock(8));
        assertEquals(0, history.blockOffset());
        storage[0] = 42;
        history.commitBlock(1);
        byte[] retained = new byte[1];
        history.copy(1, retained, 0, 1);
        assertArrayEquals(new byte[]{42}, retained);
    }

    /// Retains separate workspaces for small and segmented history windows.
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 131071, 16777217L, 4294967299L})
    void declinesSharedStorageOutsideContiguousBlockWindows(long capacity) throws IOException {
        assertNull(new ZstdHistory(capacity).beginBlock(131072));
    }

    /// Preserves the latest window across oversized appends, abandoned reservations and repeated rollover.
    @ParameterizedTest
    @ValueSource(ints = {131072, 131073, 262144, 1048576})
    void retainsWindowAcrossMixedAppends(int capacity) throws IOException {
        var history = new ZstdHistory(capacity);
        long written = 0;
        for (int count : new int[]{capacity - 1, 1, 3, 131071, capacity + 1, 7,
                capacity * 3 + 17, capacity - 3, 131072, 1, 131071}) {
            byte[] block = new byte[count + 7];
            for (int index = 0; index < count; index++) block[index] = valueAt(written + index);
            history.append(block, count);
            written += count;
            Arrays.fill(block, (byte) 0xa5);
            byte @Nullable [] storage = history.beginBlock(131072);
            if (storage != null) {
                Arrays.fill(storage, history.blockOffset(), history.blockOffset() + 131072, (byte) 0x5a);
            }
            int retained = (int) Math.min(written, capacity);
            assertEquals(retained, history.size());
            byte[] actual = new byte[retained];
            byte[] expected = new byte[retained];
            for (int index = 0; index < retained; index++) expected[index] = valueAt(written - retained + index);
            history.copy(retained, actual, 0, retained);
            assertArrayEquals(expected, actual);
        }
    }

    /// Checks every ring position and distance with empty, short and full-window copies.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 31, 128})
    void copiesContiguousRangesAtEveryRingPosition(int capacity) throws IOException {
        ZstdHistory history = new ZstdHistory(capacity);
        byte[] stream = new byte[capacity * 2];
        for (int i = 0; i < stream.length; i++) stream[i] = valueAt(i);
        for (int position = 0; position < capacity; position++) {
            history.reset();
            int total = capacity + position;
            history.append(stream, total);
            for (int distance = 1; distance <= capacity; distance++) {
                for (int requested : new int[]{0, 1, 7, 8, 15, 16, 17, 31, 32, distance}) {
                    int length = Math.min(requested, distance);
                    byte[] expected = new byte[length + 6];
                    Arrays.fill(expected, (byte) 0x5a);
                    byte[] actual = expected.clone();
                    System.arraycopy(stream, total - distance, expected, 3, length);
                    history.copy(distance, actual, 3, length);
                    assertArrayEquals(expected, actual);
                    assertEquals(capacity, history.size());
                }
            }
        }
    }

    /// Checks ring rollover, segment boundaries and windows larger than a Java array.
    ///
    /// @param capacity the logical window size
    @ParameterizedTest
    @ValueSource(longs = {1, 7, 32768, 131072, 1048576, 16_777_233, 4_294_967_299L})
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

    /// Rejects invalid source and destination ranges without modifying the destination.
    @Test
    void validatesCopyRangeBeforeWriting() throws IOException {
        ZstdHistory history = new ZstdHistory(7);
        history.append(new byte[]{1, 2, 3}, 3);
        byte[] target = {91, 92, 93};
        assertThrows(IndexOutOfBoundsException.class, () -> history.copy(3, target, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> history.copy(3, target, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> history.copy(3, target, 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> history.copy(3, target, Integer.MAX_VALUE, 1));
        assertThrows(IOException.class, () -> history.copy(0, target, 0, 1));
        assertThrows(IOException.class, () -> history.copy(4, target, 0, 1));
        assertThrows(IOException.class, () -> history.copy(1, target, 0, 2));
        history.copy(3, target, target.length, 0);
        assertArrayEquals(new byte[]{91, 92, 93}, target);
        byte[] retained = new byte[3];
        history.copy(3, retained, 0, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, retained);
    }

    /// Produces distinct patterns on both sides of segment and ring boundaries.
    private static byte valueAt(long position) {
        return (byte) (position * 31 + (position >>> 8) + (position >>> 20));
    }
}
