// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable raw compression dictionary content.
@NotNullByDefault
final class RawCompressionDictionaryTest {
    /// Verifies factories and byte access cannot mutate stored content.
    @Test
    void preservesImmutableBytes() {
        byte[] source = {1, 2, 3};
        RawCompressionDictionary dictionary = RawCompressionDictionary.of(source);

        source[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());
        byte[] returned = dictionary.bytes();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());
        assertEquals(3, dictionary.size());
        assertInstanceOf(CompressionDictionary.class, dictionary);
    }

    /// Verifies buffer factories preserve source state and expose independent read-only views.
    @Test
    void supportsByteBuffers() {
        ByteBuffer source = ByteBuffer.allocateDirect(5);
        source.put(new byte[]{9, 1, 2, 3, 9}).flip();
        source.position(1);
        source.limit(4);
        int position = source.position();
        int limit = source.limit();

        RawCompressionDictionary dictionary = RawCompressionDictionary.of(source);
        assertEquals(position, source.position());
        assertEquals(limit, source.limit());
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());

        ByteBuffer first = dictionary.buffer();
        assertEquals(true, first.isReadOnly());
        assertEquals(0, first.position());
        assertEquals(3, first.remaining());
        first.get();
        ByteBuffer second = dictionary.buffer();
        assertEquals(0, second.position());
        assertThrows(ReadOnlyBufferException.class, () -> second.put((byte) 9));
    }
}
