// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies canonical RAR Huffman table construction, decoding, reset, and malformed alphabets.
@NotNullByDefault
final class RarHuffmanDecoderTest {
    /// Accounts for actual symbol counts, build scratch space, replacement, reset, and repeated closure.
    @Test
    void accountsForLegacyHuffmanWorkspace() throws IOException {
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(272);
        Rar4HuffmanDecoder decoder = new Rar4HuffmanDecoder(memory);
        assertEquals(192, memory.used());
        for (int attempt = 0; attempt < 3; attempt++) {
            decoder.build(new byte[]{1, 1}, 0, 2);
            assertEquals(200, memory.used());
            decoder.build(new byte[]{2, 2, 2, 2}, 0, 4);
            assertEquals(208, memory.used());
            assertEquals(3, decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{(byte) 0xc0}))));
            decoder.build(new byte[4], 0, 4);
            assertEquals(192, memory.used());
            decoder.reset();
            assertEquals(192, memory.used());
        }
        decoder.close();
        decoder.close();
        assertEquals(0, memory.used());
        assertThrows(IOException.class, () -> decoder.build(new byte[]{1, 1}, 0, 2));
        assertThrows(IOException.class,
                () -> decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0}))));
    }

    /// Rejects each allocation before exceeding the limit without publishing a partial tree.
    @Test
    void rejectsLegacyHuffmanAllocationBoundaries() throws IOException {
        RarDecoderMemoryBudget insufficient = new RarDecoderMemoryBudget(191);
        assertThrows(DecompressionMemoryLimitException.class, () -> new Rar4HuffmanDecoder(insufficient));
        assertEquals(0, insufficient.used());
        for (int maximum : new int[]{199, 263, 271}) {
            RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
            try (Rar4HuffmanDecoder decoder = new Rar4HuffmanDecoder(memory)) {
                byte[] lengths;
                long required;
                if (maximum == 271) {
                    decoder.build(new byte[]{1, 1}, 0, 2);
                    lengths = new byte[]{2, 2, 2, 2};
                    required = 272;
                } else {
                    lengths = new byte[]{1, 1};
                    required = maximum == 199 ? 200 : 264;
                }
                DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                        () -> decoder.build(lengths, 0, lengths.length));
                assertEquals(maximum, failure.maximum());
                assertEquals(required, failure.actual());
                assertEquals(maximum == 199 ? 192 : 192 + 4L * lengths.length, memory.used());
                assertThrows(IOException.class,
                        () -> decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0}))));
                decoder.reset();
                assertEquals(192, memory.used());
            }
            assertEquals(0, memory.used());
        }
    }

    /// Discards a previous tree before processing malformed code lengths and permits a subsequent valid build.
    @Test
    void releasesLegacyAlphabetAfterInvalidBuild() throws IOException {
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(264);
        try (Rar4HuffmanDecoder decoder = new Rar4HuffmanDecoder(memory)) {
            for (byte[] invalid : new byte[][]{{16}, {1, 1, 1}}) {
                decoder.build(new byte[]{1, 1}, 0, 2);
                assertThrows(IOException.class, () -> decoder.build(invalid, 0, invalid.length));
                assertEquals(192, memory.used());
                assertThrows(IOException.class,
                        () -> decoder.decode(new Rar4BitInput(new ByteArrayInputStream(new byte[]{0}))));
            }
        }
        assertEquals(0, memory.used());
    }

    /// Rebuilds a bounded RAR5 decoder without leaking symbols from larger, empty, or invalid alphabets.
    @Test
    void reusesRar5AlphabetWorkspace() throws IOException {
        Rar5HuffmanDecoder decoder = new Rar5HuffmanDecoder(4);
        for (int attempt = 0; attempt < 3; attempt++) {
            decoder.build(new byte[]{2, 2, 2, 2}, 0, 4);
            assertEquals(3, decoder.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{(byte) 0xc0}))));
            decoder.build(new byte[]{1, 1}, 0, 2);
            assertEquals(1, decoder.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{(byte) 0x80}))));
            assertThrows(IllegalArgumentException.class, () -> decoder.build(new byte[5], 0, 5));
            assertThrows(IOException.class, () -> decoder.build(new byte[]{1}, 0, 1));
            assertThrows(IOException.class, () -> decoder.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{0}))));
            decoder.build(new byte[4], 0, 4);
            assertThrows(IOException.class, () -> decoder.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{0}))));
            decoder.reset();
        }
    }

    /// Verifies both decoder generations assign canonical codes by width and symbol order.
    @Test
    void decodesCanonicalTrees() throws IOException {
        byte[] lengths = {1, 2, 2};
        byte[] encoded = {0x58};

        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder(new RarDecoderMemoryBudget(ArchiveReadLimits.UNLIMITED_SIZE));
        legacy.build(lengths, 0, lengths.length);
        Rar4BitInput legacyInput = new Rar4BitInput(new ByteArrayInputStream(encoded));
        assertEquals(0, legacy.decode(legacyInput));
        assertEquals(1, legacy.decode(legacyInput));
        assertEquals(2, legacy.decode(legacyInput));

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder(3);
        assertThrows(
                IOException.class,
                () -> rar5.decode(new Rar5BitInput(new ByteArrayInputStream(encoded)))
        );
        rar5.build(lengths, 0, lengths.length);
        Rar5BitInput rar5Input = new Rar5BitInput(new ByteArrayInputStream(encoded));
        assertEquals(0, rar5.decode(rar5Input));
        assertEquals(1, rar5.decode(rar5Input));
        assertEquals(2, rar5.decode(rar5Input));
    }

    /// Verifies legacy RAR accepts unused code space but rejects bit patterns outside the assigned codes.
    @Test
    void supportsIncompleteLegacyTrees() throws IOException {
        Rar4HuffmanDecoder decoder = new Rar4HuffmanDecoder(new RarDecoderMemoryBudget(ArchiveReadLimits.UNLIMITED_SIZE));
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
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder(new RarDecoderMemoryBudget(ArchiveReadLimits.UNLIMITED_SIZE));
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

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder(3);
        rar5.build(new byte[0], 0, 0);
        assertThrows(
                IOException.class,
                () -> rar5.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[0])))
        );
        rar5.build(new byte[]{1, 1}, 0, 2);
        rar5.reset();
        assertThrows(
                IOException.class,
                () -> rar5.decode(new Rar5BitInput(new ByteArrayInputStream(new byte[]{0})))
        );
    }

    /// Verifies alphabet slices and code widths are validated by both decoder generations.
    @Test
    void validatesAlphabetRangesAndCodeWidths() throws IOException {
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder(new RarDecoderMemoryBudget(ArchiveReadLimits.UNLIMITED_SIZE));
        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder(3);
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
    void rejectsInvalidCanonicalCodeSpace() throws IOException {
        Rar4HuffmanDecoder legacy = new Rar4HuffmanDecoder(new RarDecoderMemoryBudget(ArchiveReadLimits.UNLIMITED_SIZE));
        IOException legacyException = assertThrows(
                IOException.class,
                () -> legacy.build(new byte[]{1, 1, 1}, 0, 3)
        );
        assertEquals("Legacy RAR Huffman tree is oversubscribed", legacyException.getMessage());

        Rar5HuffmanDecoder rar5 = new Rar5HuffmanDecoder(3);
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
