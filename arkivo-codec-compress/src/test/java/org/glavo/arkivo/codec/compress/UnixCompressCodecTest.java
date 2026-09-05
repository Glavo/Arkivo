// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Unix compress format discovery, LZW engines, limits, and external interoperability.
@NotNullByDefault
public final class UnixCompressCodecTest {
    /// Verifies canonical metadata and immutable format-specific configuration.
    @Test
    public void exposesFormatMetadataAndConfiguration() {
        UnixCompressFormat format = UnixCompressFormat.instance();
        assertEquals(UnixCompressFormat.NAME, format.name());
        assertEquals(List.of("z", "unix-compress"), format.aliases());
        assertEquals(List.of("Z", "taz"), format.fileExtensions());
        assertEquals(3, format.probeSize());
        assertSame(UnixCompressCodec.DEFAULT, format.defaultCodec());
        assertSame(format, UnixCompressCodec.DEFAULT.format());
        assertSame(format, CompressionFormats.require("compress"));
        assertSame(format, CompressionFormats.require("z"));
        assertSame(format, CompressionFormats.require("UNIX-COMPRESS"));
        assertEquals(16, UnixCompressCodec.DEFAULT.maximumCodeWidth());
        assertTrue(UnixCompressCodec.DEFAULT.blockMode());

        UnixCompressCodec configured = UnixCompressCodec.DEFAULT
                .withMaximumCodeWidth(12)
                .withBlockMode(false);
        assertEquals(12, configured.maximumCodeWidth());
        assertFalse(configured.blockMode());
        assertSame(configured, configured.withMaximumCodeWidth(12));
        assertSame(configured, configured.withBlockMode(false));
        assertThrows(IllegalArgumentException.class, () -> new UnixCompressCodec(8, true));
        assertThrows(IllegalArgumentException.class, () -> new UnixCompressCodec(17, true));

        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{9, 0x1f, (byte) 0x9d, (byte) 0x90, 8});
        prefix.position(1);
        int position = prefix.position();
        assertTrue(format.matches(prefix));
        assertEquals(position, prefix.position());
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0x1f})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0x1e, (byte) 0x9d})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x9c})));
        assertThrows(NullPointerException.class, () -> format.matches(null));

        UnixCompressCodec defaults = new UnixCompressCodec();
        assertEquals(UnixCompressCodec.DEFAULT_MAXIMUM_CODE_WIDTH, defaults.maximumCodeWidth());
        assertEquals(UnixCompressCodec.DEFAULT_BLOCK_MODE, defaults.blockMode());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumOutputSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, defaults.maximumMemorySize());
    }

    /// Verifies limit configurations remain immutable and compressed-size bounds handle numeric extremes.
    @Test
    public void configuresLimitsAndSizeBounds() {
        UnixCompressCodec codec = new UnixCompressCodec(12, false)
                .withMaximumOutputSize(100L)
                .withMaximumWindowSize(200L)
                .withMaximumMemorySize(300L);
        assertEquals(12, codec.maximumCodeWidth());
        assertFalse(codec.blockMode());
        assertEquals(100L, codec.maximumOutputSize());
        assertEquals(200L, codec.maximumWindowSize());
        assertEquals(300L, codec.maximumMemorySize());
        assertSame(codec, codec.withMaximumOutputSize(100L));
        assertSame(codec, codec.withMaximumWindowSize(200L));
        assertSame(codec, codec.withMaximumMemorySize(300L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumOutputSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumWindowSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> codec.withMaximumMemorySize(-2L));
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedSize(-1L));
        assertEquals(3L, codec.maxCompressedSize(0L));
        assertTrue(codec.maxCompressedSize(1L) >= 4L);
        assertEquals(Long.MAX_VALUE, codec.maxCompressedSize(Long.MAX_VALUE));
        assertThrows(NullPointerException.class, () -> codec.newEncoder(null));
    }

    /// Round-trips empty, repetitive, and random data across supported code-width and block-mode combinations.
    @Test
    public void roundTripsAcrossCodeWidthsAndModes() throws IOException {
        byte[][] inputs = {
                new byte[0],
                "TOBEORNOTTOBEORTOBEORNOT".getBytes(StandardCharsets.US_ASCII),
                "unix compress phrase ".repeat(4096).getBytes(StandardCharsets.UTF_8),
                randomBytes(180_037)
        };
        for (int maximumCodeWidth : new int[]{9, 10, 12, 16}) {
            for (boolean blockMode : new boolean[]{false, true}) {
                UnixCompressCodec codec = new UnixCompressCodec(maximumCodeWidth, blockMode);
                for (byte[] input : inputs) {
                    byte[] encoded = encode(codec, input);
                    assertTrue(codec.maxCompressedSize(input.length) >= encoded.length);
                    assertEquals(maximumCodeWidth, encoded[2] & 0x1f);
                    assertEquals(blockMode, (encoded[2] & 0x80) != 0);
                    assertArrayEquals(input, decode(codec, encoded, input.length),
                            maximumCodeWidth + "-bit blockMode=" + blockMode);
                }
            }
        }
    }

    /// Verifies byte-at-a-time input and output preserve all engine state without retaining buffers.
    @Test
    public void supportsFragmentedDirectBuffers() throws IOException {
        byte[] input = randomBytes(90_013);
        UnixCompressCodec codec = new UnixCompressCodec(16, true);
        byte[] encoded = encodeWithTinyTargets(codec, input);
        assertArrayEquals(input, decodeOneByteAtATime(codec, encoded));
    }

    /// Verifies completed engines can be reset for another stream and reject use after closure.
    @Test
    public void resetsAndClosesEngines() throws IOException {
        UnixCompressCodec codec = new UnixCompressCodec(12, true);
        byte[] first = "first stream".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second stream after reset".getBytes(StandardCharsets.UTF_8);

        CompressionEncoder encoder = codec.newEncoder(EncodingOptions.DEFAULT);
        assertArrayEquals(first, decode(codec, encodeSession(encoder, first), first.length));
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        encoder.reset();
        assertArrayEquals(second, decode(codec, encodeSession(encoder, second), second.length));
        encoder.close();
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(0)));

        CompressionDecoder decoder = codec.newDecoder();
        assertArrayEquals(first, decodeSession(decoder, encode(codec, first)));
        assertEquals(
                CodecOutcome.FINISHED,
                decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals(
                CodecOutcome.FINISHED,
                decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        decoder.reset();
        assertArrayEquals(second, decodeSession(decoder, encode(codec, second)));
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
    }

    /// Verifies null rejection, zero-capacity output backpressure, and closed-engine operations.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesBufferArgumentsAndBackpressure() throws IOException {
        UnixCompressCodec codec = new UnixCompressCodec(12, true);
        byte[] content = {(byte) 0xa5};
        byte[] encoded;

        CompressionEncoder encoder = codec.newEncoder();
        assertThrows(NullPointerException.class, () -> encoder.encode(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> encoder.encode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> encoder.finish(null));

        ByteBuffer source = ByteBuffer.wrap(content);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
        assertEquals(0, source.position());
        encoded = encodeSession(encoder, content);
        encoder.close();
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );

        CompressionDecoder decoder = codec.newDecoder();
        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        ByteBuffer compressed = ByteBuffer.wrap(encoded);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(compressed, ByteBuffer.allocate(0)));
        assertTrue(compressed.position() >= UnixCompressFormat.instance().probeSize());
        ByteBuffer target = ByteBuffer.allocate(1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(compressed, target));
        assertEquals(1, target.position());
        target.flip();
        assertEquals(Byte.toUnsignedInt(content[0]), Byte.toUnsignedInt(target.get()));

        decoder.close();
        assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies reset discards partial headers, populated dictionaries, pending final bytes, and decoder input state.
    @Test
    public void resetsEveryPartialEnginePhase() throws IOException {
        UnixCompressCodec codec = new UnixCompressCodec(12, true);
        byte[] content = randomBytes(4_097);
        byte[] encoded;

        try (CompressionEncoder encoder = codec.newEncoder()) {
            ByteBuffer headerSource = ByteBuffer.wrap(content);
            ByteBuffer partialHeader = ByteBuffer.allocate(1);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(headerSource, partialHeader));
            assertEquals(0, headerSource.position());
            assertEquals(1, partialHeader.position());
            encoder.reset();

            ByteBuffer dictionarySource = ByteBuffer.wrap(Arrays.copyOf(content, 997));
            ByteBuffer dictionaryOutput = ByteBuffer.allocate(4_096);
            assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(dictionarySource, dictionaryOutput));
            assertFalse(dictionarySource.hasRemaining());
            assertTrue(dictionaryOutput.position() > 3);
            encoder.reset();

            ByteBuffer finishingSource = ByteBuffer.wrap(new byte[]{42});
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(finishingSource, ByteBuffer.allocate(8))
            );
            assertFalse(finishingSource.hasRemaining());
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
            encoder.reset();

            encoded = encodeSession(encoder, content);
        }

        try (CompressionDecoder decoder = codec.newDecoder()) {
            ByteBuffer partialHeader = ByteBuffer.wrap(encoded, 0, 2);
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    decoder.decode(partialHeader, ByteBuffer.allocate(content.length + 1))
            );
            assertFalse(partialHeader.hasRemaining());
            decoder.reset();

            ByteBuffer partialCodes = ByteBuffer.wrap(Arrays.copyOf(encoded, encoded.length - 1));
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    decoder.decode(partialCodes, ByteBuffer.allocate(content.length + 1))
            );
            assertFalse(partialCodes.hasRemaining());
            decoder.reset();

            assertArrayEquals(content, decodeSession(decoder, encoded));
        }
    }

    /// Verifies stream finalization immediately before, at, and after LZW code-width transitions.
    @Test
    public void roundTripsAtCodeWidthTransitionBoundaries() throws IOException {
        byte[] corpus = randomBytes(1_300);
        for (TransitionRange range : new TransitionRange[]{
                new TransitionRange(10, 220, 340),
                new TransitionRange(11, 900, 1_150)
        }) {
            for (boolean blockMode : new boolean[]{false, true}) {
                UnixCompressCodec codec = new UnixCompressCodec(range.maximumCodeWidth(), blockMode);
                for (int length = range.minimumInputSize(); length <= range.maximumInputSize(); length++) {
                    byte[] input = Arrays.copyOf(corpus, length);
                    byte[] encoded = encode(codec, input);
                    String context = range.maximumCodeWidth() + "-bit blockMode=" + blockMode
                            + " length=" + length;
                    assertArrayEquals(input, decode(codec, encoded, input.length), context);
                    try (ZCompressorInputStream decoder =
                                 new ZCompressorInputStream(new ByteArrayInputStream(encoded))) {
                        assertArrayEquals(input, decoder.readAllBytes(), context);
                    }
                }
            }
        }
    }

    /// Verifies Apache Commons Compress independently decodes output from every width transition.
    @Test
    public void commonsCompressDecodesArkivoOutput() throws IOException {
        byte[] input = randomBytes(220_003);
        for (int maximumCodeWidth : new int[]{9, 10, 12, 16}) {
            byte[] encoded = encode(new UnixCompressCodec(maximumCodeWidth, true), input);
            try (ZCompressorInputStream decoder =
                         new ZCompressorInputStream(new ByteArrayInputStream(encoded))) {
                assertArrayEquals(input, decoder.readAllBytes(), maximumCodeWidth + "-bit stream");
            }
        }
    }

    /// Verifies independently packed literal and clear-code sequences.
    @Test
    public void decodesLiteralAndClearCodeStreams() throws IOException {
        byte[] literals = "ABCxyz".getBytes(StandardCharsets.US_ASCII);
        int[] literalCodes = new int[literals.length];
        for (int index = 0; index < literals.length; index++) {
            literalCodes[index] = Byte.toUnsignedInt(literals[index]);
        }
        assertArrayEquals(literals, decode(UnixCompressCodec.DEFAULT, packNineBitCodes(true, literalCodes), 32));

        int[] withClear = {'A', 'B', 256, 0, 0, 0, 0, 0, 'X', 'Y'};
        assertArrayEquals(
                "ABXY".getBytes(StandardCharsets.US_ASCII),
                decode(UnixCompressCodec.DEFAULT, packNineBitCodes(true, withClear), 32)
        );
    }

    /// Verifies the first not-yet-defined code expands to the previous phrase followed by its first byte.
    @Test
    public void decodesKwKwKSelfReferencesAcrossModes() throws IOException {
        assertArrayEquals(
                "AAA".getBytes(StandardCharsets.US_ASCII),
                decode(UnixCompressCodec.DEFAULT, packNineBitCodes(false, 'A', 256), 3)
        );
        assertArrayEquals(
                "AAA".getBytes(StandardCharsets.US_ASCII),
                decode(UnixCompressCodec.DEFAULT, packNineBitCodes(true, 'A', 257), 3)
        );
    }

    /// Verifies clear-code alignment can pause at an input boundary without losing buffered bits or output.
    @Test
    public void preservesClearCodeAlignmentAcrossSourceBoundaries() throws IOException {
        byte[] encoded = packNineBitCodes(true, 'A', 'B', 256, 0, 0, 0, 0, 0, 'X');
        int split = 7;
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = UnixCompressCodec.DEFAULT.newDecoder()) {
            ByteBuffer first = ByteBuffer.wrap(encoded, 0, split);
            ByteBuffer firstTarget = ByteBuffer.allocateDirect(8);
            assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(first, firstTarget));
            assertEquals(split, first.position());
            drain(firstTarget, decoded);

            ByteBuffer second = ByteBuffer.wrap(encoded, split, encoded.length - split).slice();
            ByteBuffer secondTarget = ByteBuffer.allocateDirect(8);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(second, secondTarget));
            assertFalse(second.hasRemaining());
            drain(secondTarget, decoded);
        }

        assertArrayEquals("ABX".getBytes(StandardCharsets.US_ASCII), decoded.toByteArray());
    }

    /// Verifies temporary input exhaustion becomes truncation when a partial terminal code reaches physical EOF.
    @Test
    public void rejectsIncompleteTerminalCode() throws IOException {
        byte[] truncated = {
                0x1f,
                (byte) 0x9d,
                (byte) 0x90,
                0x41
        };
        try (CompressionDecoder decoder = UnixCompressCodec.DEFAULT.newDecoder()) {
            ByteBuffer source = ByteBuffer.wrap(truncated);
            ByteBuffer target = ByteBuffer.allocate(8);
            assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(source, target));
            assertFalse(source.hasRemaining());
            assertEquals(0, target.position());
            assertThrows(
                    EOFException.class,
                    () -> decoder.finish(ByteBuffer.allocate(0), target)
            );
        }
    }

    /// Verifies malformed headers, invalid codes, truncation, and operation-scoped limits are rejected.
    @Test
    public void rejectsMalformedStreamsAndLimitViolations() throws IOException {
        UnixCompressCodec codec = UnixCompressCodec.DEFAULT;
        for (byte[] truncatedHeader : new byte[][]{
                new byte[0],
                new byte[]{0x1f},
                new byte[]{0x1f, (byte) 0x9d}
        }) {
            EOFException exception = assertThrows(
                    EOFException.class,
                    () -> decode(codec, truncatedHeader, 32)
            );
            assertEquals("Truncated Unix compress stream header", exception.getMessage());
        }
        for (byte[] invalidSignature : new byte[][]{
                new byte[]{0x1e, (byte) 0x9d, (byte) 0x90},
                new byte[]{0x1f, (byte) 0x9c, (byte) 0x90}
        }) {
            IOException exception = assertThrows(
                    IOException.class,
                    () -> decode(codec, invalidSignature, 32)
            );
            assertEquals("Invalid Unix compress stream signature", exception.getMessage());
        }
        IOException reservedFlags = assertThrows(
                IOException.class,
                () -> decode(codec, new byte[]{0x1f, (byte) 0x9d, (byte) 0xf0}, 32)
        );
        assertEquals("Unsupported reserved Unix compress header flags", reservedFlags.getMessage());
        IOException invalidCodeWidth = assertThrows(
                IOException.class,
                () -> decode(codec, new byte[]{0x1f, (byte) 0x9d, (byte) 0x88}, 32)
        );
        assertEquals("Invalid Unix compress maximum code width: 8", invalidCodeWidth.getMessage());
        IOException invalidFirstCode = assertThrows(
                IOException.class,
                () -> decode(codec, packNineBitCodes(true, 257), 32)
        );
        assertEquals("The first Unix compress LZW code is not a literal: 257", invalidFirstCode.getMessage());

        IOException futureCode = assertThrows(
                IOException.class,
                () -> decode(codec, packNineBitCodes(true, 'A', 258), 32)
        );
        assertEquals("Invalid Unix compress LZW code: 258", futureCode.getMessage());

        byte[] encoded = encode(codec, randomBytes(4096));
        assertThrows(
                DecompressionOutputLimitException.class,
                () -> codec.withMaximumOutputSize(1024).decompress(ByteBuffer.wrap(encoded))
        );
        assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec
                        .withMaximumOutputSize(8192)
                        .withMaximumWindowSize(65_535)
                        .decompress(ByteBuffer.wrap(encoded))
        );
        assertThrows(
                DecompressionMemoryLimitException.class,
                () -> codec
                        .withMaximumOutputSize(8192)
                        .withMaximumMemorySize(393_215)
                        .decompress(ByteBuffer.wrap(encoded))
        );
    }

    /// Encodes all input with an allocating codec convenience operation.
    private static byte[] encode(UnixCompressCodec codec, byte[] input) throws IOException {
        return bytes(codec.compress(ByteBuffer.wrap(input)));
    }

    /// Decodes all input with an explicit finite output bound.
    private static byte[] decode(UnixCompressCodec codec, byte[] input, int maximumOutputSize) throws IOException {
        return bytes(codec.withMaximumOutputSize(maximumOutputSize).decompress(ByteBuffer.wrap(input)));
    }

    /// Encodes with a one-byte direct target for every engine call.
    private static byte[] encodeWithTinyTargets(UnixCompressCodec codec, byte[] input) throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = codec.newEncoder()) {
            while (source.hasRemaining()) {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                CodecOutcome outcome = encoder.encode(source, target);
                drain(target, encoded);
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            }
            while (true) {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                CodecOutcome outcome = encoder.finish(target);
                drain(target, encoded);
                if (outcome == CodecOutcome.FINISHED) {
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            }
        }
        return encoded.toByteArray();
    }

    /// Encodes one complete stream through an existing reusable encoder.
    private static byte[] encodeSession(CompressionEncoder encoder, byte[] input) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(input);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        while (source.hasRemaining()) {
            ByteBuffer target = ByteBuffer.allocate(7);
            CodecOutcome outcome = encoder.encode(source, target);
            drain(target, encoded);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
        }
        while (true) {
            ByteBuffer target = ByteBuffer.allocate(7);
            CodecOutcome outcome = encoder.finish(target);
            drain(target, encoded);
            if (outcome == CodecOutcome.FINISHED) {
                return encoded.toByteArray();
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
        }
    }

    /// Decodes one complete stream through an existing reusable decoder.
    private static byte[] decodeSession(CompressionDecoder decoder, byte[] input) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(input);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        boolean endOfInput = false;
        while (true) {
            ByteBuffer target = ByteBuffer.allocate(7);
            CodecOutcome outcome = endOfInput
                    ? decoder.finish(source, target)
                    : decoder.decode(source, target);
            drain(target, decoded);
            if (outcome == CodecOutcome.FINISHED) {
                return decoded.toByteArray();
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
                endOfInput = true;
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            }
        }
    }

    /// Decodes one compressed source byte and one target byte per engine call.
    private static byte[] decodeOneByteAtATime(UnixCompressCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = codec.newDecoder()) {
            for (byte value : input) {
                ByteBuffer source = ByteBuffer.allocateDirect(1);
                source.put(value).flip();
                while (source.hasRemaining()) {
                    ByteBuffer target = ByteBuffer.allocateDirect(1);
                    CodecOutcome outcome = decoder.decode(source, target);
                    drain(target, decoded);
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
                }
            }
            ByteBuffer empty = ByteBuffer.allocateDirect(0);
            while (true) {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                CodecOutcome outcome = decoder.finish(empty, target);
                drain(target, decoded);
                if (outcome == CodecOutcome.FINISHED) {
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            }
        }
        return decoded.toByteArray();
    }

    /// Packs caller-supplied nine-bit codes after a minimal Unix compress header.
    private static byte[] packNineBitCodes(boolean blockMode, int... codes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x1f);
        output.write(0x9d);
        output.write(9 | (blockMode ? 0x80 : 0));
        long bits = 0L;
        int bitCount = 0;
        for (int code : codes) {
            bits |= (long) code << bitCount;
            bitCount += 9;
            while (bitCount >= Byte.SIZE) {
                output.write((int) bits);
                bits >>>= Byte.SIZE;
                bitCount -= Byte.SIZE;
            }
        }
        if (bitCount != 0) {
            output.write((int) bits);
        }
        return output.toByteArray();
    }

    /// Copies a flipped view of one target buffer into an owned byte stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        while (target.hasRemaining()) {
            output.write(target.get());
        }
    }

    /// Copies all remaining buffer bytes into a new array.
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Creates deterministic incompressible-looking test data.
    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(0x5a17_c0deL + size).nextBytes(bytes);
        return bytes;
    }

    /// Defines one dense input-length range around an LZW code-width transition.
    ///
    /// @param maximumCodeWidth maximum width encoded in the stream header
    /// @param minimumInputSize inclusive first source length
    /// @param maximumInputSize inclusive final source length
    @NotNullByDefault
    private record TransitionRange(
            int maximumCodeWidth,
            int minimumInputSize,
            int maximumInputSize
    ) {
    }
}
