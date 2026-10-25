// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import net.jpountz.lz4.LZ4FrameInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies LZ4 frame boundaries, size pledges, and owned workspace lifecycle.
@NotNullByDefault
final class LZ4FrameEncoderLifecycleTest {
    /// Leaves pending output untouched until drained or explicitly abandoned by reset.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusesBlockWorkspaceOnlyAfterDraining(boolean abandon) throws Exception {
        int blockSize = LZ4BlockSize.KIB_64.byteSize();
        var codec = LZ4Codec.DEFAULT.withBlockSize(LZ4BlockSize.KIB_64).withBlockChecksum(true);
        byte[] first = new byte[blockSize];
        Arrays.fill(first, (byte) 0x5a);
        byte[] second = new byte[blockSize];
        new Random(42).nextBytes(second);
        try (var encoder = codec.newEncoder()) {
            var workspaceField = encoder.getClass().getDeclaredField("encodedBlock");
            workspaceField.setAccessible(true);
            var encoded = new ByteArrayOutputStream();
            ByteBuffer header = ByteBuffer.allocate(7);
            ByteBuffer source = ByteBuffer.wrap(first).asReadOnlyBuffer();
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, header));
            assertEquals(first.length, source.position());
            drain(header, encoded);
            byte[] workspace = (byte[]) workspaceField.get(encoder);
            byte[] pending = workspace.clone();
            ByteBuffer next = ByteBuffer.wrap(second).asReadOnlyBuffer();
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(next, ByteBuffer.allocate(0)));
            assertEquals(0, next.position());
            assertArrayEquals(pending, workspace);
            codec.compress(ByteBuffer.wrap(second));
            assertArrayEquals(pending, workspace);
            if (abandon) {
                encoder.reset();
                encoded.reset();
            }
            encode(encoder, next, encoded, 31);
            finish(encoder, encoded, 3);
            assertSame(workspace, workspaceField.get(encoder));
            byte[] expected;
            if (abandon) {
                expected = second;
            } else {
                expected = Arrays.copyOf(first, first.length + second.length);
                System.arraycopy(second, 0, expected, first.length, second.length);
            }
            try (var input = new LZ4FrameInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    /// Drains compressed, stored, and short final blocks without emitting unused input or output capacity.
    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void writesOnlyOccupiedBlockBytes(boolean independent, boolean checksums) throws IOException {
        int blockSize = LZ4BlockSize.KIB_64.byteSize();
        byte[] content = new byte[2 * blockSize + 7];
        new Random(42).nextBytes(content);
        Arrays.fill(content, 0, blockSize, (byte) 0x5a);
        var codec = LZ4Codec.DEFAULT.withBlockSize(LZ4BlockSize.KIB_64)
                .withIndependentBlocks(independent).withBlockChecksum(checksums).withContentChecksum(checksums);
        var collected = new ByteArrayOutputStream();
        try (var stream = codec.newOutputStream(collected)) {
            stream.write(content);
        }
        ByteBuffer expected = ByteBuffer.wrap(collected.toByteArray());
        try (var encoder = codec.newEncoder()) {
            for (int run = 0; run < 2; run++) {
                var output = new ByteArrayOutputStream();
                encode(encoder, ByteBuffer.wrap(content).asReadOnlyBuffer(), output, 31);
                finish(encoder, output, 3);
                byte[] frame = output.toByteArray();
                assertEquals(expected, ByteBuffer.wrap(frame));
                assertArrayEquals(content, decompress(frame));
                if (independent) {
                    try (var input = new LZ4FrameInputStream(new ByteArrayInputStream(frame))) {
                        assertArrayEquals(content, input.readAllBytes());
                    }
                }
                ByteBuffer records = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
                records.position(7);
                int compressedLength = records.getInt();
                assertTrue(compressedLength > 0 && compressedLength < blockSize);
                records.position(records.position() + compressedLength + (checksums ? 4 : 0));
                assertEquals(0x8000_0000 | blockSize, records.getInt());
                records.position(records.position() + blockSize + (checksums ? 4 : 0));
                assertEquals(0x8000_0000 | 7, records.getInt());
                records.position(records.position() + 7 + (checksums ? 4 : 0));
                assertEquals(0, records.getInt());
                assertEquals(checksums ? 4 : 0, records.remaining());
                encoder.reset();
            }
        }
    }

    /// Keeps linked history accurate when a reused input block contains bytes beyond a flush boundary.
    @Test
    void retainsOnlyFlushedBytesInLinkedHistory() throws IOException {
        byte[] content = new byte[65536 + 7 + 1024 + 3 + 256];
        for (int index = 0; index < content.length; index++) content[index] = (byte) (index % 251);
        var codec = LZ4Codec.DEFAULT.withBlockSize(LZ4BlockSize.KIB_64).withIndependentBlocks(false)
                .withBlockChecksum(true).withDictionary(LZ4Dictionary.rawContent(Arrays.copyOf(content, 511)));
        var encoded = new ByteArrayOutputStream();
        try (var encoder = codec.newEncoder()) {
            int offset = 0;
            for (int length : new int[]{65536, 7, 1024, 3, 256}) {
                encode(encoder, ByteBuffer.wrap(content, offset, length).asReadOnlyBuffer(), encoded, 31);
                CodecOutcome outcome;
                do {
                    ByteBuffer target = ByteBuffer.allocate(3);
                    outcome = encoder.flush(target);
                    drain(target, encoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                assertEquals(CodecOutcome.FLUSHED, outcome);
                offset += length;
            }
            finish(encoder, encoded, 3);
        }
        try (var input = codec.newInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Allocates workspaces on demand, keeps them across reset, and releases all owned arrays on close.
    @ParameterizedTest
    @EnumSource(LZ4BlockSize.class)
    void releasesBuffersOnClose(LZ4BlockSize blockSize) throws Exception {
        try (var encoder = LZ4Codec.DEFAULT.withBlockSize(blockSize)
                .withDictionary(LZ4Dictionary.rawContent(new byte[128])).newEncoder()) {
            var blockField = encoder.getClass().getDeclaredField("block");
            var dictionaryField = encoder.getClass().getDeclaredField("dictionaryHistory");
            var encodedField = encoder.getClass().getDeclaredField("encodedBlock");
            var compressorField = encoder.getClass().getDeclaredField("blockCompressor");
            blockField.setAccessible(true);
            dictionaryField.setAccessible(true);
            encodedField.setAccessible(true);
            compressorField.setAccessible(true);
            Object compressor = compressorField.get(encoder);
            var tableField = compressor.getClass().getDeclaredField("hashTable");
            tableField.setAccessible(true);
            byte[] block = (byte[]) blockField.get(encoder);
            byte[] dictionary = (byte[]) dictionaryField.get(encoder);
            assertEquals(blockSize.byteSize(), block.length);
            assertEquals(128, dictionary.length);
            assertEquals(0, ((byte[]) encodedField.get(encoder)).length);
            assertEquals(0, ((int[]) tableField.get(compressor)).length);
            var discarded = new ByteArrayOutputStream();
            encode(encoder, ByteBuffer.wrap(new byte[128]), discarded, 32);
            finish(encoder, discarded, 32);
            byte[] encoded = (byte[]) encodedField.get(encoder);
            int[] table = (int[]) tableField.get(compressor);
            assertTrue(encoded.length > 128 && encoded.length < 256);
            assertEquals(16384, table.length);
            encoder.reset();
            assertSame(block, blockField.get(encoder));
            assertSame(dictionary, dictionaryField.get(encoder));
            assertSame(encoded, encodedField.get(encoder));
            assertSame(table, tableField.get(compressor));
            encoder.close();
            assertEquals(0, ((byte[]) blockField.get(encoder)).length);
            assertEquals(0, ((byte[]) dictionaryField.get(encoder)).length);
            assertEquals(0, ((byte[]) encodedField.get(encoder)).length);
            assertEquals(0, ((int[]) tableField.get(compressor)).length);
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
