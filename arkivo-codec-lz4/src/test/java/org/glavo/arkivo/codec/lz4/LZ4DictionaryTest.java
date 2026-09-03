// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies LZ4 dictionary factories, identifier boundaries, and caller-buffer isolation.
@NotNullByDefault
final class LZ4DictionaryTest {
    /// Verifies every buffer factory copies only remaining bytes without changing any caller-visible buffer state.
    @Test
    void copiesRemainingBufferWithoutChangingState() {
        byte @Unmodifiable [] expected = {
                3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3
        };
        int offset = 5;
        ByteBuffer storage = ByteBuffer.allocateDirect(expected.length + 11);
        storage.position(offset);
        storage.put(expected);

        @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer();
        source.position(offset);
        source.limit(offset + expected.length);
        source.order(ByteOrder.LITTLE_ENDIAN);
        source.mark();

        LZ4Dictionary raw = LZ4Dictionary.rawContent(source);
        assertSourceState(source, offset, expected.length);
        LZ4Dictionary identified = LZ4Dictionary.identified(0xffff_ffffL, source);
        assertSourceState(source, offset, expected.length);
        LZ4Dictionary contentIdentified = LZ4Dictionary.identifiedByContent(source);
        assertSourceState(source, offset, expected.length);

        assertArrayEquals(expected, raw.bytes());
        assertArrayEquals(expected, identified.bytes());
        assertArrayEquals(expected, contentIdentified.bytes());
        assertFalse(raw.hasDictionaryId());
        assertEquals(LZ4Dictionary.NO_DICTIONARY_ID, raw.dictionaryId());
        assertTrue(identified.hasDictionaryId());
        assertEquals(0xffff_ffffL, identified.dictionaryId());
        assertEquals(
                LZ4Dictionary.identifiedByContent(expected).dictionaryId(),
                contentIdentified.dictionaryId()
        );

        storage.put(offset, (byte) 0x7f);
        assertArrayEquals(expected, raw.bytes());
        assertArrayEquals(expected, identified.bytes());
        assertArrayEquals(expected, contentIdentified.bytes());

        byte[] returned = identified.bytes();
        returned[0] = 0;
        assertArrayEquals(expected, identified.bytes());
        @UnmodifiableView ByteBuffer firstView = identified.buffer();
        @UnmodifiableView ByteBuffer secondView = identified.buffer();
        assertNotSame(firstView, secondView);
        firstView.position(firstView.limit());
        assertEquals(0, secondView.position());
        assertThrows(ReadOnlyBufferException.class, () -> secondView.put((byte) 0));
    }

    /// Verifies optional dictionary identifiers and required request identifiers use their exact unsigned domains.
    @Test
    void validatesDictionaryIdentifierBoundaries() {
        byte @Unmodifiable [] content = {1, 2, 3};
        LZ4Dictionary absent = LZ4Dictionary.identified(LZ4Dictionary.NO_DICTIONARY_ID, content);
        LZ4Dictionary zero = LZ4Dictionary.identified(0L, content);
        LZ4Dictionary maximum = LZ4Dictionary.identified(0xffff_ffffL, content);

        assertFalse(absent.hasDictionaryId());
        assertTrue(zero.hasDictionaryId());
        assertTrue(maximum.hasDictionaryId());
        assertEquals(0L, zero.dictionaryId());
        assertEquals(0xffff_ffffL, maximum.dictionaryId());
        assertThrows(
                IllegalArgumentException.class,
                () -> LZ4Dictionary.identified(LZ4Dictionary.NO_DICTIONARY_ID - 1L, content)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LZ4Dictionary.identified(0x1_0000_0000L, content)
        );

        LZ4DictionaryRequest zeroRequest = new LZ4DictionaryRequest(0L);
        LZ4DictionaryRequest maximumRequest = new LZ4DictionaryRequest(0xffff_ffffL);
        assertTrue(zeroRequest.matches(zero));
        assertFalse(zeroRequest.matches(absent));
        assertTrue(maximumRequest.matches(maximum));
        assertThrows(NullPointerException.class, () -> zeroRequest.matches(null));
        assertThrows(IllegalArgumentException.class, () -> new LZ4DictionaryRequest(-1L));
        assertThrows(IllegalArgumentException.class, () -> new LZ4DictionaryRequest(0x1_0000_0000L));
    }

    /// Verifies one source view retains its position, limit, byte order, and mark.
    private static void assertSourceState(ByteBuffer source, int position, int length) {
        assertEquals(position, source.position());
        assertEquals(position + length, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        source.reset();
        assertEquals(position, source.position());
    }
}
