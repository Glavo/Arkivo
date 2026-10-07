// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies staged transfers across buffer shapes, bounded ranges, and failed writes.
@NotNullByDefault
final class CodecBuffersTest {
    /// Verifies bounded copying without changing limits, byte orders, marks, or guard bytes.
    @Test
    void transfersAcrossBufferShapes() {
        for (boolean directSource : new boolean[]{false, true}) {
            for (boolean readOnlySource : new boolean[]{false, true}) {
                for (boolean directTarget : new boolean[]{false, true}) {
                    ByteBuffer source = allocate(directSource, 8);
                    source.put(new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
                    if (readOnlySource) {
                        source = source.asReadOnlyBuffer();
                    }
                    source.position(1).limit(6).mark();
                    source.order(ByteOrder.LITTLE_ENDIAN);
                    ByteBuffer target = allocate(directTarget, 6);
                    target.put(new byte[]{9, 9, 9, 9, 9, 9});
                    target.position(2).limit(4).mark();

                    assertEquals(2, CodecBuffers.transfer(source, target));
                    assertEquals(3, source.position());
                    assertEquals(4, target.position());
                    assertEquals(6, source.limit());
                    assertEquals(4, target.limit());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
                    assertEquals(ByteOrder.BIG_ENDIAN, target.order());
                    assertEquals(0, CodecBuffers.transfer(source, target));
                    assertEquals(1, source.reset().position());
                    assertEquals(2, target.reset().position());
                    target.clear();
                    byte[] actual = new byte[6];
                    target.get(actual);
                    assertArrayEquals(new byte[]{9, 9, 1, 2, 9, 9}, actual);

                    source.position(5);
                    target.clear();
                    assertEquals(1, CodecBuffers.transfer(source, target));
                    assertEquals(6, source.position());
                    assertEquals(1, target.position());
                    assertEquals(5, target.get(0));
                }
            }
        }
    }

    /// Verifies source state is restored when a nonempty copy targets a read-only buffer.
    @Test
    void preservesStateAfterReadOnlyFailure() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5});
        source.position(1).limit(5).mark();
        ByteBuffer target = ByteBuffer.allocate(3).asReadOnlyBuffer();
        target.position(1).mark();
        assertThrows(ReadOnlyBufferException.class, () -> CodecBuffers.transfer(source, target));
        assertEquals(1, source.position());
        assertEquals(5, source.limit());
        assertEquals(1, target.position());
        assertEquals(3, target.limit());
        assertEquals(1, source.reset().position());
        assertEquals(1, target.reset().position());
    }

    /// Verifies zero-byte transfers do not attempt to write, including into read-only buffers.
    @Test
    void permitsEmptyTransfers() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
        ByteBuffer emptyTarget = ByteBuffer.allocate(0).asReadOnlyBuffer();
        assertEquals(0, CodecBuffers.transfer(source, emptyTarget));
        assertEquals(0, source.position());
        source.position(source.limit());
        assertEquals(0, CodecBuffers.transfer(source, ByteBuffer.allocate(1).asReadOnlyBuffer()));
        assertEquals(0, CodecBuffers.transfer(source, source));
    }

    /// Verifies nonempty self-copy and null arguments are rejected without consuming input.
    @Test
    void rejectsInvalidArguments() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1, 2, 3});
        source.position(1).mark();
        assertThrows(IllegalArgumentException.class, () -> CodecBuffers.transfer(source, source));
        assertEquals(1, source.position());
        assertEquals(3, source.limit());
        assertEquals(1, source.reset().position());
        assertThrows(NullPointerException.class, () -> CodecBuffers.transfer(null, source));
        assertThrows(NullPointerException.class, () -> CodecBuffers.transfer(source, null));
    }

    /// Allocates a buffer with the requested storage kind.
    private static ByteBuffer allocate(boolean direct, int capacity) {
        return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
    }
}
