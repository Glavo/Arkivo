// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.zip.Adler32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable zlib dictionary content and derived requests.
@NotNullByDefault
final class ZlibDictionaryTest {
    /// Verifies content immutability and automatic Adler-32 derivation.
    @Test
    void derivesIdentifierFromImmutableContent() {
        byte[] source = {1, 2, 3, 4};
        Adler32 checksum = new Adler32();
        checksum.update(source);

        ZlibDictionary dictionary = ZlibDictionary.of(source);
        source[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3, 4}, dictionary.bytes());
        assertEquals(checksum.getValue(), dictionary.adler32());
        assertEquals(4, dictionary.size());
        assertInstanceOf(CompressionDictionary.class, dictionary);
        assertTrue(new ZlibDictionaryRequest(checksum.getValue()).matches(dictionary));
    }

    /// Verifies buffer factories preserve source state and return read-only views.
    @Test
    void preservesBufferState() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 9});
        source.position(1);
        source.limit(4);

        ZlibDictionary dictionary = ZlibDictionary.of(source);

        assertEquals(1, source.position());
        assertEquals(4, source.limit());
        assertArrayEquals(new byte[]{1, 2, 3}, dictionary.bytes());
        ByteBuffer view = dictionary.buffer();
        assertEquals(0, view.position());
        assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 0));
    }

    /// Verifies dictionary requests accept only unsigned 32-bit Adler-32 values.
    @Test
    void validatesRequestRange() {
        assertEquals(0L, new ZlibDictionaryRequest(0L).adler32());
        assertEquals(0xffff_ffffL, new ZlibDictionaryRequest(0xffff_ffffL).adler32());
        assertThrows(IllegalArgumentException.class, () -> new ZlibDictionaryRequest(-1L));
        assertThrows(IllegalArgumentException.class, () -> new ZlibDictionaryRequest(0x1_0000_0000L));
    }
}
