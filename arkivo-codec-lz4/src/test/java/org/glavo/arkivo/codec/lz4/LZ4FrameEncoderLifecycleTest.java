// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies LZ4 frame encoder size pledges, frame boundaries, and mutually exclusive lifecycle operations.
@NotNullByDefault
final class LZ4FrameEncoderLifecycleTest {
    /// Verifies declared source sizes reject excess and incomplete input without consuming the failing buffer.
    @Test
    void enforcesDeclaredSourceSize() throws IOException {
        byte[] expected = {1, 2, 3};
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.FlushableFramed encoder = LZ4Codec.DEFAULT.newEncoder(
                EncodingOptions.ofSourceSize(expected.length)
        )) {
            ByteBuffer excess = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
            IOException excessFailure = assertThrows(
                    IOException.class,
                    () -> encoder.encode(excess, ByteBuffer.allocate(32))
            );
            assertEquals("LZ4 frame input exceeds the declared source size", excessFailure.getMessage());
            assertEquals(0, excess.position());

            excess.limit(expected.length);
            encode(encoder, excess, encoded, 2);
            finish(encoder, encoded, 3);
        }
        assertArrayEquals(expected, decompress(encoded.toByteArray()));

        try (CompressionEncoder.FlushableFramed encoder = LZ4Codec.DEFAULT.newEncoder(
                EncodingOptions.ofSourceSize(expected.length)
        )) {
            encode(encoder, ByteBuffer.wrap(new byte[]{1, 2}), new ByteArrayOutputStream(), 4);
            ByteBuffer target = ByteBuffer.allocate(32);
            IOException incompleteFailure = assertThrows(IOException.class, () -> encoder.finish(target));
            assertEquals("LZ4 frame source size 2 does not match declared size 3", incompleteFailure.getMessage());
            assertEquals(0, target.position());

            encoder.reset();
            ByteArrayOutputStream retried = new ByteArrayOutputStream();
            encode(encoder, ByteBuffer.wrap(expected), retried, 2);
            finish(encoder, retried, 3);
            assertArrayEquals(expected, decompress(retried.toByteArray()));
        }
    }

    /// Verifies explicit empty frames and boundary no-ops do not introduce an implicit terminal frame.
    @Test
    void startsExplicitFramesAndPreservesBoundaryNoOps() throws IOException {
        byte[] expected = {4, 5, 6, 7};
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.FlushableFramed encoder = LZ4Codec.DEFAULT.newEncoder()) {
            encode(encoder, ByteBuffer.wrap(expected), encoded, 2);
            finishFrame(encoder, encoded, 2);

            assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
            );

            encoder.startFrame(EncodingOptions.ofSourceSize(0L));
            assertThrows(IllegalStateException.class, encoder::startFrame);
            finishFrame(encoder, encoded, 1);
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));

            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
            assertThrows(IllegalStateException.class, encoder::startFrame);
        }

        assertArrayEquals(expected, decompress(encoded.toByteArray()));
    }

    /// Verifies flush, frame finalization, and terminal finalization reject every incompatible operation.
    @Test
    void enforcesMutuallyExclusiveBoundaryOperations() throws IOException {
        byte[] expected = {8, 9, 10};
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CompressionEncoder.FlushableFramed encoder = LZ4Codec.DEFAULT.newEncoder();

        encode(encoder, ByteBuffer.wrap(expected), encoded, 3);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.flush(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::startFrame);
        flush(encoder, encoded, 2);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finishFrame(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::startFrame);
        finishFrame(encoder, encoded, 2);

        encoder.startFrame(EncodingOptions.ofSourceSize(0L));
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::startFrame);
        finish(encoder, encoded, 2);

        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::startFrame);

        encoder.close();
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(IllegalStateException.class, () -> encoder.flush(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, encoder::startFrame);

        assertArrayEquals(expected, decompress(encoded.toByteArray()));
    }

    /// Supplies every remaining source byte to an incremental encoder.
    private static void encode(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        while (source.hasRemaining()) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            CodecOutcome outcome = encoder.encode(source, target);
            drain(target, output);
            if (outcome != CodecOutcome.NEEDS_INPUT && outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new AssertionError("Unexpected LZ4 encode outcome: " + outcome);
            }
        }
    }

    /// Drains one complete nonterminal flush boundary.
    private static void flush(
            CompressionEncoder.Flushable encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.flush(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FLUSHED, outcome);
    }

    /// Drains one complete nonterminal frame boundary.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finishFrame(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);
    }

    /// Drains complete terminal encoder finalization.
    private static void finish(
            CompressionEncoder encoder,
            ByteArrayOutputStream output,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finish(target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Copies produced target bytes into the encoded stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Decodes every concatenated frame from one encoded byte array.
    private static byte[] decompress(byte[] encoded) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(encoded);
             java.io.InputStream decoder = LZ4Codec.DEFAULT.newInputStream(input)) {
            return decoder.readAllBytes();
        }
    }
}
