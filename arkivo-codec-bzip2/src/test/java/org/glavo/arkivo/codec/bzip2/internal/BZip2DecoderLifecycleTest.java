// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies BZip2 decoder frame boundaries, reset behavior, malformed headers, and terminal states.
@NotNullByDefault
final class BZip2DecoderLifecycleTest {
    /// Verifies concatenated frames remain separated until the caller explicitly resets the decoder.
    @Test
    void decodesConcatenatedFramesAfterExplicitReset() throws IOException {
        byte[] first = "first BZip2 frame".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second BZip2 frame".getBytes(StandardCharsets.UTF_8);
        byte[] firstEncoded = encode(first);
        byte[] secondEncoded = encode(second);
        byte[] trailing = {11, 22, 33};
        ByteBuffer source = ByteBuffer.allocate(firstEncoded.length + secondEncoded.length + trailing.length)
                .put(firstEncoded)
                .put(secondEncoded)
                .put(trailing)
                .flip();
        BZip2Decoder decoder = new BZip2Decoder();

        ByteBuffer firstTarget = ByteBuffer.allocate(first.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, firstTarget));
        assertEquals(firstEncoded.length, source.position());
        firstTarget.flip();
        byte[] firstActual = new byte[firstTarget.remaining()];
        firstTarget.get(firstActual);
        assertArrayEquals(first, firstActual);

