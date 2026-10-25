// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import net.jpountz.lz4.LZ4Factory;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies raw LZ4 block encoder reset, backpressure, and terminal-state behavior.
@NotNullByDefault
final class LZ4BlockEncoderLifecycleTest {
    /// Ignores spare input capacity, retains output capacity without emitting it, and survives output backpressure.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 11, 12, 8191, 8192, 8193, 65537})
    void respectsOccupiedBufferPrefixes(int size) throws Exception {
        var outputField = LZ4BlockEncoder.class.getDeclaredField("output");
        outputField.setAccessible(true);
        for (boolean periodic : new boolean[]{false, true}) {
            byte[] expected = new byte[size];
            new Random(42).nextBytes(expected);
            if (periodic) {
                for (int index = 7; index < size; index++) expected[index] = expected[index % 7];
            }
            byte[] inputBytes = Arrays.copyOf(expected, size + 17);
            Arrays.fill(inputBytes, size, inputBytes.length, (byte) 0x5a);
            try (var encoder = new LZ4BlockEncoder(size + 8192)) {
                ByteBuffer input = ByteBuffer.wrap(inputBytes);
                input.limit(Math.min(size, 1));
                encoder.encode(input, ByteBuffer.allocate(0));
                input.limit(size);
                encoder.encode(input, ByteBuffer.allocate(0));
                assertEquals(size, input.position());
                Arrays.fill(inputBytes, (byte) 0x6b);

                assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
                ByteBuffer pending = (ByteBuffer) outputField.get(encoder);
                assertEquals(LZ4BlockCompression.maxCompressedLength(size), pending.capacity());
                assertEquals(0, pending.position());
                assertThrows(ReadOnlyBufferException.class,
                        () -> encoder.finish(ByteBuffer.allocate(1).asReadOnlyBuffer()));
                assertEquals(0, pending.position());

                var encoded = new ByteArrayOutputStream();
                CodecOutcome outcome;
                do {
                    byte[] targetBytes = new byte[1031];
                    Arrays.fill(targetBytes, (byte) 0x7c);
                    ByteBuffer target = ByteBuffer.wrap(targetBytes);
                    target.position(3).limit(1027);
                    outcome = encoder.finish(target);
                    encoded.write(targetBytes, 3, target.position() - 3);
                    for (int index = 0; index < 3; index++) assertEquals((byte) 0x7c, targetBytes[index]);
                    for (int index = target.position(); index < targetBytes.length; index++) {
                        assertEquals((byte) 0x7c, targetBytes[index]);
                    }
                } while (outcome != CodecOutcome.FINISHED);
                assertArrayEquals(expected, LZ4Factory.safeInstance().safeDecompressor()
                        .decompress(encoded.toByteArray(), size));
                assertEquals(0, ((ByteBuffer) outputField.get(encoder)).capacity());
            }
        }
    }

    /// Transfers the owned array without trimming it and leaves the accumulator ready for reuse.
    @Test
    void transfersCollectedStorageWithoutCopying() throws Exception {
        var accumulator = new OwnedByteAccumulator(16384);
        var bytesField = OwnedByteAccumulator.class.getDeclaredField("bytes");
        bytesField.setAccessible(true);
        accumulator.append(ByteBuffer.wrap(new byte[]{1, 2, 3}), "Overflow");
        byte[] storage = (byte[]) bytesField.get(accumulator);
        assertEquals(3, accumulator.size());
        assertSame(storage, accumulator.takeStorage());
        assertEquals(0, accumulator.size());
        assertEquals(0, ((byte[]) bytesField.get(accumulator)).length);
        accumulator.append(ByteBuffer.wrap(new byte[]{4}), "Overflow");
        assertArrayEquals(new byte[]{4}, accumulator.takeBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOf(storage, 3));
        assertEquals(0, accumulator.size());
    }

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
