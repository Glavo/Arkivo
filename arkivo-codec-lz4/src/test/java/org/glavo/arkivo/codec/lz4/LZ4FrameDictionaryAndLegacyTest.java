// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests standard-frame dictionary semantics and read-only legacy-frame compatibility.
@NotNullByDefault
public final class LZ4FrameDictionaryAndLegacyTest {
    /// Verifies immutable dictionary construction, effective suffix selection, and codec configuration.
    @Test
    public void dictionaryValuesAreImmutableAndSelfTyped() {
        byte[] oversized = new byte[LZ4Dictionary.MAXIMUM_CONTENT_SIZE + 17];
        for (int index = 0; index < oversized.length; index++) {
            oversized[index] = (byte) index;
        }
        ByteBuffer source = ByteBuffer.wrap(oversized);
        source.position(3);
        int originalPosition = source.position();
        LZ4Dictionary raw = LZ4Dictionary.rawContent(source);
        assertEquals(originalPosition, source.position());
        assertEquals(LZ4Dictionary.MAXIMUM_CONTENT_SIZE, raw.size());
        assertFalse(raw.hasDictionaryId());
        assertEquals(LZ4Dictionary.NO_DICTIONARY_ID, raw.dictionaryId());

        byte[] expectedSuffix = Arrays.copyOfRange(
                oversized,
                oversized.length - LZ4Dictionary.MAXIMUM_CONTENT_SIZE,
                oversized.length
        );
        assertArrayEquals(expectedSuffix, raw.bytes());
        oversized[oversized.length - 1] ^= 1;
        assertArrayEquals(expectedSuffix, raw.bytes());

        LZ4Dictionary identified = LZ4Dictionary.identifiedByContent(expectedSuffix);
        assertTrue(identified.hasDictionaryId());
        assertNotEquals(LZ4Dictionary.NO_DICTIONARY_ID, identified.dictionaryId());
        assertEquals(
                identified.dictionaryId(),
                LZ4Dictionary.identifiedByContent(expectedSuffix).dictionaryId()
        );

        LZ4Codec codec = new LZ4Codec().withDictionary(identified);
        assertSame(identified, codec.dictionary());
        assertNull(codec.withoutDictionary().dictionary());
    }

