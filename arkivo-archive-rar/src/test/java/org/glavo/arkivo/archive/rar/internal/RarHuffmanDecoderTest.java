// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies canonical RAR Huffman table construction, decoding, reset, and malformed alphabets.
@NotNullByDefault
final class RarHuffmanDecoderTest {
    /// Verifies both decoder generations assign canonical codes by width and symbol order.
    @Test
    void decodesCanonicalTrees() throws IOException {
        byte[] lengths = {1, 2, 2};
        byte[] encoded = {0x58};

        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder();
        legacy.build(lengths, 0, lengths.length);
        Rar4BitInput legacyInput = new Rar4BitInput(new ByteArrayInputStream(encoded));
        assertEquals(0, legacy.decode(legacyInput));
        assertEquals(1, legacy.decode(legacyInput));
        assertEquals(2, legacy.decode(legacyInput));

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder();
        assertFalse(rar5.isPopulated());
        rar5.build(lengths, 0, lengths.length);
        assertTrue(rar5.isPopulated());
        Rar5BitInput rar5Input = new Rar5BitInput(new ByteArrayInputStream(encoded));
        assertEquals(0, rar5.decode(rar5Input));
        assertEquals(1, rar5.decode(rar5Input));
        assertEquals(2, rar5.decode(rar5Input));
    }

    /// Verifies legacy RAR accepts unused code space but rejects bit patterns outside the assigned codes.
    @Test
    void supportsIncompleteLegacyTrees() throws IOException {
        Rar4HuffmanDecoder decoder = new Rar4HuffmanDecoder();
        decoder.build(new byte[]{2}, 0, 1);

        assertEquals(0, decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0}))));
        IOException exception = assertThrows(
                IOException.class,
                () -> decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0x40, 0})))
        );
        assertEquals("Invalid legacy RAR Huffman code", exception.getMessage());
    }

    /// Verifies empty alphabets and resets leave both decoders unable to decode symbols.
    @Test
    void rejectsDecodingWithoutAnAlphabet() throws IOException {
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder();
        legacy.build(new byte[0], 0, 0);
        assertThrows(
                IOException.class,
                () -> legacy.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[0])))
        );
        legacy.build(new byte[]{1, 1}, 0, 2);
        legacy.reset();
        assertThrows(
                IOException.class,
                () -> legacy.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0})))
        );

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder();
        rar5.build(new byte[0], 0, 0);
        assertFalse(rar5.isPopulated());
        assertThrows(
                IOException.class,
                () -> rar5.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[0])))
        );
        rar5.build(new byte[]{1, 1}, 0, 2);
        rar5.reset();
        assertFalse(rar5.isPopulated());
        assertThrows(
                IOException.class,
                () -> rar5.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{0})))
        );
    }

    /// Verifies alphabet slices and code widths are validated by both decoder generations.
    @Test
    void validatesAlphabetRangesAndCodeWidths() {
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder();
        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder();
        byte[] lengths = {0, 1, 1};

        assertThrows(IllegalArgumentException.class, () -> legacy.build(lengths, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> legacy.build(lengths, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> legacy.build(lengths, 2, 2));
        assertThrows(IOException.class, () -> legacy.build(new byte[]{16}, 0, 1));

        assertThrows(IllegalArgumentException.class, () -> rar5.build(lengths, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> rar5.build(lengths, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> rar5.build(lengths, 2, 2));
        assertThrows(IOException.class, () -> rar5.build(new byte[]{16}, 0, 1));
    }

    /// Verifies oversubscribed legacy trees and non-complete RAR5 trees are rejected.
    @Test
    void rejectsInvalidCanonicalCodeSpace() {
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder();
        IOException legacyException = assertThrows(
                IOException.class,
                () -> legacy.build(new byte[]{1, 1, 1}, 0, 3)
        );
        assertEquals("Legacy RAR Huffman tree is oversubscribed", legacyException.getMessage());

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder();
        IOException incompleteException = assertThrows(
                IOException.class,
                () -> rar5.build(new byte[]{1}, 0, 1)
        );
        assertEquals("RAR5 Huffman tree is incomplete or oversubscribed", incompleteException.getMessage());
        IOException oversubscribedException = assertThrows(
                IOException.class,
                () -> rar5.build(new byte[]{1, 1, 1}, 0, 3)
        );
        assertEquals("RAR5 Huffman tree is incomplete or oversubscribed", oversubscribedException.getMessage());
    }
}
