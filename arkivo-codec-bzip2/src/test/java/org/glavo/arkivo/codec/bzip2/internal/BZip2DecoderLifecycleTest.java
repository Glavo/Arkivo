// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;

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
