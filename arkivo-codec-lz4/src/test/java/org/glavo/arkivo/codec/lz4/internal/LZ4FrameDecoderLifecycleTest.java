// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.glavo.arkivo.codec.lz4.LZ4Codec;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.codec.lz4.LZ4DictionaryRequest;
import org.glavo.arkivo.codec.lz4.LZ4Format;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies LZ4 frame boundaries, dictionary pauses, legacy lookahead, and terminal states.
@NotNullByDefault
final class LZ4FrameDecoderLifecycleTest {
    /// The primitive sentinel used for unlimited decoder resource bounds.
    private static final long UNLIMITED = -1L;

    /// The first standard skippable-frame magic value.
    private static final long SKIPPABLE_FRAME_MAGIC = 0x184d_2a50L;

    /// Verifies a completed standard frame remains stable until reset exposes the next frame.
    @Test
    void decodesConcatenatedStandardFramesAfterExplicitReset() throws IOException {
        byte[] first = "first LZ4 frame".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second LZ4 frame".getBytes(StandardCharsets.UTF_8);
        byte[] firstEncoded = encode(LZ4Codec.DEFAULT, first);
        byte[] secondEncoded = encode(LZ4Codec.DEFAULT, second);
        byte[] trailing = {11, 22, 33};
        ByteBuffer source = ByteBuffer.allocate(firstEncoded.length + secondEncoded.length + trailing.length)
                .put(firstEncoded)
                .put(secondEncoded)
                .put(trailing)
                .flip();
        LZ4FrameDecoder decoder = decoder(null, UNLIMITED);

        ByteBuffer firstTarget = ByteBuffer.allocate(first.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, firstTarget));
        assertEquals(firstEncoded.length, source.position());
        assertBufferEquals(first, firstTarget);

