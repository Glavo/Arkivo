// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x9c})));
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

    /// Verifies malformed headers, invalid codes, truncation, and operation-scoped limits are rejected.
    @Test
    public void rejectsMalformedStreamsAndLimitViolations() throws IOException {
        UnixCompressCodec codec = UnixCompressCodec.DEFAULT;
        assertThrows(IOException.class, () -> decode(codec, new byte[0], 32));
        assertThrows(IOException.class, () -> decode(codec, new byte[]{0x1f, (byte) 0x9d}, 32));
        assertThrows(IOException.class,
                () -> decode(codec, new byte[]{0x1e, (byte) 0x9d, (byte) 0x90}, 32));
        assertThrows(IOException.class,
                () -> decode(codec, new byte[]{0x1f, (byte) 0x9d, (byte) 0xf0}, 32));
        assertThrows(IOException.class,
                () -> decode(codec, new byte[]{0x1f, (byte) 0x9d, (byte) 0x88}, 32));
        assertThrows(IOException.class, () -> decode(codec, packNineBitCodes(true, 257), 32));

        byte[] encoded = encode(codec, randomBytes(4096));
        assertThrows(
                DecompressionOutputLimitException.class,
                () -> codec.decompress(ByteBuffer.wrap(encoded), DecompressionLimits.ofMaximumOutputSize(1024))
        );
        assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.decompress(
                        ByteBuffer.wrap(encoded),
                        new DecompressionLimits(8192, 65_535, DecompressionLimits.UNLIMITED_SIZE)
                )
        );
        assertThrows(
                DecompressionMemoryLimitException.class,
                () -> codec.decompress(
                        ByteBuffer.wrap(encoded),
                        new DecompressionLimits(8192, DecompressionLimits.UNLIMITED_SIZE, 393_215)
                )
        );
    }

    /// Encodes all input with an allocating codec convenience operation.
    private static byte[] encode(UnixCompressCodec codec, byte[] input) throws IOException {
        return bytes(codec.compress(ByteBuffer.wrap(input)));
    }

    /// Decodes all input with an explicit finite output bound.
    private static byte[] decode(UnixCompressCodec codec, byte[] input, int maximumOutputSize) throws IOException {
        return bytes(codec.decompress(ByteBuffer.wrap(input), maximumOutputSize));
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
}
