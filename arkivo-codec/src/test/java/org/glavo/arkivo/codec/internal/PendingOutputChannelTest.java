// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Random;

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

    /// Verifies a rejected target cannot discard pending bytes, even when draining after channel closure.
    @Test
    void rejectsReadOnlyTargetsWithoutLosingPendingBytes() throws ClosedChannelException {
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean closed : new boolean[]{false, true}) {
                PendingOutputChannel channel = new PendingOutputChannel();
                channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
                ByteBuffer prefix = ByteBuffer.allocate(1);
                channel.drainTo(prefix);
                assertEquals(1, prefix.get(0));
                if (closed) {
                    channel.close();
                }
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(5) : ByteBuffer.allocate(5);
                storage.put(new byte[]{9, 8, 7, 6, 5}).position(1).limit(4);
                ByteBuffer rejected = storage.asReadOnlyBuffer().mark();
                assertThrows(ReadOnlyBufferException.class, () -> channel.drainTo(rejected));
                assertEquals(1, rejected.position());
                assertEquals(4, rejected.limit());
                assertEquals(1, rejected.reset().position());
                assertTrue(channel.hasRemaining());
                channel.drainTo(ByteBuffer.allocate(0));
                assertTrue(channel.hasRemaining());
                channel.drainTo(storage);
                assertEquals(3, storage.position());
                assertEquals(4, storage.limit());
                byte[] actual = new byte[5];
                storage.clear().get(actual);
                assertArrayEquals(new byte[]{9, 2, 3, 6, 5}, actual);
                assertFalse(channel.hasRemaining());
            }
        }
    }

    /// Verifies interleaved writes, drains, and clears against an independent byte queue with reusable source storage.
    @Test
    void interleavedOperationsPreserveQueueContents() throws ClosedChannelException {
        for (long seed : new long[]{0x13579bdfL, 0x2468ace0L, 0x5eedL}) {
            Random random = new Random(seed);
            ArrayDeque<Byte> expected = new ArrayDeque<>();
            PendingOutputChannel channel = new PendingOutputChannel();
            for (int step = 0; step < 256; step++) {
                int operation = random.nextInt(16);
                if (operation == 0) {
                    channel.clear();
                    expected.clear();
                } else if (operation < 9) {
                    byte[] bytes = new byte[random.nextInt(2_049)];
                    random.nextBytes(bytes);
                    ByteBuffer storage = random.nextBoolean() ? ByteBuffer.allocateDirect(bytes.length + 4)
                            : ByteBuffer.allocate(bytes.length + 4);
                    storage.position(2).put(bytes).position(2).limit(bytes.length + 2);
                    ByteBuffer source = (random.nextBoolean() ? storage.asReadOnlyBuffer() : storage.duplicate()).mark();
                    assertEquals(bytes.length, channel.write(source));
                    assertEquals(bytes.length + 2, source.position());
                    assertEquals(bytes.length + 2, source.limit());
                    assertEquals(2, source.reset().position());
                    for (byte value : bytes) {
                        expected.addLast(value);
                    }
                    // Reusing caller storage must not change the channel's queued copy.
                    storage.clear();
                    while (storage.hasRemaining()) {
                        storage.put((byte) 0);
                    }
                } else {
                    int requested = random.nextInt(3_073);
                    ByteBuffer target = random.nextBoolean() ? ByteBuffer.allocateDirect(requested + 4)
                            : ByteBuffer.allocate(requested + 4);
                    target.put(0, (byte) 0x55).put(target.capacity() - 1, (byte) 0x66);
                    target.position(2).limit(requested + 2).mark();
                    int count = Math.min(requested, expected.size());
                    channel.drainTo(target);
                    assertEquals(count + 2, target.position());
                    assertEquals(requested + 2, target.limit());
                    assertEquals(2, target.reset().position());
                    for (int index = 0; index < count; index++) {
                        assertEquals(expected.removeFirst().byteValue(), target.get());
                    }
                    target.clear();
                    assertEquals(0x55, target.get(0));
                    assertEquals(0x66, target.get(target.capacity() - 1));
                }
                assertEquals(!expected.isEmpty(), channel.hasRemaining());
            }
            channel.close();
            ByteBuffer tail = ByteBuffer.allocate(expected.size() + 1);
            channel.drainTo(tail);
            assertEquals(expected.size(), tail.position());
            tail.flip();
            while (!expected.isEmpty()) {
                assertEquals(expected.removeFirst().byteValue(), tail.get());
            }
            assertFalse(channel.hasRemaining());
        }
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