        int boundaryPosition = source.position();
        assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(0)));
        assertEquals(boundaryPosition, source.position());

        decoder.reset();
        ByteBuffer secondTarget = ByteBuffer.allocate(second.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, secondTarget));
        assertEquals(firstEncoded.length + secondEncoded.length, source.position());
        assertBufferEquals(second, secondTarget);

        byte[] remaining = new byte[source.remaining()];
        source.get(remaining);
        assertArrayEquals(trailing, remaining);
        decoder.close();
    }

    /// Verifies a dictionary request is stable, rejects mismatches, and resumes under a finite memory limit.
    @Test
    void resumesIdentifiedDictionaryFrameWithinFiniteMemoryLimit() throws IOException {
        byte[] dictionaryBytes = patternedBytes(8 * 1024);
        LZ4Dictionary requested = LZ4Dictionary.identified(23L, dictionaryBytes);
        LZ4Dictionary initial = LZ4Dictionary.identified(24L, dictionaryBytes);
        byte[] expected = Arrays.copyOfRange(dictionaryBytes, 1_000, 5_000);
        byte[] encoded = encode(LZ4Codec.DEFAULT.withDictionary(requested), expected);
        LZ4FrameDecoder decoder = decoder(initial, 1L << 20);

        IllegalStateException absentRequest = assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
        assertEquals("LZ4 decoder is not waiting for a dictionary", absentRequest.getMessage());

        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteBuffer target = ByteBuffer.allocate(expected.length + 1);
        assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, target));
        int requestPosition = source.position();
        LZ4DictionaryRequest request = decoder.dictionaryRequest();
        assertEquals(23L, request.dictionaryId());

        assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.finish(source, target));
        assertEquals(requestPosition, source.position());
        IOException mismatch = assertThrows(IOException.class, () -> decoder.provideDictionary(initial));
        assertEquals("Configured LZ4 dictionary does not satisfy " + request, mismatch.getMessage());
        assertEquals(23L, decoder.dictionaryRequest().dictionaryId());

        decoder.provideDictionary(requested);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
        assertFalse(source.hasRemaining());
        assertBufferEquals(expected, target);

        IllegalStateException completedRequest = assertThrows(
                IllegalStateException.class,
                decoder::dictionaryRequest
        );
        assertEquals("LZ4 decoder is not waiting for a dictionary", completedRequest.getMessage());
        decoder.close();
    }

    /// Verifies a legacy frame leaves following legacy and skippable magic values untouched.
    @Test
    void preservesEveryRecognizedLegacyLookaheadBoundary() throws IOException {
        assertLegacyLookaheadBoundary(LZ4Format.LEGACY_FRAME_MAGIC);
        assertLegacyLookaheadBoundary(SKIPPABLE_FRAME_MAGIC);
    }

    /// Verifies invalid magic and null arguments fail without weakening permanent closed-state checks.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesMagicArgumentsAndClosedState() {
        LZ4FrameDecoder malformed = decoder(null, UNLIMITED);
        IOException invalidMagic = assertThrows(
                IOException.class,
                () -> malformed.finish(
                        ByteBuffer.wrap(new byte[]{0, 0, 0, 0}),
                        ByteBuffer.allocate(1)
                )
        );
        assertEquals("Invalid LZ4 frame magic", invalidMagic.getMessage());
        malformed.close();

        LZ4FrameDecoder decoder = decoder(null, UNLIMITED);
        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.provideDictionary(null));

        decoder.close();
        decoder.close();
        IllegalStateException resetFailure = assertThrows(IllegalStateException.class, decoder::reset);
        assertEquals("LZ4 frame decoder is closed", resetFailure.getMessage());
        IllegalStateException decodeFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("LZ4 frame decoder is closed", decodeFailure.getMessage());
        IllegalStateException finishFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("LZ4 frame decoder is closed", finishFailure.getMessage());
        IllegalStateException dictionaryFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.provideDictionary(LZ4Dictionary.rawContent(new byte[0]))
        );
        assertEquals("LZ4 frame decoder is closed", dictionaryFailure.getMessage());
    }

    /// Verifies one implicit legacy boundary preserves the next frame magic in the source buffer.
    private static void assertLegacyLookaheadBoundary(long nextMagic) throws IOException {
        byte[] expected = "legacy boundary".getBytes(StandardCharsets.UTF_8);
        byte[] legacy = legacyFrame(expected);
        ByteBuffer source = ByteBuffer.allocate(legacy.length + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(legacy)
                .putInt((int) nextMagic)
                .flip();
        LZ4FrameDecoder decoder = decoder(null, UNLIMITED);
        ByteBuffer target = ByteBuffer.allocate(expected.length + 1);

        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));

        assertEquals(legacy.length, source.position());
        assertEquals(nextMagic, Integer.toUnsignedLong(source.getInt()));
        assertBufferEquals(expected, target);
        decoder.close();
    }

    /// Creates a decoder with checksum verification and the selected working-memory bound.
    private static LZ4FrameDecoder decoder(@Nullable LZ4Dictionary dictionary, long maximumMemorySize) {
        return new LZ4FrameDecoder(dictionary, UNLIMITED, maximumMemorySize, true);
    }

    /// Encodes one standard LZ4 frame through the public codec.
    private static byte[] encode(LZ4Codec codec, byte[] content) throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(content));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Creates one unterminated legacy frame whose next recognized magic supplies its boundary.
    private static byte[] legacyFrame(byte[] content) throws IOException {
        ByteBuffer compressed = LZ4BlockCodec.DEFAULT
                .withMaximumBlockSize(Math.max(1, content.length))
                .compress(ByteBuffer.wrap(content));
        ByteBuffer frame = ByteBuffer.allocate(2 * Integer.BYTES + compressed.remaining())
                .order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt((int) LZ4Format.LEGACY_FRAME_MAGIC);
        frame.putInt(compressed.remaining());
        frame.put(compressed);
        return frame.array();
    }

    /// Verifies a produced target contains exactly the expected bytes.
    private static void assertBufferEquals(byte[] expected, ByteBuffer target) {
        target.flip();
        byte[] actual = new byte[target.remaining()];
        target.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Creates deterministic dictionary bytes with repeated match opportunities.
    private static byte[] patternedBytes(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((index * 31) ^ (index >>> 3) ^ (index % 47));
        }
        return result;
    }
}
