// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies LZ4 frame encoder size pledges and owned workspace release.
@NotNullByDefault
final class LZ4FrameEncoderLifecycleTest {
    /// Keeps the block allocation across reset but releases owned block and dictionary storage on close.
    @ParameterizedTest
    @EnumSource(LZ4BlockSize.class)
    void releasesBuffersOnClose(LZ4BlockSize blockSize) throws Exception {
        try (var encoder = LZ4Codec.DEFAULT.withBlockSize(blockSize)
                .withDictionary(LZ4Dictionary.rawContent(new byte[128])).newEncoder()) {
            var blockField = encoder.getClass().getDeclaredField("block");
            var dictionaryField = encoder.getClass().getDeclaredField("dictionaryHistory");
            blockField.setAccessible(true);
            dictionaryField.setAccessible(true);
            byte[] block = (byte[]) blockField.get(encoder);
            byte[] dictionary = (byte[]) dictionaryField.get(encoder);
            assertEquals(blockSize.byteSize(), block.length);
            assertEquals(128, dictionary.length);
            encoder.reset();
            assertSame(block, blockField.get(encoder));
            assertSame(dictionary, dictionaryField.get(encoder));
            encoder.close();
            assertEquals(0, ((byte[]) blockField.get(encoder)).length);
            assertEquals(0, ((byte[]) dictionaryField.get(encoder)).length);
            encoder.close();
            assertEquals(0, ((byte[]) blockField.get(encoder)).length);
            assertThrows(IllegalStateException.class, encoder::reset);
        }
    }

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