    /// Verifies identified dictionaries across independent and linked physical blocks.
    @Test
    public void identifiedDictionariesRoundTripAcrossBlocks() throws IOException {
        byte[] dictionaryBytes = dictionaryBytes();
        byte[] input = new byte[2 * LZ4BlockSize.KIB_64.byteSize() + 37];
        for (int index = 0; index < input.length; index++) {
            input[index] = dictionaryBytes[index % dictionaryBytes.length];
        }
        LZ4Dictionary dictionary = LZ4Dictionary.identified(0xf123_4567L, dictionaryBytes);

        for (boolean independent : new boolean[]{true, false}) {
            LZ4Codec codec = LZ4Codec.builder()
                    .blockSize(LZ4BlockSize.KIB_64)
                    .independentBlocks(independent)
                    .blockChecksum(true)
                    .contentChecksum(true)
                    .dictionary(dictionary)
                    .build();
            byte[] frame = compress(codec, input);
            assertTrue((frame[4] & 1) != 0);
            assertEquals(
                    dictionary.dictionaryId(),
                    Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(frame, 6))
            );
            assertArrayEquals(input, decompress(codec, frame), "independent=" + independent);
            assertArrayEquals(
                    input,
                    decodeFragmentedDirect(new LZ4Codec(), frame, dictionary),
                    "fragmented independent=" + independent
            );
        }
    }

    /// Verifies zero is a present identifier while an out-of-band dictionary omits the descriptor field.
    @Test
    public void zeroAndAbsentDictionaryIdentifiersRemainDistinct() throws IOException {
        byte[] dictionaryBytes = dictionaryBytes();
        byte[] input = Arrays.copyOf(dictionaryBytes, 32_003);

        LZ4Dictionary zero = LZ4Dictionary.identified(0L, dictionaryBytes);
        byte[] identifiedFrame = compress(new LZ4Codec().withDictionary(zero), input);
        assertTrue((identifiedFrame[4] & 1) != 0);
        assertEquals(0, ByteArrayAccess.readIntLittleEndian(identifiedFrame, 6));
        assertTrue(new LZ4DictionaryRequest(0L).matches(zero));
        assertArrayEquals(input, decodeFragmentedDirect(new LZ4Codec(), identifiedFrame, zero));

        LZ4Dictionary raw = LZ4Dictionary.rawContent(dictionaryBytes);
        byte[] outOfBandFrame = compress(new LZ4Codec().withDictionary(raw), input);
        assertEquals(0, outOfBandFrame[4] & 1);
        assertArrayEquals(input, decompress(new LZ4Codec().withDictionary(raw), outOfBandFrame));
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), outOfBandFrame));
    }

    /// Verifies a parsed dictionary request rejects a mismatched identified dictionary and accepts the requested one.
    @Test
    public void lateDictionaryBindingFollowsDecoderLifecycle() throws IOException {
        byte[] dictionaryBytes = dictionaryBytes();
        byte[] input = Arrays.copyOfRange(dictionaryBytes, 7, 40_007);
        LZ4Dictionary requested = LZ4Dictionary.identified(23L, dictionaryBytes);
        LZ4Dictionary wrong = LZ4Dictionary.identified(24L, dictionaryBytes);
        byte[] frame = compress(new LZ4Codec().withDictionary(requested), input);

        try (CompressionDecoder.FramedDictionaryAware<LZ4Dictionary, LZ4DictionaryRequest> decoder =
                     new LZ4Codec().newDecoder()) {
            ByteBuffer source = ByteBuffer.wrap(frame);
            ByteBuffer target = ByteBuffer.allocate(1);
            assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, target));
            assertEquals(23L, decoder.dictionaryRequest().dictionaryId());
            assertThrows(IOException.class, () -> decoder.provideDictionary(wrong));
            decoder.provideDictionary(requested);
        }
    }

    /// Verifies configured dictionary storage is charged before any physical block is retained.
    @Test
    public void dictionaryStorageHonorsDecoderMemoryLimits() throws IOException {
        LZ4Dictionary dictionary = LZ4Dictionary.rawContent(dictionaryBytes());
        LZ4Codec codec = new LZ4Codec().withDictionary(dictionary);
        byte[] frame = compress(codec, new byte[]{1});
        try (CompressionDecoder decoder = codec.withMaximumMemorySize(dictionary.size()).newDecoder()) {
            assertThrows(
                    DecompressionMemoryLimitException.class,
                    () -> decoder.finish(ByteBuffer.wrap(frame), ByteBuffer.allocate(1))
            );
        }

        LZ4Dictionary required = LZ4Dictionary.identified(17L, dictionaryBytes());
        LZ4Dictionary initial = LZ4Dictionary.identified(18L, dictionaryBytes());
        byte[] identifiedFrame = compress(new LZ4Codec().withDictionary(required), new byte[]{1});
        try (CompressionDecoder.FramedDictionaryAware<LZ4Dictionary, LZ4DictionaryRequest> decoder =
                     new LZ4Codec()
                             .withDictionary(initial)
                             .withMaximumMemorySize(2L * dictionary.size() - 1L)
                             .newDecoder()) {
            assertEquals(
                    CodecOutcome.NEEDS_DICTIONARY,
                    decoder.decode(ByteBuffer.wrap(identifiedFrame), ByteBuffer.allocate(1))
            );
            assertThrows(DecompressionMemoryLimitException.class, () -> decoder.provideDictionary(required));
        }
    }

    /// Verifies legacy frames finish at physical EOF, a zero marker, or the next recognized frame magic.
    @Test
    public void decodesLegacyFramesAndPreservesImplicitBoundaries() throws IOException {
        byte[] legacyContent = "legacy lz4 content ".repeat(3000).getBytes(StandardCharsets.UTF_8);
        byte[] modernContent = "modern frame after legacy".getBytes(StandardCharsets.UTF_8);
        byte[] legacy = legacyFrame(legacyContent, false);
        byte[] modern = compress(new LZ4Codec(), modernContent);

        assertArrayEquals(legacyContent, decompress(new LZ4Codec(), legacy));
        assertArrayEquals(legacyContent, decodeFragmentedDirect(new LZ4Codec(), legacy, null));

        byte[] expected = concatenate(legacyContent, modernContent);
        byte[] concatenated = concatenate(legacy, modern);
        assertArrayEquals(expected, decompress(new LZ4Codec(), concatenated));
        ByteBuffer source = ByteBuffer.allocateDirect(concatenated.length);
        source.put(concatenated).flip();
        ByteArrayOutputStream firstFrame = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = new LZ4Codec().newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(5);
                outcome = decoder.decode(source, target);
                drain(target, firstFrame);
            } while (outcome != CodecOutcome.FINISHED);
        }
        assertArrayEquals(legacyContent, firstFrame.toByteArray());
        assertEquals(legacy.length, source.position());
        assertEquals(modern.length, source.remaining());
        assertArrayEquals(
                expected,
                decompress(new LZ4Codec(), concatenate(legacyFrame(legacyContent, true), modern))
        );
    }

    /// Verifies legacy detection and malformed trailing/header rejection.
    @Test
    public void detectsLegacyFramesAndRejectsMalformedBlocks() throws IOException {
        byte[] magic = {0x02, 0x21, 0x4c, 0x18};
        assertTrue(LZ4Format.instance().matches(ByteBuffer.wrap(magic)));
        assertFalse(LZ4Format.instance().matches(ByteBuffer.wrap(new byte[]{0x02, 0x21, 0x4c, 0x19})));
        assertThrows(
                IOException.class,
                () -> decompress(new LZ4Codec(), concatenate(magic, new byte[]{1, 2, 3}))
        );

        byte[] impossibleSize = concatenate(magic, new byte[]{-1, -1, -1, 127});
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), impossibleSize));

        byte[] partial = legacyFrame(new byte[]{1, 2, 3}, false);
        byte[] anotherBlock = legacyFrame(new byte[]{4, 5, 6}, false);
        byte[] dataAfterPartial = concatenate(partial, Arrays.copyOfRange(anotherBlock, Integer.BYTES, anotherBlock.length));
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), dataAfterPartial));
    }

    /// Compresses one complete standard frame into an owned byte array.
    private static byte[] compress(LZ4Codec codec, byte[] input) throws IOException {
        return bytes(codec.compress(ByteBuffer.wrap(input)));
    }

    /// Decompresses all concatenated frames into one owned byte array.
    private static byte[] decompress(LZ4Codec codec, byte[] input) throws IOException {
        return bytes(codec.withMaximumOutputSize(Integer.MAX_VALUE).decompress(ByteBuffer.wrap(input)));
    }

    /// Creates one legacy frame whose only block is independently compressed by the raw-block codec.
    private static byte[] legacyFrame(byte[] input, boolean zeroTerminated) throws IOException {
        byte[] compressed = bytes(new LZ4BlockCodec()
                .withMaximumBlockSize(8L * 1024L * 1024L)
                .compress(ByteBuffer.wrap(input)));
        ByteBuffer frame = ByteBuffer.allocate(
                Integer.BYTES + Integer.BYTES + compressed.length + (zeroTerminated ? Integer.BYTES : 0)
        );
        writeInt(frame, (int) LZ4Format.LEGACY_FRAME_MAGIC);
        writeInt(frame, compressed.length);
        frame.put(compressed);
        if (zeroTerminated) {
            writeInt(frame, 0);
        }
        return frame.array();
    }

    /// Decodes one frame through incrementally exposed direct source storage and tiny direct targets.
    private static byte[] decodeFragmentedDirect(
            LZ4Codec codec,
            byte[] encoded,
            @Nullable LZ4Dictionary providedDictionary
    ) throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length);
        source.put(encoded).flip();
        int completeLimit = source.limit();
        source.limit(0);
        boolean exposeInput = true;
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder.FramedDictionaryAware<LZ4Dictionary, LZ4DictionaryRequest> decoder =
                     codec.newDecoder()) {
            while (true) {
                if (exposeInput && source.limit() < completeLimit) {
                    source.limit(source.limit() + 1);
                }
                ByteBuffer target = ByteBuffer.allocateDirect(3);
                CodecOutcome outcome = source.limit() == completeLimit
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                drain(target, decoded);
                switch (outcome) {
                    case NEEDS_INPUT -> exposeInput = true;
                    case NEEDS_OUTPUT -> exposeInput = false;
                    case NEEDS_DICTIONARY -> {
                        LZ4Dictionary dictionary = providedDictionary;
                        if (dictionary == null) {
                            throw new IOException("Test decoder unexpectedly requested an unavailable dictionary");
                        }
                        assertTrue(decoder.dictionaryRequest().matches(dictionary));
                        decoder.provideDictionary(dictionary);
                        exposeInput = false;
                    }
                    case FINISHED -> {
                        return decoded.toByteArray();
                    }
                    default -> throw new IOException("Unexpected LZ4 decode outcome: " + outcome);
                }
            }
        }
    }

    /// Returns deterministic dictionary bytes with substantial internal repetition.
    private static byte[] dictionaryBytes() {
        byte[] pattern = "Arkivo LZ4 external dictionary content/".getBytes(StandardCharsets.UTF_8);
        byte[] dictionary = new byte[LZ4Dictionary.MAXIMUM_CONTENT_SIZE];
        for (int index = 0; index < dictionary.length; index++) {
            dictionary[index] = pattern[index % pattern.length];
        }
        return dictionary;
    }

    /// Copies a readable buffer into an owned byte array.
    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    /// Drains produced direct-buffer bytes into an owned byte stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Writes one little-endian 32-bit integer into a buffer.
    private static void writeInt(ByteBuffer buffer, int value) {
        int position = buffer.position();
        ByteArrayAccess.writeIntLittleEndian(buffer.array(), buffer.arrayOffset() + position, value);
        buffer.position(position + Integer.BYTES);
    }
}
