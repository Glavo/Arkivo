// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.zstd.internal.ZstdDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests transport-independent Zstandard encoder and decoder behavior.
@NotNullByDefault
public final class ZstdBufferEngineTest {
    /// Shared Zstandard codec under test.
    private static final ZstdCodec CODEC = new ZstdCodec();

    /// Reuses only a matching contiguous window and releases it when resized, replaced by a large window, or closed.
    @Test
    void boundsHistoryReuseAcrossFrames() throws Exception {
        var field = ZstdDecoder.class.getDeclaredField("blockDecoder");
        field.setAccessible(true);
        try (var decoder = new ZstdDecoder(null, CompressionCodec.UNKNOWN_SIZE, false, true)) {
            ByteBuffer target = ByteBuffer.allocate(8);
            byte[] small = singleBlockFrame(10, new byte[]{25, 0, 0, 1, 2, 3});
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target));
            Object cached = java.util.Objects.requireNonNull(field.get(decoder));
            decoder.reset();
            target.clear();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target));
            assertSame(cached, field.get(decoder));

            // Abandon a partially delivered raw block, then start another frame in the same storage.
            decoder.reset();
            assertEquals(CodecOutcome.NEEDS_INPUT,
                    decoder.decode(ByteBuffer.wrap(Arrays.copyOf(small, small.length - 1)), target.clear()));
            assertSame(cached, field.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target.clear()));
            assertSame(cached, field.get(decoder));
            assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), target.flip());

            // An independent frame cannot refer to bytes retained physically from the previous one.
            byte[] unavailableMatch = singleBlockFrame(10, new byte[]{61, 0, 0, 0, 1, 84, 0, 2, 0, 4});
            try (var reference = new ZstdInputStream(new ByteArrayInputStream(unavailableMatch))) {
                assertThrows(IOException.class, reference::readAllBytes);
            }
            decoder.reset();
            target.clear();
            assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(unavailableMatch), target));
            assertEquals(0, target.position());
            assertSame(cached, field.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target.clear()));
            assertSame(cached, field.get(decoder));

            decoder.reset();
            ByteBuffer skippable = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x184d2a50).putInt(1).put((byte) 99).flip();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(skippable, target.clear()));
            assertEquals(0, target.position());
            assertSame(cached, field.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target.clear()));
            assertSame(cached, field.get(decoder));

            decoder.reset();
            byte[] larger = singleBlockFrame(11, new byte[]{1, 0, 0});
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(larger), target.clear()));
            assertNotSame(cached, field.get(decoder));
            assertNotNull(field.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED,
                    decoder.finish(ByteBuffer.wrap(singleBlockFrame(24, new byte[]{1, 0, 0})), target.clear()));
            assertNotNull(field.get(decoder), "the largest contiguous window remains reusable");
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED,
                    decoder.finish(ByteBuffer.wrap(singleBlockFrame(25, new byte[]{1, 0, 0})), target.clear()));
            assertNull(field.get(decoder), "segmented windows must not remain cached");

            decoder.reset();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target.clear()));
            assertNotNull(field.get(decoder));
            decoder.close();
            decoder.close();
            assertNull(field.get(decoder));
        }
    }

    /// Wraps an already encoded final block in a frame with an explicit power-of-two window and no checksum.
    private static byte[] singleBlockFrame(int windowLog, byte[] block) {
        return ByteBuffer.allocate(6 + block.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xfd2fb528).put((byte) 0).put((byte) ((windowLog - 10) << 3)).put(block).array();
    }

    /// Clears Huffman tables after completion, abandoned output and checksum failure before reusing a window.
    @ParameterizedTest
    @CsvSource({"complete", "pending", "checksum"})
    void resetsReusedBlockEntropy(String stop) throws Exception {
        byte[] body = new byte[8192];
        new java.util.Random(315).nextBytes(body);
        for (int index = 0; index < body.length; index++) body[index] &= 3;
        byte[] encoded;
        try (var encoder = new ZstdCompressCtx()) {
            encoded = encoder.setLevel(3).setChecksum(true).compress(body);
        }
        var blockField = ZstdDecoder.class.getDeclaredField("blockDecoder");
        blockField.setAccessible(true);
        var huffmanField = Class.forName("org.glavo.arkivo.codec.zstd.internal.ZstdBlockDecoder")
                .getDeclaredField("huffmanTable");
        huffmanField.setAccessible(true);
        try (var decoder = new ZstdDecoder(null, CompressionCodec.UNKNOWN_SIZE, false, true)) {
            ByteBuffer target = ByteBuffer.allocate(body.length + 1);
            if (stop.equals("checksum")) {
                byte[] corrupt = encoded.clone();
                corrupt[corrupt.length - 1] ^= 1;
                assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(corrupt), target));
                assertEquals(body.length, target.position());
            } else {
                if (stop.equals("pending")) target.limit(1);
                assertEquals(stop.equals("pending") ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED,
                        decoder.finish(ByteBuffer.wrap(encoded), target));
            }
            Object cached = java.util.Objects.requireNonNull(blockField.get(decoder));
            if (!stop.equals("complete")) assertNotNull(huffmanField.get(cached));
            decoder.reset();
            assertSame(cached, blockField.get(decoder));
            assertNull(huffmanField.get(cached));
            // One treeless literal cannot reuse the preceding frame's Huffman table.
            byte[] treeless = singleBlockFrame(13, new byte[]{45, 0, 0, 19, 64, 0, 1, 0});
            target.clear();
            assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(treeless), target));
            assertEquals(0, target.position());
            assertSame(cached, blockField.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(encoded), target.clear()));
            assertEquals(ByteBuffer.wrap(body), target.flip());
            assertSame(cached, blockField.get(decoder));
        }
    }

    /// Releases a borrowed literal buffer when an empty compressed block finishes the frame.
    @Test
    void releasesEmptyFinalBlockView() throws Exception {
        byte[] encoded = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xfd2fb528).put((byte) 0).put((byte) 0)
                .put(new byte[]{44, 0, 0, 24, 4, 5, 6, 0})
                .put(new byte[]{21, 0, 0, 0, 0}).array();
        try (var reference = new ZstdInputStream(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(new byte[]{4, 5, 6}, reference.readAllBytes());
        }
        var field = ZstdDecoder.class.getDeclaredField("output");
        field.setAccessible(true);
        try (var decoder = new ZstdDecoder(null, CompressionCodec.UNKNOWN_SIZE, false, true)) {
            Object emptyOutput = java.util.Objects.requireNonNull(field.get(decoder));
            ByteBuffer target = ByteBuffer.allocate(4);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(encoded), target));
            assertEquals(3, target.position());
            assertArrayEquals(new byte[]{4, 5, 6, 0}, target.array());
            assertSame(emptyOutput, field.get(decoder));
        }
    }

    /// Reuses input storage across mixed blocks and resets, grows it only when needed, and releases it on close.
    @Test
    void reusesPayloadAcrossMixedBlocks() throws Exception {
        byte[][] bodies = {testData(64), new byte[]{7}, new byte[]{1, 2, 3},
                new byte[]{24, 4, 5, 6, 0}, testData(128), new byte[0]};
        int[] types = {0, 1, 0, 2, 0, 0};
        int[] sizes = {64, 17, 3, 5, 128, 0};
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(bodies[0]);
        byte[] repeated = new byte[17];
        Arrays.fill(repeated, (byte) 7);
        expected.writeBytes(repeated);
        expected.writeBytes(bodies[2]);
        expected.writeBytes(new byte[]{4, 5, 6});
        expected.writeBytes(bodies[4]);
        byte[] header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xfd2fb528).put((byte) 0).put((byte) 0).array();
        var field = ZstdDecoder.class.getDeclaredField("payload");
        field.setAccessible(true);
        for (boolean direct : new boolean[]{false, true}) {
            try (var decoder = new ZstdDecoder(null, CompressionCodec.UNKNOWN_SIZE, false, true)) {
                assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(ByteBuffer.wrap(header), ByteBuffer.allocate(1)));
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.writeBytes(header);
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                @Nullable Object previous = null;
                for (int i = 0; i < bodies.length; i++) {
                    boolean last = i == bodies.length - 1;
                    int blockHeader = sizes[i] << 3 | types[i] << 1 | (last ? 1 : 0);
                    ByteBuffer input = direct ? ByteBuffer.allocateDirect(3 + bodies[i].length)
                            : ByteBuffer.allocate(3 + bodies[i].length);
                    input.put((byte) blockHeader).put((byte) (blockHeader >>> 8)).put((byte) (blockHeader >>> 16));
                    input.put(bodies[i]).flip();
                    byte[] encoded = new byte[input.remaining()];
                    input.duplicate().get(encoded);
                    frame.writeBytes(encoded);
                    ByteBuffer source = input.asReadOnlyBuffer();
                    CodecOutcome outcome;
                    do {
                        ByteBuffer target = ByteBuffer.allocate(7);
                        outcome = decoder.decode(source, target);
                        drain(target, actual);
                    } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                    assertFalse(source.hasRemaining());
                    assertEquals(last ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_INPUT, outcome);
                    if (i == 0 || i == 4) {
                        assertNotSame(previous, field.get(decoder));
                        previous = field.get(decoder);
                        assertNotNull(previous);
                    } else {
                        assertSame(previous, field.get(decoder));
                    }
                }
                assertArrayEquals(expected.toByteArray(), actual.toByteArray());
                try (var reference = new ZstdInputStream(new ByteArrayInputStream(frame.toByteArray()))) {
                    assertArrayEquals(expected.toByteArray(), reference.readAllBytes());
                }
                for (boolean close : new boolean[]{false, true}) {
                    decoder.reset();
                    ByteBuffer incomplete = ByteBuffer.allocate(10).put(header).put(new byte[]{32, 0, 0, 1}).flip();
                    assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(incomplete, ByteBuffer.allocate(8)));
                    assertNotNull(field.get(decoder));
                    if (close) decoder.close();
                    else decoder.reset();
                    if (close) assertNull(field.get(decoder));
                    else assertSame(previous, field.get(decoder));
                }
            }
        }
    }

    /// Reuses a maximum-size input workspace without treating stale bytes as part of a later frame.
    @Test
    void boundsPayloadReuseAcrossFrames() throws Exception {
        var field = ZstdDecoder.class.getDeclaredField("payload");
        field.setAccessible(true);
        byte[] rawBlock = new byte[128 * 1024 + 3];
        rawBlock[0] = 1;
        rawBlock[2] = 0x10;
        Arrays.fill(rawBlock, 3, rawBlock.length, (byte) 0xff);
        try (var decoder = new ZstdDecoder(null, CompressionCodec.UNKNOWN_SIZE, false, true)) {
            ByteBuffer target = ByteBuffer.allocate(128 * 1024 + 1);
            byte[] small = singleBlockFrame(10, new byte[]{45, 0, 0, 24, 4, 5, 6, 0});
            assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(small), target));
            Object initial = java.util.Objects.requireNonNull(field.get(decoder));
            decoder.reset();
            assertEquals(CodecOutcome.FINISHED,
                    decoder.finish(ByteBuffer.wrap(singleBlockFrame(17, rawBlock)), target.clear()));
            byte[] cached = (byte[]) java.util.Objects.requireNonNull(field.get(decoder));
            assertNotSame(initial, cached);
            assertEquals(128 * 1024, cached.length);
            assertEquals(ByteBuffer.wrap(rawBlock, 3, rawBlock.length - 3), target.flip());

            decoder.reset();
            byte[] oversized = singleBlockFrame(18, new byte[]{9, 0, 0x10});
            target.clear();
            assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(oversized), target));
            assertEquals(0, target.position());
            assertSame(cached, field.get(decoder));
            for (int attempt = 0; attempt < 3; attempt++) {
                decoder.reset();
                assertSame(cached, field.get(decoder));
                ByteBuffer source = ByteBuffer.allocateDirect(small.length + 1)
                        .put(small).put((byte) 99).flip().asReadOnlyBuffer();
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target.clear()));
                assertEquals(1, source.remaining());
                assertEquals(99, source.get());
                assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), target.flip());
                assertSame(cached, field.get(decoder));

                decoder.reset();
                // The declared payload omits the sequence count; the cached trailing zero must not supply it.
                byte[] malformed = singleBlockFrame(10, new byte[]{37, 0, 0, 24, 4, 5, 6});
                target.clear();
                assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(malformed), target));
                assertEquals(0, target.position());
                assertSame(cached, field.get(decoder));

                decoder.reset();
                ByteBuffer skippable = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x184d2a50).putInt(1).put((byte) 42).flip();
                assertEquals(CodecOutcome.FINISHED, decoder.finish(skippable, target.clear()));
                assertEquals(0, target.position());
                assertSame(cached, field.get(decoder));
            }
            decoder.close();
            decoder.close();
            assertNull(field.get(decoder));
        }
    }

    /// Releases the frame engine and its parsed dictionary configuration when the buffer encoder closes.
    @Test
    void releasesEncoderWorkspaceOnClose() throws Exception {
        ZstdDictionary dictionary = ZstdDictionary.rawContent(new byte[1024]);
        ZstdCodec codec = ZstdCodec.builder().dictionary(dictionary)
                .longDistanceMatching(true).longDistanceHashLog(20).build();
        try (var encoder = codec.newEncoder()) {
            for (String name : new String[]{"encoder", "initialParameters", "defaultFrameParameters", "parameters"}) {
                var field = encoder.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertNotNull(field.get(encoder));
            }
            encoder.close();
            encoder.close();
            for (String name : new String[]{"encoder", "initialParameters", "defaultFrameParameters", "parameters"}) {
                var field = encoder.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(encoder), name);
            }
            assertThrows(IllegalStateException.class, encoder::reset);
        }
        assertArrayEquals(new byte[1024], dictionary.bytes());
        try (var encoder = codec.newEncoder()) {
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(128)));
        }
    }

    /// Drops the decoder's parsed dictionary copy without changing the reusable dictionary configuration.
    @Test
    void releasesDecoderDictionaryOnClose() throws Exception {
        ZstdDictionary dictionary = ZstdDictionary.rawContent(new byte[1024]);
        ZstdCodec codec = CODEC.withDictionary(dictionary);
        try (var decoder = codec.newDecoder()) {
            var field = decoder.getClass().getDeclaredField("dictionary");
            field.setAccessible(true);
            Object context = java.util.Objects.requireNonNull(field.get(decoder));
            var content = context.getClass().getDeclaredField("content");
            content.setAccessible(true);
            assertEquals(1024, ((byte[]) content.get(context)).length);
            decoder.close();
            decoder.close();
            Object released = java.util.Objects.requireNonNull(field.get(decoder));
            assertNotSame(context, released);
            assertEquals(0, ((byte[]) content.get(released)).length);
            assertThrows(IllegalStateException.class, decoder::reset);
        }
        assertArrayEquals(new byte[1024], dictionary.bytes());
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(new byte[]{42}));
        ByteBuffer decoded = ByteBuffer.allocate(1);
        codec.decompress(encoded, decoded);
        assertEquals((byte) 42, decoded.get(0));
    }

    /// Checks decoded compressed-block sizes against the frame window, including the exact boundary.
    @ParameterizedTest
    @CsvSource({"10, -1", "10, 0", "10, 1", "12, -1", "12, 0", "12, 1",
            "16, -1", "16, 0", "16, 1"})
    void enforcesDecodedBlockWindowLimit(int windowLog, int excess) throws IOException {
        int windowSize = 1 << windowLog;
        byte[] expected = new byte[windowSize + excess];
        Arrays.fill(expected, (byte) 'x');
        byte[] encoded;
        try (var encoder = new ZstdCompressCtx()) {
            encoded = encoder.setContentSize(false).setChecksum(false).setWindowLog(17).compress(expected);
        }
        assertEquals(0, encoded[4], "frame must have a window descriptor and no content size");
        assertEquals(2, (encoded[6] >>> 1) & 3, "fixture must contain a compressed block");
        assertTrue(encoded.length < windowSize, "encoded block itself must fit the window");
        encoded[5] = (byte) ((windowLog - 10) << 3);

        try (var reference = new ZstdInputStream(new ByteArrayInputStream(encoded))) {
            if (excess > 0) {
                assertThrows(IOException.class, reference::readAllBytes);
            } else {
                assertArrayEquals(expected, reference.readAllBytes());
            }
        }
        for (boolean direct : new boolean[]{false, true}) {
            ByteBuffer storage = direct ? ByteBuffer.allocateDirect(encoded.length) : ByteBuffer.allocate(encoded.length);
            ByteBuffer source = storage.put(encoded).flip().asReadOnlyBuffer();
            ByteBuffer target = direct ? ByteBuffer.allocateDirect(expected.length) : ByteBuffer.allocate(expected.length);
            try (var decoder = CODEC.newDecoder()) {
                if (excess > 0) {
                    IOException failure = assertThrows(IOException.class, () -> decoder.finish(source, target));
                    assertEquals("Decoded Zstandard block exceeds the frame block-size limit", failure.getMessage());
                    assertEquals(0, target.position(), "invalid block must not be published");
                } else {
                    assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                    byte[] actual = new byte[target.position()];
                    target.flip().get(actual);
                    assertArrayEquals(expected, actual);
                }
                assertEquals(encoded.length, source.position());
            }
        }
    }

    /// Drains borrowed block output without retaining consumed input, including after reset.
    @ParameterizedTest
    @CsvSource({"false, false, false, true", "false, true, false, true",
            "true, false, false, true", "true, true, false, true",
            "false, false, true, true", "false, true, true, true",
            "true, false, true, true", "true, true, true, true",
            "false, false, false, false", "true, false, false, false",
            "false, false, true, false", "true, false, true, false"})
    void drainsCompressedBlocksBeforeReusingTheirStorage(boolean checksum, boolean direct, boolean repeated,
                                                        boolean readOnly)
            throws IOException {
        int blockSize = 128 * 1024;
        byte[] expected = new byte[3 * blockSize + 37];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = repeated && i < 2 * blockSize ? (byte) 'A' : (byte) (i % 251 + i / blockSize);
        }
        byte[] encoded;
        try (var encoder = new ZstdCompressCtx()) {
            encoded = encoder.setContentSize(false).setChecksum(checksum).setWindowLog(17).compress(expected);
        }
        assertEquals(checksum ? 4 : 0, encoded[4]);
        int firstBlockHeader = (encoded[6] & 0xff) | (encoded[7] & 0xff) << 8 | (encoded[8] & 0xff) << 16;
        assertEquals(2, (firstBlockHeader >>> 1) & 3);
        assertEquals(0, firstBlockHeader & 1, "fixture must contain multiple blocks");
        int firstBlockEnd = 9 + (firstBlockHeader >>> 3);
        if (repeated) {
            int rleBlocks = 0;
            for (int offset = 6;;) {
                int header = (encoded[offset] & 0xff) | (encoded[offset + 1] & 0xff) << 8
                        | (encoded[offset + 2] & 0xff) << 16;
                boolean rle = ((header >>> 1) & 3) == 1;
                if (rle) rleBlocks++;
                offset += 3 + (rle ? 1 : header >>> 3);
                if ((header & 1) != 0) break;
            }
            assertTrue(rleBlocks > 0, "fixture must reuse output storage across RLE and compressed blocks");
        }

        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(encoded.length + 6)
                : ByteBuffer.allocate(encoded.length + 6);
        ByteBuffer writable = storage.position(5).slice().put(encoded).put((byte) 99).flip();
        ByteBuffer source = readOnly ? writable.asReadOnlyBuffer() : writable;
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(31) : ByteBuffer.allocate(31);
        try (var decoder = CODEC.newDecoder()) {
            for (int pass = 0; pass < 2; pass++) {
                writable.rewind().put(encoded).put((byte) 99).flip();
                source.rewind();
                byte[] actual = new byte[expected.length];
                target.clear().limit(1);
                assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(source, target));
                assertEquals(firstBlockEnd, source.position());
                for (int index = 0; index < source.position(); index++) writable.put(index, (byte) 0xa5);
                target.flip().get(actual, 0, 1);
                target.clear().limit(0);
                assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(source, target));
                assertEquals(firstBlockEnd, source.position(), "pending output must not consume another block");

                int offset = 1;
                CodecOutcome outcome;
                do {
                    target.clear();
                    int consumed = source.position();
                    outcome = decoder.decode(source, target);
                    for (int index = consumed; index < source.position(); index++) writable.put(index, (byte) 0xa5);
                    int count = target.position();
                    assertTrue(count > 0 || outcome == CodecOutcome.FINISHED);
                    target.flip().get(actual, offset, count);
                    offset += count;
                    assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
                } while (outcome != CodecOutcome.FINISHED);
                assertEquals(expected.length, offset);
                assertArrayEquals(expected, actual);
                assertEquals(encoded.length, source.position());
                assertEquals(99, source.get());
                decoder.reset();
            }
        }
    }

    /// Verifies fragmented direct buffers and exact trailing-input preservation.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] input = testData(180_003);
        byte[] encoded = encode(input, CODEC.newEncoder(), 113, 1, false);
        byte[] tail = {9, 8, 7, 6};
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

    /// Verifies fresh source buffers, flush boundaries, and checksum validation.
    @Test
    public void fragmentedInputAndFlush() throws IOException {
        byte[] input = testData(96_127);
        ZstdCodec codec = CODEC.withFrameChecksum(true);
        byte[] encoded = encode(input, codec.newEncoder(), 5, 2, true);
        assertArrayEquals(input, decodeFragments(encoded, codec, 3, 2));
    }

    /// Verifies pledged size, magicless framing, and worker configuration on the buffer API.
    @Test
    public void exposesAdvancedEncoderConfiguration() throws IOException {
        byte[] input = testData(700_123);
        ZstdCodec codec = ZstdCodec.builder()
                .workerCount(2)
                .frameChecksum(true)
                .frameFormat(ZstdFrameFormat.MAGICLESS)
                .build();
        byte[] encoded = encode(
                input,
                codec.newEncoder(EncodingOptions.ofSourceSize(input.length)),
                8191,
                17,
                false
        );
        assertArrayEquals(input, decode(encoded, codec, 19));
    }

    /// Rejects further encoding after a source-size failure and restores a clean, deterministic frame on reset.
    @ParameterizedTest
    @CsvSource({"0, finish", "2, finish", "0, finishFrame", "2, finishFrame", "0, encode", "2, encode"})
    void sourceSizeFailureRequiresReset(int workers, String operation) throws IOException {
        ZstdCodec codec = ZstdCodec.builder().workerCount(workers).frameChecksum(true).build();
        byte[] complete = {1, 2, 3, 4};
        for (boolean flushed : new boolean[]{false, true}) {
            try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(complete.length))) {
                ByteBuffer output = ByteBuffer.allocate(128);
                assertEquals(CodecOutcome.NEEDS_INPUT,
                        encoder.encode(ByteBuffer.wrap(new byte[]{1, 2, 3}), output));
                if (flushed) {
                    assertEquals(CodecOutcome.FLUSHED, encoder.flush(output));
                }
                ByteBuffer excess = ByteBuffer.wrap(new byte[]{0, 4, 5, 0});
                excess.position(1).limit(3);
                IOException failure = assertThrows(IOException.class, () -> {
                    switch (operation) {
                        case "finish" -> encoder.finish(output);
                        case "finishFrame" -> encoder.finishFrame(output);
                        case "encode" -> encoder.encode(excess, output);
                        default -> throw new AssertionError(operation);
                    }
                });
                assertTrue(failure.getMessage().contains("pledged"));
                assertEquals(1, excess.position());
                assertEquals(3, excess.limit());

                int failedPosition = output.position();
                ByteBuffer remaining = ByteBuffer.wrap(new byte[]{4});
                for (int retry = 0; retry < 2; retry++) {
                    assertThrows(IllegalStateException.class, () -> encoder.finish(output));
                    assertThrows(IllegalStateException.class, () -> encoder.finishFrame(output));
                    assertThrows(IllegalStateException.class, () -> encoder.flush(output));
                    assertThrows(IllegalStateException.class, () -> encoder.encode(remaining, output));
                    assertThrows(IllegalStateException.class, encoder::startFrame);
                    assertEquals(failedPosition, output.position());
                    assertEquals(0, remaining.position());
                }

                encoder.reset();
                ByteArrayOutputStream recovered = new ByteArrayOutputStream();
                encodeSource(encoder, ByteBuffer.wrap(complete), recovered, 1);
                finish(encoder, recovered, 1);
                byte[] fresh = encode(complete, codec.newEncoder(EncodingOptions.ofSourceSize(complete.length)),
                        complete.length, 1, false);
                assertArrayEquals(fresh, recovered.toByteArray());
                try (var reference = new ZstdInputStream(new ByteArrayInputStream(recovered.toByteArray()))) {
                    assertArrayEquals(complete, reference.readAllBytes());
                }
            }
        }
    }

    /// Verifies skippable frames leave following bytes unconsumed.
    @Test
    public void skippableFrameBoundary() throws IOException {
        byte[] payload = testData(257);
        byte[] tail = {1, 2, 3};
        ByteBuffer skippable = ByteBuffer.allocate(8 + payload.length + tail.length);
        skippable.putInt(Integer.reverseBytes(0x184d2a50));
        skippable.putInt(Integer.reverseBytes(payload.length));
        skippable.put(payload).put(tail).flip();
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            assertEquals(
                    CodecOutcome.FINISHED,
                    decoder.decode(skippable, ByteBuffer.allocate(1))
            );
        }
        assertEquals(8 + payload.length, skippable.position());
    }

    /// Verifies truncated input, checksum corruption, and maximum-window enforcement.
    @Test
    public void rejectsMalformedAndOversizedFrames() throws IOException {
        byte[] input = testData(24_019);
        ZstdCodec codec = CODEC.withFrameChecksum(true);
        byte[] encoded = encode(input, codec.newEncoder(), 257, 11, false);

        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(IOException.class, () -> decode(truncated, codec, 17));
        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decode(corrupt, codec, 17));

        assertThrows(IOException.class, () -> decode(encoded, codec.withMaximumWindowSize(1024L), 17));
    }

    /// Verifies checksum validation retains decoded bytes and accounts for the consumed checksum on failure.
    @Test
    public void checksumFailurePreservesDecodedOutputAndInputProgress() throws IOException {
        byte[] input = testData(32_017);
        ZstdCodec codec = CODEC.withFrameChecksum(true);
        byte[] encoded = encode(input, codec.newEncoder(), 257, 11, false);
        encoded[encoded.length - 1] ^= 1;
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteBuffer target = ByteBuffer.allocate(input.length);

        try (CompressionDecoder decoder = codec.newDecoder()) {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(source, target));
            assertEquals(input.length, target.position());
            assertEquals(encoded.length - Integer.BYTES, source.position());

            ByteBuffer failureTarget = ByteBuffer.allocate(1);
            IOException exception = assertThrows(IOException.class, () -> decoder.finish(source, failureTarget));
            assertEquals("Zstandard frame checksum mismatch", exception.getMessage());
            assertEquals(encoded.length, source.position());
            assertEquals(0, failureTarget.position());
        }

        target.flip();
        byte[] restored = new byte[target.remaining()];
        target.get(restored);
        assertArrayEquals(input, restored);
    }

    /// Verifies a decoder can be reused after completion and rejects every operation after closure.
    @Test
    public void decoderCompletionResetAndClosure() throws IOException {
        byte[] input = testData(2_017);
        byte[] encoded = encode(input, CODEC.newEncoder(), 127, 7, false);
        CompressionDecoder.FramedDictionaryAware<ZstdDictionary, ZstdDictionaryRequest> decoder = CODEC.newDecoder();
        assertThrows(IllegalStateException.class, decoder::dictionaryRequest);

        ByteBuffer firstSource = ByteBuffer.wrap(encoded);
        ByteBuffer firstTarget = ByteBuffer.allocate(input.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(firstSource, firstTarget));
        assertEquals(encoded.length, firstSource.position());
        assertEquals(input.length, firstTarget.position());
        assertThrows(IllegalStateException.class, decoder::dictionaryRequest);

        ByteBuffer trailingSource = ByteBuffer.wrap(new byte[]{1, 2, 3});
        ByteBuffer terminalTarget = ByteBuffer.allocate(1);
        assertEquals(CodecOutcome.FINISHED, decoder.decode(trailingSource, terminalTarget));
        assertEquals(0, trailingSource.position());
        assertEquals(0, terminalTarget.position());

        decoder.reset();
        ByteBuffer secondSource = ByteBuffer.wrap(encoded);
        ByteBuffer secondTarget = ByteBuffer.allocate(input.length + 1);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(secondSource, secondTarget));
        assertArrayEquals(
                Arrays.copyOf(firstTarget.array(), firstTarget.position()),
                Arrays.copyOf(secondTarget.array(), secondTarget.position())
        );

        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies Channel adaptation exposes one exact frame boundary before physical EOF.
    @Test
    public void channelAdapterEndsAtPhysicalInput() throws IOException {
        byte[] input = testData(20_007);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CODEC.compress(
                Channels.newChannel(new java.io.ByteArrayInputStream(input)),
                Channels.newChannel(encoded)
        );
        byte[] frame = encoded.toByteArray();
        assertEquals(frame.length, CODEC.frameCompressedSize(ByteBuffer.wrap(frame)));

        try (DecompressingReadableByteChannel.Framed decoder = CODEC.newReadableByteChannel(
                Channels.newChannel(new java.io.ByteArrayInputStream(frame))
        )) {
            ByteBuffer target = ByteBuffer.allocate(input.length);
            CodecResult boundary = decoder.decodeFrame(target);
            assertEquals(CodecResult.Status.FRAME_FINISHED, boundary.status());
            assertEquals(frame.length, decoder.inputBytes());
            assertArrayEquals(input, Arrays.copyOf(target.array(), target.position()));
            assertEquals(
                    CodecResult.Status.END_OF_INPUT,
                    decoder.decode(ByteBuffer.allocate(1)).status()
            );
        }
    }

    /// Verifies encoder reset and deterministic output.
    @Test
    public void resetProducesDeterministicOutput() throws IOException {
        byte[] input = testData(20_003);
        CompressionEncoder encoder = CODEC.newEncoder(
                EncodingOptions.ofSourceSize(input.length)
        );
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), first, 13);
        finish(encoder, first, 7);
        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), second, 13);
        finish(encoder, second, 7);
        encoder.close();
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        assertThrows(IllegalStateException.class, encoder::reset);
    }

    /// Verifies explicit frame boundaries preserve the encoder and terminal finish emits no empty frame.
    @Test
    public void framedEncoderStartsFollowingFramesLazily() throws IOException {
        byte[] first = testData(10_003);
        byte[] second = testData(17_009);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Framed encoder = CODEC.newEncoder(
                EncodingOptions.ofSourceSize(first.length)
        )) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 11);
            finishFrame(encoder, encoded, 3);
            int firstEnd = encoded.size();

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 13);
            finishFrame(encoder, encoded, 5);
            int secondEnd = encoded.size();

            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            finish(encoder, encoded, 7);
            assertEquals(secondEnd, encoded.size());

            byte[] frames = encoded.toByteArray();
            assertEquals(firstEnd, CODEC.frameCompressedSize(ByteBuffer.wrap(frames)));
            assertEquals(
                    secondEnd - firstEnd,
                    CODEC.frameCompressedSize(ByteBuffer.wrap(frames, firstEnd, secondEnd - firstEnd).slice())
            );
            ZstdStandardFrameInfo firstInfo = (ZstdStandardFrameInfo) CODEC.frameInfo(
                    ByteBuffer.wrap(frames, 0, firstEnd)
            );
            ZstdStandardFrameInfo secondInfo = (ZstdStandardFrameInfo) CODEC.frameInfo(
                    ByteBuffer.wrap(frames, firstEnd, secondEnd - firstEnd).slice()
            );
            assertEquals(first.length, firstInfo.contentSize());
            assertEquals(EncodingOptions.DEFAULT.sourceSize(), secondInfo.contentSize());
            assertArrayEquals(first, decode(Arrays.copyOfRange(frames, 0, firstEnd), CODEC, 2));
            assertArrayEquals(second, decode(Arrays.copyOfRange(frames, firstEnd, secondEnd), CODEC, 2));
        }
    }

    /// Verifies that a following frame receives independent exact source-size metadata.
    @Test
    public void framedEncoderAcceptsOptionsForEachExplicitFrame() throws IOException {
        byte[] first = testData(1_003);
        byte[] second = testData(2_009);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Framed encoder = CODEC.newEncoder(
                EncodingOptions.ofSourceSize(first.length)
        )) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 9);
            finishFrame(encoder, encoded, 4);
            int firstEnd = encoded.size();

            encoder.startFrame(EncodingOptions.ofSourceSize(second.length));
            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 7);
            finishFrame(encoder, encoded, 3);
            int secondEnd = encoded.size();
            finish(encoder, encoded, 5);

            byte[] frames = encoded.toByteArray();
            ZstdStandardFrameInfo firstInfo = (ZstdStandardFrameInfo) CODEC.frameInfo(
                    ByteBuffer.wrap(frames, 0, firstEnd)
            );
            ZstdStandardFrameInfo secondInfo = (ZstdStandardFrameInfo) CODEC.frameInfo(
                    ByteBuffer.wrap(frames, firstEnd, secondEnd - firstEnd).slice()
            );
            assertEquals(first.length, firstInfo.contentSize());
            assertEquals(second.length, secondInfo.contentSize());
        }
    }

    /// Encodes input fragments into one frame.
    private static byte[] encode(
            byte[] input,
            CompressionEncoder.FlushableFramed encoder,
            int sourceFragmentSize,
            int targetSize,
            boolean flushEachFragment
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (encoder) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int end = Math.min(input.length, offset + sourceFragmentSize);
                encodeSource(encoder, ByteBuffer.wrap(Arrays.copyOfRange(input, offset, end)), encoded, targetSize);
                if (flushEachFragment) {
                    flush(encoder, encoded, targetSize);
                }
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertEquals(false, source.hasRemaining());
    }

    /// Drains one encoder flush operation.
    private static void flush(CompressionEncoder.FlushableFramed encoder, ByteArrayOutputStream encoded, int targetSize)
            throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.flush(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FLUSHED, outcome);
    }

    /// Drains one non-terminal Zstandard frame-finalization operation.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
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

    /// Drains one terminal encoder-finalization operation.
    private static void finish(CompressionEncoder encoder, ByteArrayOutputStream encoded, int targetSize)
            throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finish(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Decodes one frame from one complete source buffer using the codec's limits.
    private static byte[] decode(byte[] encoded, ZstdCodec codec, int targetSize) throws IOException {
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
        }
        return decoded.toByteArray();
    }

    /// Decodes one frame while replacing the source buffer after each input request.
    private static byte[] decodeFragments(
            byte[] encoded,
            ZstdCodec codec,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = codec.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(sourceFragmentSize, encoded.length - offset);
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
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.FINISHED);
            }
            assertEquals(encoded.length, offset);
        }
        return decoded.toByteArray();
    }

    /// Copies produced target bytes into a byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic test content of the requested size.
    private static byte[] testData(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 67) ^ (index >>> 5));
        }
        return data;
    }
}
