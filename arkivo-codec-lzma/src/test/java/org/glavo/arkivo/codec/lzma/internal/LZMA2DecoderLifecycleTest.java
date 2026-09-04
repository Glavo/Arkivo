// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw LZMA2 control parsing, buffer progress, malformed chunks, and decoder lifecycle behavior.
@NotNullByDefault
final class LZMA2DecoderLifecycleTest {
    /// Dictionary size shared by direct decoder instances and generated compressed streams.
    private static final int DICTIONARY_SIZE = 1 << 20;

    /// Verifies stored chunks respect target backpressure and preserve trailing compressed input.
    @Test
    void decodesStoredChunksWithExactBufferProgress() throws IOException {
        byte[] first = {1, 2, 3, 4};
        byte[] second = {5, 6, 7};
        byte[] encoded = storedStream(first, second);
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 2);
        withTail[encoded.length] = 91;
        withTail[encoded.length + 1] = 92;
        ByteBuffer source = ByteBuffer.wrap(withTail);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        LZMA2Decoder decoder = new LZMA2Decoder(DICTIONARY_SIZE);

        assertEquals(
                CodecOutcome.NEEDS_INPUT,
                decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(source, ByteBuffer.allocate(0)));
        assertEquals(3, source.position());

        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(2);
            outcome = decoder.decode(source, target);
            drain(target, decoded);
        } while (outcome != CodecOutcome.FINISHED);

        assertArrayEquals(concatenate(first, second), decoded.toByteArray());
        assertEquals(encoded.length, source.position());
        assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(1)));
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, ByteBuffer.allocate(1)));
        assertEquals(encoded.length, source.position());

        decoder.reset();
        ByteBuffer repeatedSource = ByteBuffer.wrap(encoded);
        ByteBuffer repeatedTarget = ByteBuffer.allocate(first.length + second.length);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(repeatedSource, repeatedTarget));
        repeatedTarget.flip();
        byte[] repeated = new byte[repeatedTarget.remaining()];
        repeatedTarget.get(repeated);
        assertArrayEquals(concatenate(first, second), repeated);

        decoder.close();
        decoder.close();
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

    /// Verifies end-of-input reports truncation from control, header, stored-body, and compressed-body phases.
    @Test
    void rejectsTruncationInEveryInputCollectionPhase() {
        byte[][] truncatedStreams = {
                {},
                {0x01},
                {0x01, 0x00},
                {0x01, 0x00, 0x01, 0x41},
                {(byte) 0xe0, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00}
        };

        for (byte[] encoded : truncatedStreams) {
            IOException failure = assertThrows(IOException.class, () -> decode(encoded));
            assertInstanceOf(EOFException.class, failure);
            assertEquals("Truncated LZMA2 stream", failure.getMessage());
        }
    }

    /// Verifies control bytes enforce initial dictionary reset and compressed-property requirements.
    @Test
    void rejectsInvalidControlAndPropertyTransitions() {
        assertFailureMessage(new byte[]{0x03}, "Invalid LZMA2 control byte: 3");
        assertFailureMessage(
                new byte[]{0x02},
                "LZMA2 stream does not begin with a dictionary reset"
        );
        assertFailureMessage(
                new byte[]{(byte) 0xc0},
                "LZMA2 stream does not begin with a dictionary reset"
        );
        assertFailureMessage(
                new byte[]{(byte) 0xe0, 0, 0, 0, 0, (byte) 0xff},
                "Invalid LZMA2 property byte"
        );
        assertFailureMessage(
                new byte[]{0x01, 0, 0, 0x41, (byte) 0x80, 0, 0, 0, 0},
                "LZMA2 compressed chunk omits required properties"
        );
    }

    /// Verifies compressed chunks reject shorter, longer, and non-canonical declared extents.
    @Test
    void rejectsInconsistentCompressedChunkSizes() throws IOException {
        byte[] expected = new byte[4_096];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index & 3);
        }
        byte[] encoded = compress(expected);
        CompressedChunk chunk = firstCompressedChunk(encoded);
        assertEquals(expected.length, chunk.uncompressedSize());

        byte[] oversizedOutput = encoded.clone();
        writeUncompressedSize(oversizedOutput, chunk.uncompressedSize() + 1);
        assertFailureMessage(
                oversizedOutput,
                "Truncated LZMA range-coded stream"
        );

        byte[] undersizedOutput = encoded.clone();
        writeUncompressedSize(undersizedOutput, chunk.uncompressedSize() - 1);
        assertFailureMessage(
                undersizedOutput,
                "LZMA match exceeds the expected output size"
        );

        byte[] extendedInput = insertCompressedByte(encoded, chunk, (byte) 0x5a);
        assertFailureMessage(
                extendedInput,
                "LZMA2 chunk has a non-canonical range-coder ending"
        );
    }

    /// Verifies a continuation chunk can reset LZMA probability state while retaining the shared dictionary.
    @Test
    void decodesExplicitStateResetContinuationChunk() throws IOException {
        byte[] first = patternedBytes(4_097, 3);
        byte[] second = patternedBytes(5_123, 71);
        byte[] firstStream = compress(first);
        byte[] secondStream = compress(second);
        CompressedChunk firstChunk = firstCompressedChunk(firstStream);
        CompressedChunk secondChunk = firstCompressedChunk(secondStream);
        requireSingleChunkStream(firstStream, firstChunk);
        requireSingleChunkStream(secondStream, secondChunk);

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        int firstBodyEnd = firstChunk.bodyOffset() + firstChunk.compressedSize();
        combined.write(firstStream, 0, firstBodyEnd);
        combined.write((Byte.toUnsignedInt(secondStream[0]) & 0x1f) | 0xa0);
        combined.write(secondStream, 1, 4);
        combined.write(secondStream, secondChunk.bodyOffset(), secondChunk.compressedSize());
        combined.write(0);

        assertArrayEquals(concatenate(first, second), decode(combined.toByteArray()));
    }

    /// Verifies null arguments are rejected before decoder state can change.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesNullArguments() {
        LZMA2Decoder decoder = new LZMA2Decoder(DICTIONARY_SIZE);

        assertThrows(
                NullPointerException.class,
                () -> decoder.decode(null, ByteBuffer.allocate(1))
        );
        assertThrows(
                NullPointerException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), null)
        );
        assertThrows(
                NullPointerException.class,
                () -> decoder.finish(null, ByteBuffer.allocate(1))
        );
        assertThrows(
                NullPointerException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), null)
        );
        decoder.close();
    }

    /// Decodes one complete raw LZMA2 stream through small direct target buffers.
    private static byte[] decode(byte[] encoded) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (LZMA2Decoder decoder = new LZMA2Decoder(DICTIONARY_SIZE)) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(7);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }
        return decoded.toByteArray();
    }

    /// Asserts decoding fails with the exact stable diagnostic message.
    private static void assertFailureMessage(byte[] encoded, String expectedMessage) {
        IOException failure = assertThrows(IOException.class, () -> decode(encoded));
        assertEquals(expectedMessage, failure.getMessage());
    }

    /// Encodes source bytes as one valid raw LZMA2 stream.
    private static byte[] compress(byte[] source) throws IOException {
        ByteBuffer encoded = LZMA2Codec.DEFAULT.compress(ByteBuffer.wrap(source));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /// Creates a raw LZMA2 stream containing two stored chunks and an end marker.
    private static byte[] storedStream(byte[] first, byte[] second) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeStoredChunk(output, 0x01, first);
        writeStoredChunk(output, 0x02, second);
        output.write(0);
        return output.toByteArray();
    }

    /// Writes one nonempty stored LZMA2 chunk with the selected control byte.
    private static void writeStoredChunk(ByteArrayOutputStream output, int control, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalArgumentException("Stored chunk length must be between 1 and 65536");
        }
        int encodedSize = bytes.length - 1;
        output.write(control);
        output.write(encodedSize >>> 8);
        output.write(encodedSize);
        output.writeBytes(bytes);
    }

    /// Returns metadata for the first compressed chunk in a generated stream.
    private static CompressedChunk firstCompressedChunk(byte[] encoded) {
        int control = Byte.toUnsignedInt(encoded[0]);
        if (control < 0xe0) {
            throw new AssertionError("Expected the generated stream to begin with a compressed reset chunk");
        }
        int uncompressedSize = ((control & 0x1f) << 16) + unsignedShort(encoded, 1) + 1;
        int compressedSize = unsignedShort(encoded, 3) + 1;
        int bodyOffset = 6;
        assertTrue(bodyOffset + compressedSize < encoded.length);
        return new CompressedChunk(uncompressedSize, compressedSize, bodyOffset);
    }

    /// Requires an encoded stream to contain exactly one compressed chunk followed by its end marker.
    private static void requireSingleChunkStream(byte[] encoded, CompressedChunk chunk) {
        int endOffset = chunk.bodyOffset() + chunk.compressedSize();
        if (endOffset + 1 != encoded.length || encoded[endOffset] != 0) {
            throw new AssertionError("Expected exactly one compressed LZMA2 chunk");
        }
    }

    /// Updates the first compressed chunk's declared uncompressed size.
    private static void writeUncompressedSize(byte[] encoded, int size) {
        int encodedSize = size - 1;
        encoded[0] = (byte) ((Byte.toUnsignedInt(encoded[0]) & 0xe0) | (encodedSize >>> 16));
        encoded[1] = (byte) (encodedSize >>> 8);
        encoded[2] = (byte) encodedSize;
    }

    /// Inserts one byte at the end of the compressed body and extends its declared compressed size.
    private static byte[] insertCompressedByte(byte[] encoded, CompressedChunk chunk, byte value) {
        int bodyEnd = chunk.bodyOffset() + chunk.compressedSize();
        byte[] extended = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, extended, 0, bodyEnd);
        extended[bodyEnd] = value;
        System.arraycopy(encoded, bodyEnd, extended, bodyEnd + 1, encoded.length - bodyEnd);
        int encodedSize = chunk.compressedSize();
        extended[3] = (byte) (encodedSize >>> 8);
        extended[4] = (byte) encodedSize;
        return extended;
    }

    /// Reads one unsigned big-endian 16-bit field from an array.
    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) << 8 | Byte.toUnsignedInt(bytes[offset + 1]);
    }

    /// Concatenates two byte arrays in encounter order.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates deterministic compressible bytes from a size and seed.
    private static byte[] patternedBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (seed + (index & 15) * 7 + index / 257);
        }
        return bytes;
    }

    /// Copies produced target bytes into the decoded stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Describes the first compressed chunk in a generated raw LZMA2 stream.
    ///
    /// @param uncompressedSize declared output size
    /// @param compressedSize declared compressed body size
    /// @param bodyOffset byte offset of the compressed body
    @NotNullByDefault
    private record CompressedChunk(int uncompressedSize, int compressedSize, int bodyOffset) {
    }
}
