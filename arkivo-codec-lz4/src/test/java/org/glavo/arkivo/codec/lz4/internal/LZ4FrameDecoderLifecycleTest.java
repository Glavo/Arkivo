// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.glavo.arkivo.codec.lz4.LZ4BlockSize;
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

        decoder.reset();
        ByteBuffer requestedAgain = ByteBuffer.wrap(encoded);
        ByteBuffer resetTarget = ByteBuffer.allocate(expected.length + 1);
        assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.finish(requestedAgain, resetTarget));
        assertEquals(23L, decoder.dictionaryRequest().dictionaryId());
        assertEquals(0, resetTarget.position());

        decoder.reset();
        assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
        byte[] initialFrame = encode(LZ4Codec.DEFAULT.withDictionary(initial), expected);
        ByteBuffer initialSource = ByteBuffer.wrap(initialFrame);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(initialSource, resetTarget));
        assertFalse(initialSource.hasRemaining());
        assertBufferEquals(expected, resetTarget);
        decoder.close();
    }

    /// Verifies reset abandons partial standard frames, pending output, and rejected block or content checksums.
    @Test
    void resetsEveryPartialStandardFramePhase() throws IOException {
        byte[] discardedContent = patternedBytes(257);
        byte[] expected = patternedBytes(131);
        LZ4Dictionary dictionary = LZ4Dictionary.identified(7, patternedBytes(513));
        LZ4Codec codec = LZ4Codec.DEFAULT.withBlockSize(LZ4BlockSize.KIB_64)
                .withBlockChecksum(true).withContentChecksum(true).withDictionary(dictionary);
        byte[] discardedFrame = encode(codec, discardedContent);
        byte[] expectedFrame = encode(codec, expected);
        try (LZ4FrameDecoder decoder = decoder(dictionary, 1L << 20)) {
            for (int cut = 0; cut < discardedFrame.length; cut++) {
                ByteBuffer prefix = ByteBuffer.allocateDirect(cut + 4);
                prefix.position(2).put(discardedFrame, 0, cut).flip().position(2);
                ByteBuffer source = prefix.asReadOnlyBuffer();
                ByteBuffer discarded = ByteBuffer.allocateDirect(discardedContent.length + 1);
                assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(source, discarded), "cut at " + cut);
                assertEquals(cut + 2, source.position());
                assertEquals(cut + 2, source.limit());
                assertEquals(2, prefix.position());

                decoder.reset();
                ByteBuffer completeSource = ByteBuffer.wrap(expectedFrame).asReadOnlyBuffer();
                ByteBuffer target = ByteBuffer.allocateDirect(expected.length + 1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(completeSource, target), "reset after " + cut);
                assertFalse(completeSource.hasRemaining());
                assertBufferEquals(expected, target);
                decoder.reset();
            }

            ByteBuffer source = ByteBuffer.wrap(discardedFrame);
            ByteBuffer tinyTarget = ByteBuffer.allocate(1);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(source, tinyTarget));
            assertEquals(discardedContent[0], tinyTarget.get(0));
            int discardedPosition = source.position();
            decoder.reset();
            assertEquals(discardedPosition, source.position());
            ByteBuffer target = ByteBuffer.allocate(expected.length + 1);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(expectedFrame), target));
            assertBufferEquals(expected, target);

            // A single-block frame ends with its block checksum, EndMark, and content checksum.
            int[] checksumOffsets = {discardedFrame.length - 3 * Integer.BYTES, discardedFrame.length - Integer.BYTES};
            for (int checksumIndex = 0; checksumIndex < checksumOffsets.length; checksumIndex++) {
                decoder.reset();
                byte[] corrupted = discardedFrame.clone();
                corrupted[checksumOffsets[checksumIndex]] ^= 1;
                ByteBuffer corruptSource = ByteBuffer.wrap(corrupted);
                ByteBuffer corruptTarget = ByteBuffer.allocate(discardedContent.length + 1);
                IOException failure = assertThrows(IOException.class, () -> decoder.finish(corruptSource, corruptTarget));
                assertEquals(checksumIndex == 0 ? "LZ4 block checksum mismatch" : "LZ4 content checksum mismatch",
                        failure.getMessage());
                assertEquals(checksumOffsets[checksumIndex] + Integer.BYTES, corruptSource.position());
                assertEquals(checksumIndex == 0 ? 0 : discardedContent.length, corruptTarget.position());
                decoder.reset();
                ByteBuffer recovered = ByteBuffer.allocate(expected.length + 1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(expectedFrame), recovered));
                assertBufferEquals(expected, recovered);
            }
        }
    }

    /// Verifies reset abandons partial skippable sizes and payloads without skipping bytes from the next frame.
    @Test
    void resetsEveryPartialSkippableFramePhase() throws IOException {
        byte[] payload = patternedBytes(17);
        ByteBuffer skippable = ByteBuffer.allocate(2 * Integer.BYTES + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) SKIPPABLE_FRAME_MAGIC).putInt(payload.length).put(payload);
        byte[] expected = patternedBytes(31);
        byte[] frame = encode(LZ4Codec.DEFAULT.withBlockSize(LZ4BlockSize.KIB_64), expected);
        try (LZ4FrameDecoder decoder = decoder(null, 1L << 20)) {
            for (int cut = 0; cut < skippable.capacity(); cut++) {
                ByteBuffer prefix = skippable.duplicate().flip().limit(cut).asReadOnlyBuffer();
                ByteBuffer target = ByteBuffer.allocate(expected.length + 1);
                assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(prefix, target), "cut at " + cut);
                assertFalse(prefix.hasRemaining());
                assertEquals(0, target.position());

                decoder.reset();
                ByteBuffer source = ByteBuffer.wrap(frame);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                assertFalse(source.hasRemaining());
                assertBufferEquals(expected, target);
                decoder.reset();
            }
        }
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
