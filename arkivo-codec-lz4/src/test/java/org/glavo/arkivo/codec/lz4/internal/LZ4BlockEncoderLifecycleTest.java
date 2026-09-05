// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies raw LZ4 block encoder reset, backpressure, and terminal-state behavior.
@NotNullByDefault
final class LZ4BlockEncoderLifecycleTest {
    /// Verifies reset discards both collected input and encoded bytes awaiting target space.
    @Test
    void resetsCollectedInputAndPendingOutput() throws IOException {
        byte[] expected = "replacement raw LZ4 block".getBytes(StandardCharsets.UTF_8);
        byte[] compressed;

        try (LZ4BlockEncoder encoder = new LZ4BlockEncoder(64)) {
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.wrap(new byte[]{1, 2, 3}), ByteBuffer.allocate(0))
            );
            encoder.reset();

            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.wrap(new byte[32]), ByteBuffer.allocate(0))
            );
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(1)));
            encoder.reset();

            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.wrap(expected), ByteBuffer.allocate(0))
            );
            compressed = finish(encoder);
        }

        ByteBuffer decoded = new LZ4BlockCodec()
                .withMaximumBlockSize(64)
                .withMaximumOutputSize(64)
                .decompress(ByteBuffer.wrap(compressed));
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Verifies null arguments, invalid operation ordering, stable completion, and permanent closure.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsAndTerminalStates() throws IOException {
        LZ4BlockEncoder encoder = new LZ4BlockEncoder(16);
        assertThrows(NullPointerException.class, () -> encoder.encode(null, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> encoder.encode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> encoder.finish(null));

        encoder.encode(ByteBuffer.wrap(new byte[]{42}), ByteBuffer.allocate(0));
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        IllegalStateException drainingFailure = assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("Cannot encode while raw LZ4 encoder state is DRAINING", drainingFailure.getMessage());

        finish(encoder);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        IllegalStateException finishedFailure = assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        assertEquals("Cannot encode while raw LZ4 encoder state is FINISHED", finishedFailure.getMessage());

        encoder.close();
        encoder.close();
        assertClosedFailure(encoder::reset);
        assertClosedFailure(() -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
        assertClosedFailure(() -> encoder.finish(ByteBuffer.allocate(0)));
    }

    /// Drains one encoder through small target buffers and returns its complete raw block.
    private static byte[] finish(LZ4BlockEncoder encoder) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(3);
            outcome = encoder.finish(target);
            target.flip();
            byte[] bytes = new byte[target.remaining()];
            target.get(bytes);
            output.writeBytes(bytes);
        } while (outcome != CodecOutcome.FINISHED);
        return output.toByteArray();
    }

    /// Verifies one operation fails with the raw encoder's stable closed-state diagnostic.
    private static void assertClosedFailure(ThrowingOperation operation) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, operation::run);
        assertEquals("Raw LZ4 encoder is closed", exception.getMessage());
    }

    /// Represents an encoder operation that may report an I/O failure.
    @FunctionalInterface
    @NotNullByDefault
    private interface ThrowingOperation {
        /// Runs the operation.
        void run() throws IOException;
    }
}
