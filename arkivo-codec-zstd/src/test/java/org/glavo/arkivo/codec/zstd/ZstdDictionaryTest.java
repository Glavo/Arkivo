// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Zstandard dictionary interpretation, identifiers, and immutable content.
@NotNullByDefault
final class ZstdDictionaryTest {
    /// Verifies automatic, forced-raw, and required-full interpretations remain distinct.
    @Test
    void distinguishesDictionaryContentTypes() {
        byte[] bytes = magicPrefixedBytes();

        ZstdDictionary automatic = ZstdDictionary.of(bytes);
        ZstdDictionary raw = ZstdDictionary.rawContent(bytes);
        ZstdDictionary full = ZstdDictionary.fullDictionary(bytes);

        assertEquals(ZstdDictionary.ContentType.AUTO, automatic.contentType());
        assertEquals(true, automatic.isFullDictionary());
        assertEquals(0x1234_5678L, automatic.dictionaryId());
        assertEquals(ZstdDictionary.ContentType.RAW_CONTENT, raw.contentType());
        assertEquals(false, raw.isFullDictionary());
        assertEquals(ZstdDictionary.NO_DICTIONARY_ID, raw.dictionaryId());
        assertEquals(ZstdDictionary.ContentType.FULL_DICTIONARY, full.contentType());
        assertEquals(0x1234_5678L, full.dictionaryId());
        assertInstanceOf(CompressionDictionary.class, raw);
    }

    /// Verifies forced raw content remains usable even when its first bytes equal the full-dictionary magic.
    @Test
    void forcesMagicPrefixedBytesToRawContent() throws IOException {
        byte[] bytes = magicPrefixedBytes();
        ZstdDictionary raw = ZstdDictionary.rawContent(bytes);
        ZstdCodec codec = new ZstdCodec().withDictionary(raw);
        byte[] input = Arrays.copyOfRange(bytes, 32, 224);

        ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
        ByteBuffer decoded = codec.withMaximumOutputSize(input.length).decompress(compressed);
        byte[] output = new byte[decoded.remaining()];
        decoded.get(output);

        assertArrayEquals(input, output);
        ZstdDictionary automatic = ZstdDictionary.of(bytes);
        assertThrows(IOException.class, () -> new ZstdCodec().withDictionary(automatic).newEncoder());
    }

    /// Verifies dictionary content is copied and exposed only through immutable views or copies.
    @Test
    void preservesImmutableContent() {
        byte[] source = magicPrefixedBytes();
        ZstdDictionary dictionary = ZstdDictionary.rawContent(source);
        source[0] = 0;

        assertEquals(256, dictionary.size());
        assertEquals(0x37, Byte.toUnsignedInt(dictionary.bytes()[0]));
        ByteBuffer view = dictionary.buffer();
        assertEquals(0, view.position());
        assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 0));
    }

    /// Verifies every buffer factory copies only remaining bytes and preserves the complete source-view state.
    @Test
    void copiesRemainingBuffersWithoutChangingState() {
        byte @Unmodifiable [] expected = magicPrefixedBytes();
        int offset = 3;
        ByteBuffer storage = ByteBuffer.allocateDirect(expected.length + 9);
        storage.position(offset);
        storage.put(expected);

        @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer();
        source.position(offset);
        source.limit(offset + expected.length);
        source.order(ByteOrder.LITTLE_ENDIAN);
        source.mark();

        ZstdDictionary automatic = ZstdDictionary.of(source);
        assertSourceState(source, offset, expected.length);
        ZstdDictionary raw = ZstdDictionary.rawContent(source);
        assertSourceState(source, offset, expected.length);
        ZstdDictionary full = ZstdDictionary.fullDictionary(source);
        assertSourceState(source, offset, expected.length);
        assertEquals(0x1234_5678L, ZstdDictionary.dictionaryId(source));
        assertSourceState(source, offset, expected.length);

        assertArrayEquals(expected, automatic.bytes());
        assertArrayEquals(expected, raw.bytes());
        assertArrayEquals(expected, full.bytes());
        assertEquals(ZstdDictionary.ContentType.AUTO, automatic.contentType());
        assertEquals(ZstdDictionary.ContentType.RAW_CONTENT, raw.contentType());
        assertEquals(ZstdDictionary.ContentType.FULL_DICTIONARY, full.contentType());

        storage.put(offset, (byte) 0);
        assertArrayEquals(expected, automatic.bytes());
        assertArrayEquals(expected, raw.bytes());
        assertArrayEquals(expected, full.bytes());

        @UnmodifiableView ByteBuffer first = automatic.buffer();
        @UnmodifiableView ByteBuffer second = automatic.buffer();
        assertNotSame(first, second);
        first.position(first.limit());
        assertEquals(0, second.position());
    }

    /// Verifies invalid dictionary representations and request identifiers are rejected eagerly.
    @Test
    void validatesRepresentationsAndRequests() {
        assertThrows(IllegalArgumentException.class, () -> ZstdDictionary.of(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> ZstdDictionary.fullDictionary(new byte[8]));
        byte[] zeroId = magicPrefixedBytes();
        Arrays.fill(zeroId, 4, 8, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> ZstdDictionary.of(zeroId));

        ZstdDictionary dictionary = ZstdDictionary.of(magicPrefixedBytes());
        ZstdDictionaryRequest request = new ZstdDictionaryRequest(dictionary.dictionaryId());
        assertEquals(true, request.matches(dictionary));
        assertThrows(IllegalArgumentException.class, () -> new ZstdDictionaryRequest(0L));
        assertThrows(IllegalArgumentException.class, () -> new ZstdDictionaryRequest(0x1_0000_0000L));

        assertEquals(ZstdDictionary.NO_DICTIONARY_ID, ZstdDictionary.dictionaryId(ByteBuffer.allocate(7)));
        assertEquals(ZstdDictionary.NO_DICTIONARY_ID, ZstdDictionary.dictionaryId(ByteBuffer.allocate(8)));
        assertThrows(NullPointerException.class, () -> ZstdDictionary.of((ByteBuffer) null));
        assertThrows(NullPointerException.class, () -> ZstdDictionary.rawContent((ByteBuffer) null));
        assertThrows(NullPointerException.class, () -> ZstdDictionary.fullDictionary((ByteBuffer) null));
        assertThrows(NullPointerException.class, () -> ZstdDictionary.dictionaryId(null));
    }

    /// Verifies one source view retains its position, limit, byte order, and mark.
    private static void assertSourceState(ByteBuffer source, int position, int length) {
        assertEquals(position, source.position());
        assertEquals(position + length, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        source.reset();
        assertEquals(position, source.position());
    }

    /// Returns content beginning with the standard dictionary magic and a nonzero little-endian identifier.
    private static byte[] magicPrefixedBytes() {
        byte[] bytes = new byte[256];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31 + 7);
        }
        bytes[0] = 0x37;
        bytes[1] = (byte) 0xa4;
        bytes[2] = 0x30;
        bytes[3] = (byte) 0xec;
        bytes[4] = 0x78;
        bytes[5] = 0x56;
        bytes[6] = 0x34;
        bytes[7] = 0x12;
        bytes[8] = 0;
        return bytes;
    }
}
