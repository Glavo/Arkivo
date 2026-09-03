// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies owned pending-output storage across growth, draining, clearing, and closure.
@NotNullByDefault
final class PendingOutputChannelTest {
    /// Verifies partially drained bytes retain their order when a following write requires storage growth.
    @Test
    void preservesOrderAcrossPartialDrainAndGrowth() throws ClosedChannelException {
        PendingOutputChannel channel = new PendingOutputChannel();
        byte[] first = sequence(9_000, 17);
        byte[] second = sequence(9_000, 83);

        ByteBuffer firstSource = ByteBuffer.wrap(first);
        assertEquals(first.length, channel.write(firstSource));
        assertEquals(firstSource.limit(), firstSource.position());

        ByteBuffer prefix = ByteBuffer.allocate(1_000);
        channel.drainTo(prefix);
        assertArrayEquals(slice(first, 0, 1_000), prefix.array());
        assertTrue(channel.hasRemaining());

        ByteBuffer guardedSecond = ByteBuffer.allocate(second.length + 2);
        guardedSecond.put((byte) 0x55).put(second).put((byte) 0x66).flip();
        guardedSecond.position(1);
        guardedSecond.limit(1 + second.length);
        assertEquals(second.length, channel.write(guardedSecond));
        assertEquals(guardedSecond.limit(), guardedSecond.position());

        // Mutation after write must not affect the channel's owned copy.
        guardedSecond.put(1, (byte) 0);
        byte[] actual = drainAll(channel, 317);
        byte[] expected = new byte[first.length - 1_000 + second.length];
        System.arraycopy(first, 1_000, expected, 0, first.length - 1_000);
        System.arraycopy(second, 0, expected, first.length - 1_000, second.length);
        assertArrayEquals(expected, actual);
        assertFalse(channel.hasRemaining());
    }

    /// Verifies partially drained storage is compacted when that avoids an unnecessary allocation.
    @Test
    void compactsPartiallyDrainedStorage() throws ClosedChannelException {
        PendingOutputChannel channel = new PendingOutputChannel();
        byte[] first = sequence(9_000, 29);
        byte[] second = sequence(6_000, 107);
        channel.write(ByteBuffer.wrap(first));

        ByteBuffer discardedPrefix = ByteBuffer.allocate(7_000);
        channel.drainTo(discardedPrefix);
        channel.write(ByteBuffer.wrap(second));

        byte[] expected = new byte[2_000 + second.length];
        System.arraycopy(first, 7_000, expected, 0, 2_000);
        System.arraycopy(second, 0, expected, 2_000, second.length);
        assertArrayEquals(expected, drainAll(channel, 509));
    }

    /// Verifies closure rejects all writes while leaving pending bytes available for draining or clearing.
    @Test
    void retainsPendingBytesAfterClosure() throws ClosedChannelException {
        PendingOutputChannel channel = new PendingOutputChannel();
        channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        channel.close();
        channel.close();
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(0)));

        ByteBuffer first = ByteBuffer.allocate(2);
        channel.drainTo(first);
        assertArrayEquals(new byte[]{1, 2}, first.array());
        assertTrue(channel.hasRemaining());

        channel.clear();
        assertFalse(channel.hasRemaining());
        ByteBuffer untouched = ByteBuffer.allocate(1);
        channel.drainTo(untouched);
        assertEquals(0, untouched.position());
    }

    /// Verifies null buffers are rejected without changing pending data.
    @Test
    void rejectsNullBuffersWithoutLosingData() throws ClosedChannelException {
        PendingOutputChannel channel = new PendingOutputChannel();
        channel.write(ByteBuffer.wrap(new byte[]{7, 8}));

        assertThrows(NullPointerException.class, () -> channel.write(null));
        assertThrows(NullPointerException.class, () -> channel.drainTo(null));
        assertArrayEquals(new byte[]{7, 8}, drainAll(channel, 1));
    }

    /// Returns deterministic bytes of the requested length.
    private static byte @Unmodifiable [] sequence(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (seed + index * 31);
        }
        return bytes;
    }

    /// Returns a copy of one byte-array range.
    private static byte @Unmodifiable [] slice(byte[] bytes, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(bytes, offset, result, 0, length);
        return result;
    }

    /// Drains all pending data using fixed-size target fragments.
    private static byte @Unmodifiable [] drainAll(PendingOutputChannel channel, int fragmentSize) {
        ByteBuffer result = ByteBuffer.allocate(32_000);
        while (channel.hasRemaining()) {
            ByteBuffer fragment = ByteBuffer.allocate(fragmentSize);
            channel.drainTo(fragment);
            fragment.flip();
            result.put(fragment);
        }
        result.flip();
        byte[] bytes = new byte[result.remaining()];
        result.get(bytes);
        return bytes;
    }
}
