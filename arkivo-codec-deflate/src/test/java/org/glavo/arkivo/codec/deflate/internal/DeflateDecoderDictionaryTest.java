// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies preset-history restoration in the pure Java raw Deflate decoder.
@NotNullByDefault
final class DeflateDecoderDictionaryTest {
    /// Verifies a dictionary larger than the window and reuse after reset.
    @Test
    void restoresDictionaryHistoryAfterReset() throws IOException {
        byte[] dictionary = new byte[40_000];
        new Random(0x1951L).nextBytes(dictionary);
        byte[] expected = Arrays.copyOfRange(dictionary, dictionary.length - 4096, dictionary.length);
        byte[] compressed = encodeWithDictionary(expected, dictionary);
        assertTrue(compressed.length < expected.length);

        try (DeflateDecoder decoder = new DeflateDecoder(RawCompressionDictionary.of(dictionary))) {
            assertArrayEquals(expected, decode(decoder, compressed));
            decoder.reset();
            assertArrayEquals(expected, decode(decoder, compressed));
        }

        try (DeflateDecoder decoder = new DeflateDecoder(null)) {
            assertThrows(IOException.class, () -> decode(decoder, compressed));
        }
    }

    /// Encodes a raw stream against one preset dictionary with the JDK reference implementation.
    private static byte[] encodeWithDictionary(byte[] input, byte[] dictionary) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setDictionary(dictionary);
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] target = new byte[128];
            while (!deflater.finished()) {
                int produced = deflater.deflate(target);
                output.write(target, 0, produced);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Decodes one complete stream using fresh source and target buffers for each operation.
    private static byte[] decode(CompressionDecoder decoder, byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        while (true) {
            int offered = Math.min(2, compressed.length - offset);
            ByteBuffer source = ByteBuffer.wrap(compressed, offset, offered).slice();
            ByteBuffer target = ByteBuffer.allocateDirect(3);
            CodecOutcome outcome = decoder.decode(source, target, offset + offered == compressed.length);
            offset += source.position();
            target.flip();
            while (target.hasRemaining()) {
                output.write(target.get());
            }
            if (outcome == CodecOutcome.FINISHED) {
                return output.toByteArray();
            }
            if (outcome != CodecOutcome.NEEDS_INPUT && outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected raw Deflate decoder outcome: " + outcome);
            }
        }
    }
}