        int boundaryPosition = source.position();
        assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(0)));
        assertEquals(boundaryPosition, source.position());

        decoder.reset();
        ByteBuffer secondTarget = ByteBuffer.allocate(second.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, secondTarget));
        assertEquals(firstEncoded.length + secondEncoded.length, source.position());
        secondTarget.flip();
        byte[] secondActual = new byte[secondTarget.remaining()];
        secondTarget.get(secondActual);
        assertArrayEquals(second, secondActual);

        byte[] remaining = new byte[source.remaining()];
        source.get(remaining);
        assertArrayEquals(trailing, remaining);
        decoder.close();
    }

    /// Verifies reset discards buffered input and every operation enforces null and closed-state contracts.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void resetsBufferedInputAndRejectsClosedOperations() throws IOException {
        BZip2Decoder decoder = new BZip2Decoder();
        ByteBuffer partialHeader = ByteBuffer.wrap(new byte[]{'B', 'Z'});
        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(partialHeader, ByteBuffer.allocate(0)));
        assertFalse(partialHeader.hasRemaining());

        decoder.reset();
        byte[] content = "state after reset".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.wrap(encode(content));
        ByteBuffer target = ByteBuffer.allocate(content.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
        target.flip();
        byte[] actual = new byte[target.remaining()];
        target.get(actual);
        assertArrayEquals(content, actual);

        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                ClosedChannelException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertThrows(
                ClosedChannelException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
    }

    /// Verifies every incomplete byte prefix can be abandoned before or after declaring physical EOF.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetsEveryIncompleteInputPrefix(boolean direct) throws IOException {
        byte[] content = new byte[513];
        Arrays.fill(content, 0, 260, (byte) 0x41);
        for (int index = 260; index < content.length; index++) {
            content[index] = (byte) (index * 37 + 11);
        }
        byte[] expected = "independent frame after abandoned BZip2 input".getBytes(StandardCharsets.UTF_8);
        byte[] valid = encode(expected);
        try (BZip2Decoder decoder = new BZip2Decoder()) {
            for (byte[] discarded : new byte[][]{encode(new byte[0]), encode(content)}) {
                for (int length = 0; length < discarded.length; length++) {
                    for (boolean declareEnd : new boolean[]{false, true}) {
                        ByteBuffer storage = direct
                                ? ByteBuffer.allocateDirect(length + 4)
                                : ByteBuffer.allocate(length + 4);
                        storage.position(2);
                        storage.put(discarded, 0, length).flip().position(2);
                        ByteBuffer source = storage.asReadOnlyBuffer();
                        source.mark();
                        ByteBuffer target = ByteBuffer.allocate(content.length + 1);
                        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(source, target));
                        assertEquals(length + 2, source.position());
                        assertEquals(length + 2, source.limit());
                        if (declareEnd) {
                            assertThrows(EOFException.class, () -> decoder.finish(source, target));
                        }
                        decoder.reset();
                        assertFreshFrame(decoder, valid, expected, direct);
                        assertEquals(length + 2, source.position());
                        source.reset();
                        assertEquals(2, source.position());
                        decoder.reset();
                    }
                }
            }
        }
    }

    /// Verifies reset clears pending run expansion and both block and combined checksum failures.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetsPendingRunsAndChecksumFailures(boolean direct) throws IOException {
        byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 0x41);
        byte[] encoded = encode(content);
        byte[] expected = "different bytes after resetting a run".getBytes(StandardCharsets.UTF_8);
        byte[] valid = encode(expected);
        try (BZip2Decoder decoder = new BZip2Decoder()) {
            for (int capacity : new int[]{0, 1, 3, 4, 5, 258, 1023}) {
                ByteBuffer source = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
                ByteBuffer target = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
                assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(source, target));
                assertEquals(capacity, target.position());
                for (int index = 0; index < capacity; index++) {
                    assertEquals((byte) 0x41, target.get(index));
                }
                int consumed = source.position();
                decoder.reset();
                assertFreshFrame(decoder, valid, expected, direct);
                assertEquals(consumed, source.position());
                assertEquals(capacity, target.position());
                decoder.reset();
            }
            for (int corruption : new int[]{10, encoded.length - 2}) {
                byte[] corrupted = encoded.clone();
                corrupted[corruption] ^= 1;
                ByteBuffer source = ByteBuffer.wrap(corrupted).asReadOnlyBuffer();
                ByteBuffer target = direct
                        ? ByteBuffer.allocateDirect(content.length + 1)
                        : ByteBuffer.allocate(content.length + 1);
                IOException failure = assertThrows(IOException.class, () -> decoder.finish(source, target));
                assertEquals(corruption == 10 ? "BZip2 block CRC mismatch" : "BZip2 combined CRC mismatch",
                        failure.getMessage());
                assertEquals(content.length, target.position());
                decoder.reset();
                assertFreshFrame(decoder, valid, expected, direct);
                decoder.reset();
            }
        }
    }

    /// Verifies a reset engine produces only the new frame and preserves inaccessible target bytes and its mark.
    private static void assertFreshFrame(
            BZip2Decoder decoder,
            byte @Unmodifiable [] encoded,
            byte @Unmodifiable [] expected,
            boolean direct
    ) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        ByteBuffer target = direct
                ? ByteBuffer.allocateDirect(expected.length + 4)
                : ByteBuffer.allocate(expected.length + 4);
        while (target.hasRemaining()) {
            target.put((byte) 0x5a);
        }
        target.position(2).limit(expected.length + 3).mark();
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
        assertEquals(encoded.length, source.position());
        assertEquals(expected.length + 2, target.position());
        assertEquals(expected.length + 3, target.limit());
        target.reset();
        assertEquals(2, target.position());
        byte[] actual = new byte[expected.length];
        target.get(actual);
        assertArrayEquals(expected, actual);
        target.clear();
        assertEquals((byte) 0x5a, target.get(0));
        assertEquals((byte) 0x5a, target.get(1));
        assertEquals((byte) 0x5a, target.get(expected.length + 2));
        assertEquals((byte) 0x5a, target.get(expected.length + 3));
    }

    /// Verifies each stream-header component, block-size bound, and following marker is validated independently.
    @Test
    void rejectsMalformedHeadersAndBlockMarkers() {
        assertFailure(new byte[]{'X'}, "Invalid BZip2 stream header");
        assertFailure(new byte[]{'B', 'X'}, "Invalid BZip2 stream header");
        assertFailure(new byte[]{'B', 'Z', 'X'}, "Invalid BZip2 stream header");
        assertFailure(new byte[]{'B', 'Z', 'h', '0'}, "Invalid BZip2 block size: 0");
        assertFailure(new byte[]{'B', 'Z', 'h', ':'}, "Invalid BZip2 block size: 10");
        assertFailure(
                new byte[]{'B', 'Z', 'h', '1', 0, 0, 0, 0, 0, 0},
                "Invalid BZip2 block marker"
        );

        EOFException truncated = assertThrows(
                EOFException.class,
                () -> new BZip2Decoder().finish(
                        ByteBuffer.wrap(new byte[]{'B', 'Z', 'h'}),
                        ByteBuffer.allocate(0)
                )
        );
        assertEquals("Truncated BZip2 stream", truncated.getMessage());
    }

    /// Compresses one independent frame with the public codec entry point.
    private static byte[] encode(byte[] content) throws IOException {
        ByteBuffer encoded = new BZip2Codec().compress(ByteBuffer.wrap(content));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Verifies decoding one malformed byte sequence reports the expected checked failure.
    private static void assertFailure(byte[] encoded, String message) {
        IOException exception = assertThrows(
                IOException.class,
                () -> new BZip2Decoder().finish(ByteBuffer.wrap(encoded), ByteBuffer.allocate(1))
        );
        assertEquals(message, exception.getMessage());
    }
}
