// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests transport-independent zlib stream encoder and decoder behavior.
@NotNullByDefault
public final class ZlibBufferEngineTest {
    /// Shared zlib codec under test.
    private static final ZlibCodec CODEC = new ZlibCodec();

    /// Verifies fragmented direct buffers and exact stream-boundary source positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, CODEC, 7, 1);
        byte[] tail = {11, 22, 33, 44};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length + tail.length);
        source.put(encoded).put(tail).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                outcome = decoder.decode(source, target);
                drain(target, decoded);
                assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
            } while (outcome != CodecOutcome.FINISHED);
        }

        assertArrayEquals(input, decoded.toByteArray());
        assertEquals(encoded.length, source.position());
        byte[] remaining = new byte[source.remaining()];
        source.get(remaining);
        assertArrayEquals(tail, remaining);
    }

    /// Verifies that each operation releases its caller-owned source and target buffers.
    @Test
    public void acceptsFreshBuffersForEveryOperation() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, CODEC, 1, 2);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(3, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                boolean endOfInput = offset + length == encoded.length;
                do {
                    ByteBuffer target = ByteBuffer.allocate(2);
                    outcome = endOfInput
                            ? decoder.finish(source, target)
                            : decoder.decode(source, target);
                    drain(target, decoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                offset += source.position();
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.FINISHED);
            }
            assertEquals(encoded.length, offset);
        }

        assertArrayEquals(input, decoded.toByteArray());
    }

    /// Verifies late dictionary negotiation and configured automatic dictionary application.
    @Test
    public void lateDictionaryBinding() throws IOException {
        byte[] dictionaryBytes = dictionaryData();
        Adler32 checksum = new Adler32();
        checksum.update(dictionaryBytes);
        ZlibDictionary dictionary = ZlibDictionary.of(dictionaryBytes);
        byte[] input = Arrays.copyOfRange(dictionaryBytes, dictionaryBytes.length - 2048, dictionaryBytes.length);
        ZlibCodec dictionaryCodec = CODEC.withDictionary(dictionary);
        byte[] encoded = encodeInFragments(input, dictionaryCodec, 17, 3);
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder.DictionaryAware<ZlibDictionary, ZlibDictionaryRequest> decoder =
                     CODEC.newDecoder(DecompressionLimits.ofMaximumOutputSize(input.length))) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(5);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.NEEDS_DICTIONARY, outcome);
            assertEquals(checksum.getValue(), decoder.dictionaryRequest().adler32());

            byte[] wrongBytes = dictionaryBytes.clone();
            wrongBytes[0] ^= 1;
            ZlibDictionary wrongDictionary = ZlibDictionary.of(wrongBytes);
            assertThrows(IOException.class, () -> decoder.provideDictionary(wrongDictionary));
            assertEquals(checksum.getValue(), decoder.dictionaryRequest().adler32());
            decoder.provideDictionary(dictionary);

            do {
                ByteBuffer target = ByteBuffer.allocate(5);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }

        assertArrayEquals(input, decoded.toByteArray());
        assertArrayEquals(
                input,
                decode(encoded, 2, dictionaryCodec, DecompressionLimits.UNLIMITED)
        );
    }

    /// Verifies sync-flush visibility through the standard JDK zlib inflater.
    @Test
    public void flushProducesDecodableBoundary() throws IOException, DataFormatException {
        byte[] first = "first flushed zlib payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second finished zlib payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Flushable encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 3);
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(1);
                outcome = encoder.flush(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, outcome);
            assertArrayEquals(first, inflateFlushedStream(encoded.toByteArray()));

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 3);
            finish(encoder, encoded, 1);
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, decode(encoded.toByteArray(), 2, CODEC, DecompressionLimits.UNLIMITED));
        assertArrayEquals(expected, inflateFlushedStream(encoded.toByteArray()));
    }

    /// Verifies declared-window enforcement without masking malformed zlib headers.
    @Test
    public void declaredWindowLimit() throws IOException {
        byte[] input = "small zlib buffer window".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = withMinimumWindowHeader(encodeInFragments(input, CODEC, 5, 2));
        DecompressionLimits exactLimits = DecompressionLimits.ofMaximumWindowSize(256L);
        DecompressionLimits shortLimits = DecompressionLimits.ofMaximumWindowSize(255L);

        assertArrayEquals(input, decode(encoded, 1, CODEC, exactLimits));
        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> decode(encoded, 1, CODEC, shortLimits)
        );
        assertEquals(255L, exception.maximumWindowSize());
        assertEquals(256L, exception.requiredWindowSize());

        IOException malformed = assertThrows(
                IOException.class,
                () -> decode(
                        new byte[]{0x78, 0x00},
                        1,
                        CODEC,
                        DecompressionLimits.ofMaximumWindowSize(0L)
                )
        );
        assertFalse(malformed instanceof DecompressionWindowLimitException);
    }

    /// Verifies the Adler-32 trailer and the history limit declared by the zlib header.
    @Test
    public void checksumAndDeclaredDistanceValidation() throws IOException {
        byte[] content = testData();
        byte[] encoded = encodeInFragments(content, CODEC, 29, 7);
        byte[] corrupted = encoded.clone();
        corrupted[corrupted.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decode(corrupted, 3, CODEC, DecompressionLimits.UNLIMITED));

        byte[] distantMatch = new byte[1_280];
        new Random(0x7a1bL).nextBytes(distantMatch);
        System.arraycopy(distantMatch, 0, distantMatch, 1_024, 256);
        byte[] limitedWindow = withMinimumWindowHeader(
                encodeInFragments(distantMatch, CODEC, 113, 11)
        );
        assertThrows(IOException.class, () -> decode(limitedWindow, 5, CODEC, DecompressionLimits.UNLIMITED));
    }

    /// Verifies output limiting, reset, terminal state, and non-framed engine capabilities.
    @Test
    public void lifecycleAndCapabilities() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, CODEC, 13, 5);
        DecompressionLimits exactLimits =
                DecompressionLimits.ofMaximumOutputSize(input.length);
        DecompressionLimits shortLimits =
                DecompressionLimits.ofMaximumOutputSize(input.length - 1L);
        assertArrayEquals(input, decode(encoded, 1, CODEC, exactLimits));
        assertThrows(
                DecompressionLimitException.class,
                () -> decode(encoded, 1, CODEC, shortLimits)
        );

        CompressionEncoder.Flushable encoder = CODEC.newEncoder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), first, 4);
        finish(encoder, first, 1);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(1)));
        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), second, 4);
        finish(encoder, second, 1);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        assertFalse(encoder instanceof CompressionEncoder.Framed);
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);

    }

    /// Encodes source fragments into one zlib stream.
    private static byte[] encodeInFragments(
            byte[] input,
            ZlibCodec codec,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.Flushable encoder = codec.newEncoder()) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int length = Math.min(sourceFragmentSize, input.length - offset);
                encodeSource(encoder, ByteBuffer.wrap(input, offset, length).slice(), encoded, targetSize);
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder.Flushable encoder,
            ByteBuffer source,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, encoded);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertFalse(source.hasRemaining());
    }

    /// Drains stream finalization with bounded target buffers.
    private static void finish(
            CompressionEncoder.Flushable encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finish(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Decodes one complete stream with bounded target buffers.
    private static byte[] decode(
            byte[] encoded,
            int targetSize,
            ZlibCodec codec,
            DecompressionLimits limits
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer source = ByteBuffer.wrap(encoded);
        try (CompressionDecoder decoder = codec.newDecoder(limits)) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(targetSize);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
            assertFalse(source.hasRemaining());
        }
        return decoded.toByteArray();
    }

    /// Inflates all zlib output currently visible after a sync flush.
    private static byte[] inflateFlushedStream(byte[] encoded) throws DataFormatException {
        Inflater inflater = new Inflater(false);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try {
            inflater.setInput(encoded);
            byte[] output = new byte[17];
            while (!inflater.needsInput()) {
                int produced = inflater.inflate(output);
                if (produced == 0) {
                    break;
                }
                decoded.write(output, 0, produced);
            }
            return decoded.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Returns a copy whose valid zlib header declares the minimum 256-byte window.
    private static byte[] withMinimumWindowHeader(byte[] encoded) {
        byte[] adjusted = encoded.clone();
        int compressionMethodAndInfo = 0x08;
        int flags = Byte.toUnsignedInt(adjusted[1]) & 0xe0;
        for (int check = 0; check < 32; check++) {
            int candidate = flags | check;
            if (((compressionMethodAndInfo << 8) | candidate) % 31 == 0) {
                adjusted[0] = (byte) compressionMethodAndInfo;
                adjusted[1] = (byte) candidate;
                return adjusted;
            }
        }
        throw new AssertionError("Unable to construct a valid zlib header");
    }

    /// Copies produced buffer bytes into the supplied byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte @Unmodifiable [] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic, weakly compressible test content.
    private static byte @Unmodifiable [] testData() {
        byte[] data = new byte[32 * 1024 + 37];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 31) ^ (index >>> 3));
        }
        return data;
    }

    /// Creates deterministic dictionary content with repeated suffixes.
    private static byte @Unmodifiable [] dictionaryData() {
        return (
                "Arkivo zlib buffer dictionary phrase 0123456789;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);
    }
}
