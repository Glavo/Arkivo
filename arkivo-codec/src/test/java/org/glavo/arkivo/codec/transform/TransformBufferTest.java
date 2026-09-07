// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies committed-prefix boundaries, suffix compaction, and terminal tails independently of I/O adapters.
@NotNullByDefault
final class TransformBufferTest {
    /// Verifies lookahead is neither transformed twice nor discarded when consumed output is reclaimed.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 15, 8191})
    void preservesLookaheadAcrossCompaction(int lookahead) throws IOException {
        int length = lookahead == 8191 ? 8221 : 24593;
        byte[] original = new byte[length];
        for (int i = 0; i < length; i++) {
            original[i] = (byte) (i * 37 + (i >>> 8));
        }
        for (int chunk : new int[]{7, 8191, 8192, 8193}) {
            int[] committed = {0};
            TransformBuffer buffer = new TransformBuffer((bytes, offset, count) -> {
                // Every suffix presented to the transform must still match the original input.
                assertArrayEquals(Arrays.copyOfRange(original, committed[0], committed[0] + count),
                        Arrays.copyOfRange(bytes, offset, offset + count));
                int prefix = Math.max(0, count - lookahead);
                for (int i = 0; i < prefix; i++) {
                    bytes[offset + i] ^= 0x5a;
                }
                committed[0] += prefix;
                return prefix;
            });
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            int position = 0;
            while (position < length) {
                ByteBuffer input = buffer.input();
                int copied = Math.min(Math.min(chunk, input.remaining()), length - position);
                input.put(original, position, copied);
                position += copied;
                buffer.transform();
                ByteBuffer output = buffer.output();
                while (output.hasRemaining()) {
                    int drained = Math.min(13, output.remaining());
                    actual.write(output.array(), output.position(), drained);
                    output.position(output.position() + drained);
                }
            }
            assertEquals(length - lookahead, committed[0]);
            assertEquals(length - lookahead, actual.size());
            buffer.finish();
            ByteBuffer tail = buffer.output();
            assertEquals(lookahead, tail.remaining());
            actual.write(tail.array(), tail.position(), tail.remaining());
            tail.position(tail.limit());
            buffer.finish();
            assertFalse(buffer.output().hasRemaining());

            byte[] expected = original.clone();
            for (int i = 0; i < length - lookahead; i++) {
                expected[i] ^= 0x5a;
            }
            assertArrayEquals(expected, actual.toByteArray());
        }
    }

    /// Verifies finish exposes both unread committed bytes and the unchanged suffix without another transform call.
    @Test
    void finishesPartiallyConsumedOutput() throws IOException {
        int[] calls = {0};
        TransformBuffer buffer = new TransformBuffer((bytes, offset, length) -> {
            calls[0]++;
            bytes[offset] = 9;
            return length - 2;
        });
        buffer.input().put(new byte[]{1, 2, 3, 4, 5});
        buffer.transform();
        assertEquals(9, buffer.output().get());
        buffer.finish();
        buffer.finish();
        byte[] remaining = new byte[4];
        buffer.output().get(remaining);
        assertArrayEquals(new byte[]{2, 3, 4, 5}, remaining);
        assertEquals(1, calls[0]);
    }

    /// Verifies invalid transformed counts are rejected before changing the committed output boundary.
    @ParameterizedTest
    @ValueSource(ints = {-1, 4, Integer.MAX_VALUE})
    void rejectsInvalidCounts(int count) {
        TransformBuffer buffer = new TransformBuffer((bytes, offset, length) -> count);
        buffer.input().put(new byte[]{1, 2, 3});
        assertThrows(IOException.class, buffer::transform);
        assertFalse(buffer.output().hasRemaining());
    }

    /// Verifies a stalled transform fails exactly when its pending input fills the working storage.
    @Test
    void rejectsFullUncommittedBuffer() throws IOException {
        TransformBuffer buffer = new TransformBuffer((bytes, offset, length) -> 0);
        ByteBuffer first = buffer.input();
        first.position(first.limit() - 1);
        buffer.transform();
        assertFalse(buffer.output().hasRemaining());
        ByteBuffer last = buffer.input();
        assertEquals(1, last.remaining());
        last.put((byte) 1);
        assertThrows(IOException.class, buffer::transform);
    }
}
