// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests transport-independent gzip member encoder and decoder behavior.
@NotNullByDefault
public final class GzipBufferEngineTest {
    /// Shared gzip codec under test.
    private static final GzipCodec CODEC = new GzipCodec();

    /// Verifies fragmented direct buffers and exact member-boundary source positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 7, 1, false);
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

    /// Verifies that every decode operation can use fresh source and target buffers.
    @Test
    public void acceptsFreshBuffersForEveryOperation() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 1, 2, false);
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

    /// Verifies sync-flush visibility with tiny targets and complete JDK gzip interoperability.
    @Test
    public void flushProducesDecodableBoundary() throws IOException, DataFormatException {
        byte[] first = "first flushed gzip payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second finished gzip payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.FlushableFramed encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 3);
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(1);
                outcome = encoder.flush(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, outcome);
            assertArrayEquals(first, inflateFlushedBody(encoded.toByteArray()));

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 3);
            finish(encoder, encoded, 1);
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies optional header parsing and fragmented header-checksum validation.
    @Test
    public void optionalHeaderFields() throws IOException {
        byte[] content = "optional gzip buffer header".getBytes(StandardCharsets.UTF_8);
        byte[] member = gzipWithOptionalHeader(content);
        assertArrayEquals(content, decodeFragments(member, 1, 2));

        member[12] ^= 1;
        assertThrows(IOException.class, () -> decodeFragments(member, 1, 2));
    }

    /// Verifies malformed fixed headers, body truncation, reserved blocks, and both trailer integrity fields.
    @Test
    public void rejectsMalformedHeadersBodiesAndTrailers() throws IOException {
        byte[] content = "gzip defensive parsing".repeat(29).getBytes(StandardCharsets.UTF_8);
        byte[] member = gzip(content);
        byte[][] invalidHeaders = {member.clone(), member.clone(), member.clone(), member.clone()};
        invalidHeaders[0][0] = 0;
        invalidHeaders[1][1] = 0;
        invalidHeaders[2][2] = 0;
        invalidHeaders[3][3] = 0x20;
        String[] headerMessages = {
                "Invalid gzip member signature",
                "Invalid gzip member signature",
                "Unsupported gzip compression method",
                "Gzip member uses reserved flags"
        };
        for (int index = 0; index < invalidHeaders.length; index++) {
            byte[] invalidHeader = invalidHeaders[index];
            String expectedMessage = headerMessages[index];
            IOException failure = assertThrows(
                    IOException.class,
                    () -> decodeFragments(invalidHeader, 1, 2)
            );
            assertEquals(expectedMessage, failure.getMessage());
        }

        for (int length = 0; length < 10; length++) {
            byte[] truncatedHeader = Arrays.copyOf(member, length);
            EOFException failure = assertThrows(
                    EOFException.class,
                    () -> decodeFragments(truncatedHeader, 1, 2)
            );
            assertEquals("Unexpected end of gzip member header", failure.getMessage());
        }

        byte[] reservedBlock = member.clone();
        reservedBlock[10] = (byte) (reservedBlock[10] & ~0x06 | 0x06);
        IOException invalidBody = assertThrows(IOException.class, () -> decodeFragments(reservedBlock, 2, 3));
        assertEquals("Invalid gzip deflate data", invalidBody.getMessage());

        byte[] truncatedBody = Arrays.copyOf(member, member.length - 9);
        EOFException truncatedBodyFailure = assertThrows(
                EOFException.class,
                () -> decodeFragments(truncatedBody, 2, 3)
        );
        assertEquals("Unexpected end of gzip member data", truncatedBodyFailure.getMessage());

        byte[] sizeMismatch = member.clone();
        sizeMismatch[sizeMismatch.length - 4] ^= 1;
        IOException invalidSize = assertThrows(IOException.class, () -> decodeFragments(sizeMismatch, 2, 3));
        assertEquals("Gzip member size mismatch", invalidSize.getMessage());

        assertArrayEquals(content, decodeFragments(withEmptyExtraField(member), 1, 2));
    }

    /// Verifies finished decoding is idempotent and every stateful operation rejects a closed decoder.
    @Test
    public void decoderFinishedAndClosedStates() throws IOException {
        byte[] content = "finished gzip decoder".getBytes(StandardCharsets.UTF_8);
        byte[] member = gzip(content);
        CompressionDecoder decoder = CODEC.newDecoder();
        try {
            ByteBuffer source = ByteBuffer.wrap(member);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(2);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
            assertArrayEquals(content, decoded.toByteArray());
            assertEquals(CodecOutcome.FINISHED, decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
        } finally {
            decoder.close();
        }
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
    }

    /// Verifies trailer corruption, truncation, reset, and terminal lifecycle behavior.
    @Test
    public void validationAndLifecycle() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 11, 3, false);
        byte[] corrupted = encoded.clone();
        corrupted[corrupted.length - 8] ^= 1;
        assertThrows(IOException.class, () -> decode(corrupted, 2, CODEC));
        assertThrows(
                IOException.class,
                () -> decode(Arrays.copyOf(encoded, encoded.length - 1), 2, CODEC)
        );

        CompressionEncoder.FlushableFramed encoder = CODEC.newEncoder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), first, 4);
        finish(encoder, first, 1);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(1)));
        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), second, 4);
        finish(encoder, second, 1);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
    }

    /// Verifies output limiting and the flushable framed encoder contract.
    @Test
    public void framedEncoderAndOutputLimit() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 13, 5, false);
        GzipCodec exactCodec = CODEC.withMaximumOutputSize(input.length);
        GzipCodec shortCodec = CODEC.withMaximumOutputSize(input.length - 1L);

        assertArrayEquals(input, decode(encoded, 1, exactCodec));
        assertThrows(DecompressionLimitException.class, () -> decode(encoded, 1, shortCodec));
        try (CompressionEncoder.FlushableFramed encoder = CODEC.newEncoder()) {
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(32)));
            ByteBuffer terminal = ByteBuffer.allocate(32);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(terminal));
            assertEquals(0, terminal.position());
            assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(32)));
        }
    }

    /// Verifies an explicit member boundary can be followed by another explicitly configured gzip member.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void startsExplicitConcatenatedMembers() throws IOException {
        byte[] first = "first explicit gzip member".repeat(17).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second explicit gzip member".repeat(19).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.FlushableFramed encoder = CODEC.newEncoder()) {
            assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 3);
            finishFrame(encoder, encoded, 1);

            assertThrows(NullPointerException.class, () -> encoder.startFrame(null));
            encoder.startFrame(EncodingOptions.ofSourceSize(second.length));
            assertThrows(IllegalStateException.class, () -> encoder.startFrame(EncodingOptions.DEFAULT));
            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 2);
            finish(encoder, encoded, 1);
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies flush, terminal finish, and member finish cannot overlap and preserve their resumable states.
    @Test
    public void rejectsOverlappingEncoderBoundaryOperations() throws IOException {
        CompressionEncoder.FlushableFramed flushing = CODEC.newEncoder();
        try {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, flushing.flush(ByteBuffer.allocate(0)));
            assertThrows(
                    IllegalStateException.class,
                    () -> flushing.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            assertThrows(IllegalStateException.class, () -> flushing.finish(ByteBuffer.allocate(1)));
            assertThrows(IllegalStateException.class, () -> flushing.finishFrame(ByteBuffer.allocate(1)));

            CodecOutcome outcome;
            do {
                outcome = flushing.flush(ByteBuffer.allocate(1));
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, outcome);
        } finally {
            flushing.close();
        }

        CompressionEncoder.FlushableFramed finishing = CODEC.newEncoder();
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        try {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, finishing.finish(ByteBuffer.allocate(0)));
            assertThrows(
                    IllegalStateException.class,
                    () -> finishing.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            assertThrows(IllegalStateException.class, () -> finishing.flush(ByteBuffer.allocate(1)));
            assertThrows(IllegalStateException.class, () -> finishing.finishFrame(ByteBuffer.allocate(1)));
            finish(finishing, terminalOutput, 1);
            assertThrows(
                    IllegalStateException.class,
                    () -> finishing.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            assertThrows(IllegalStateException.class, () -> finishing.flush(ByteBuffer.allocate(1)));
        } finally {
            finishing.close();
        }

        CompressionEncoder.FlushableFramed finishingFrame = CODEC.newEncoder();
        ByteArrayOutputStream framedOutput = new ByteArrayOutputStream();
        try {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, finishingFrame.finishFrame(ByteBuffer.allocate(0)));
            assertThrows(
                    IllegalStateException.class,
                    () -> finishingFrame.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            assertThrows(IllegalStateException.class, () -> finishingFrame.flush(ByteBuffer.allocate(1)));
            assertThrows(IllegalStateException.class, () -> finishingFrame.finish(ByteBuffer.allocate(1)));
            finishFrame(finishingFrame, framedOutput, 1);
            assertEquals(
                    CodecOutcome.BOUNDARY_REACHED,
                    finishingFrame.finishFrame(ByteBuffer.allocate(0))
            );
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    finishingFrame.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            assertEquals(CodecOutcome.FLUSHED, finishingFrame.flush(ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.FINISHED, finishingFrame.finish(ByteBuffer.allocate(0)));
        } finally {
            finishingFrame.close();
        }
    }

    /// Verifies gzip XFL records the fastest and maximum-compression level hints.
    @Test
    public void recordsCompressionLevelInExtraFlags() throws IOException {
        assertEquals(4, Byte.toUnsignedInt(emptyMember(CODEC.withCompressionLevel(1))[8]));
        assertEquals(0, Byte.toUnsignedInt(emptyMember(CODEC)[8]));
        assertEquals(2, Byte.toUnsignedInt(emptyMember(CODEC.withCompressionLevel(9))[8]));
    }

    /// Encodes source fragments into one gzip member.
    private static byte[] encodeInFragments(
            byte[] input,
            int sourceFragmentSize,
            int targetSize,
            boolean flushEachFragment
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.FlushableFramed encoder = CODEC.newEncoder()) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int end = Math.min(input.length, offset + sourceFragmentSize);
                encodeSource(encoder, ByteBuffer.wrap(input, offset, end - offset).slice(), encoded, targetSize);
                if (flushEachFragment) {
                    CodecOutcome outcome;
                    do {
                        ByteBuffer target = ByteBuffer.allocate(targetSize);
                        outcome = encoder.flush(target);
                        drain(target, encoded);
                    } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                    assertEquals(CodecOutcome.FLUSHED, outcome);
                }
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder.FlushableFramed encoder,
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

    /// Drains member finalization with bounded target buffers.
    private static void finish(
            CompressionEncoder.FlushableFramed encoder,
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

    /// Drains a nonterminal member boundary with bounded target buffers.
    private static void finishFrame(
            CompressionEncoder.FlushableFramed encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finishFrame(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);
    }

    /// Encodes one empty member with the supplied codec configuration.
    private static byte[] emptyMember(GzipCodec codec) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.FlushableFramed encoder = codec.newEncoder()) {
            finish(encoder, encoded, 1);
        }
        return encoded.toByteArray();
    }

    /// Decodes one complete member with bounded target buffers.
    private static byte[] decode(
            byte[] encoded,
            int targetSize,
            GzipCodec codec
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer source = ByteBuffer.wrap(encoded);
        try (CompressionDecoder decoder = codec.newDecoder()) {
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

    /// Decodes a member through fresh compressed-input fragments.
    private static byte[] decodeFragments(byte[] encoded, int sourceSize, int targetSize) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(sourceSize, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                boolean endOfInput = offset + length == encoded.length;
                do {
                    ByteBuffer target = ByteBuffer.allocate(targetSize);
                    outcome = endOfInput
                            ? decoder.finish(source, target)
                            : decoder.decode(source, target);
                    drain(target, decoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                offset += source.position();
            }
            assertEquals(encoded.length, offset);
        }
        return decoded.toByteArray();
    }

    /// Inflates the raw member body currently visible after a sync flush.
    private static byte[] inflateFlushedBody(byte[] encoded) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try {
            inflater.setInput(encoded, 10, encoded.length - 10);
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

    /// Replaces a JDK gzip header with all optional fields and a valid header checksum.
    private static byte[] gzipWithOptionalHeader(byte[] content) throws IOException {
        byte[] member = gzip(content);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x1f);
        header.write(0x8b);
        header.write(8);
        header.write(0x1e);
        header.write(member, 4, 6);
        header.write(3);
        header.write(0);
        header.write(new byte[]{1, 2, 3});
        header.writeBytes("name\0".getBytes(StandardCharsets.ISO_8859_1));
        header.writeBytes("comment\0".getBytes(StandardCharsets.ISO_8859_1));

        CRC32 checksum = new CRC32();
        checksum.update(header.toByteArray());
        int headerChecksum = (int) checksum.getValue() & 0xffff;
        header.write(headerChecksum);
        header.write(headerChecksum >>> 8);
        header.write(member, 10, member.length - 10);
        return header.toByteArray();
    }

    /// Returns a member whose optional extra field is present with zero payload bytes.
    private static byte[] withEmptyExtraField(byte[] member) {
        byte[] result = new byte[member.length + 2];
        System.arraycopy(member, 0, result, 0, 10);
        result[3] |= 0x04;
        System.arraycopy(member, 10, result, 12, member.length - 10);
        return result;
    }

    /// Encodes one gzip member through the JDK implementation.
    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(content);
        }
        return output.toByteArray();
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
}
