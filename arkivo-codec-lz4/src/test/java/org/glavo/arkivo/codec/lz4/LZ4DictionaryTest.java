// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies LZ4 dictionary identification and identifier boundaries.
@NotNullByDefault
final class LZ4DictionaryTest {
    /// Derives and preserves identifiers from the selected content rather than surrounding buffer bytes.
    @Test
    void identifiesRemainingBufferContent() {
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

        LZ4Dictionary raw = LZ4Dictionary.rawContent(source);
        LZ4Dictionary identified = LZ4Dictionary.identified(0xffff_ffffL, source);
        LZ4Dictionary contentIdentified = LZ4Dictionary.identifiedByContent(source);

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
}
