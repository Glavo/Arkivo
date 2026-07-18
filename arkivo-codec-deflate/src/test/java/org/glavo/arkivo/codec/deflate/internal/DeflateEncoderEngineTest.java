// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pure Java Deflate block selection, Huffman generation, and independent decoder interoperability.
@NotNullByDefault
final class DeflateEncoderEngineTest {
    /// Verifies exact-cost selection of stored, fixed-Huffman, and dynamic-Huffman blocks.
    @Test
    void selectsAllDeflateBlockTypes() throws IOException, DataFormatException {
        byte[] storedInput = new byte[2_048];
        new Random(1L).nextBytes(storedInput);
        byte[] stored = encode(storedInput, 0, DeflateStrategy.DEFAULT, 31, 3);
        assertEquals(0, firstBlockType(stored));
        assertArrayEquals(storedInput, inflate(stored));

        byte[] fixedInput = {1, 2, 3};
        byte[] fixed = encode(fixedInput, 6, DeflateStrategy.DEFAULT, 1, 1);
        assertEquals(1, firstBlockType(fixed));
        assertArrayEquals(fixedInput, inflate(fixed));

        byte[] dynamicInput = "dynamic Huffman block selection ".repeat(2_000).getBytes(StandardCharsets.UTF_8);
        byte[] dynamic = encode(dynamicInput, 6, DeflateStrategy.DEFAULT, 113, 2);
        assertEquals(2, firstBlockType(dynamic));
        assertArrayEquals(dynamicInput, inflate(dynamic));
    }

    /// Verifies length limiting for a deliberately deep literal-only Huffman tree.
    @Test
    void limitsAdversarialHuffmanDepth() throws IOException, DataFormatException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        int previous = 1;
        int current = 1;
        for (int symbol = 0; symbol < 22; symbol++) {
            int frequency = symbol < 2 ? 1 : Math.addExact(previous, current);
            if (symbol >= 2) {
                previous = current;
                current = frequency;
            }
            for (int count = 0; count < frequency; count++) {
                input.write(symbol);
            }
        }
        byte[] source = input.toByteArray();

        byte[] encoded = encode(source, 9, DeflateStrategy.HUFFMAN_ONLY, 97, 5);

        assertEquals(2, firstBlockType(encoded));
        assertArrayEquals(source, inflate(encoded));
    }

    /// Verifies every strategy with fragmented caller buffers and an independent RFC 1951 decoder.
    @Test
    void interoperatesAcrossStrategiesAndLevels() throws IOException, DataFormatException {
        Random random = new Random(0xdef1_8eL);
        for (DeflateStrategy strategy : DeflateStrategy.values()) {
            for (int level : new int[]{1, 6, 9}) {
                for (int iteration = 0; iteration < 8; iteration++) {
                    byte[] source = new byte[257 + random.nextInt(8_192)];
                    random.nextBytes(source);
                    byte[] phrase = " repeated pure Java Deflate phrase ".getBytes(StandardCharsets.UTF_8);
                    for (int offset = 41; offset + phrase.length <= source.length; offset += 173) {
                        System.arraycopy(phrase, 0, source, offset, phrase.length);
                    }

                    byte[] encoded = encode(
                            source,
                            level,
                            strategy,
                            1 + random.nextInt(127),
                            1 + random.nextInt(17)
                    );

                    assertArrayEquals(source, inflate(encoded));
                }
            }
        }
    }

    /// Verifies normal match search materially improves repeated data over literal-only encoding.
    @Test
    void huffmanOnlyDisablesMatchSearch() throws IOException {
        byte[] source = "strategy-sensitive repeated payload ".repeat(3_000).getBytes(StandardCharsets.UTF_8);

        byte[] normal = encode(source, 6, DeflateStrategy.DEFAULT, 1_003, 31);
        byte[] literalOnly = encode(source, 6, DeflateStrategy.HUFFMAN_ONLY, 1_003, 31);

        assertTrue(normal.length < literalOnly.length / 4);
    }

    /// Encodes one raw Deflate stream through fresh source and target buffers.
    private static byte @Unmodifiable [] encode(
            byte[] input,
            int compressionLevel,
            DeflateStrategy strategy,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflateEncoderEngine encoder = new DeflateEncoderEngine(
                DeflateEncoderEngine.Format.DEFLATE,
                compressionLevel,
                null,
                strategy
        )) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int length = Math.min(sourceFragmentSize, input.length - offset);
                ByteBuffer source = ByteBuffer.allocateDirect(length);
                source.put(input, offset, length).flip();
                while (true) {
                    ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                    CodecOutcome outcome = encoder.encode(source, target);
                    drain(target, output);
                    if (outcome == CodecOutcome.NEEDS_INPUT) {
                        assertEquals(false, source.hasRemaining());
                        break;
                    }
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                }
            }
            while (true) {
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                CodecOutcome outcome = encoder.finish(target);
                drain(target, output);
                if (outcome == CodecOutcome.FINISHED) {
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            }
        }
        return output.toByteArray();
    }

    /// Inflates one complete raw Deflate stream with the independent JDK implementation.
    private static byte @Unmodifiable [] inflate(byte[] input) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[257];
            while (!inflater.finished()) {
                int produced = inflater.inflate(chunk);
                if (produced > 0) {
                    output.write(chunk, 0, produced);
                } else {
                    assertEquals(false, inflater.needsInput());
                    assertEquals(false, inflater.needsDictionary());
                }
            }
            assertEquals(0, inflater.getRemaining());
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Returns the first block's two-bit type field.
    private static int firstBlockType(byte[] encoded) {
        return Byte.toUnsignedInt(encoded[0]) >>> 1 & 3;
    }

    /// Copies produced direct-buffer bytes into the accumulated output.
    private static void drain(ByteBuffer source, ByteArrayOutputStream output) {
        source.flip();
        while (source.hasRemaining()) {
            output.write(source.get());
        }
    }
}
