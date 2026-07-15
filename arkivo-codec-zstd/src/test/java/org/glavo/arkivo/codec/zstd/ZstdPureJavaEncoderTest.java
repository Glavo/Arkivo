// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pure Java Zstandard encoder output against an independent native decoder.
@NotNullByDefault
public final class ZstdPureJavaEncoderTest {
    /// Verifies repetitive input is represented by a compressed block and decoded by zstd-jni.
    @Test
    public void nativeDecoderReadsCompressedBlock() throws IOException {
        byte[] input = (
                "pure Java Zstandard sequence encoding 0123456789abcdef;"
        ).repeat(4_096).getBytes(StandardCharsets.UTF_8);
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, input, CodecOptions.EMPTY);

        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
        assertTrue(compressed.length < input.length / 8);

        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(compressed));
        int blockHeader = Byte.toUnsignedInt(compressed[info.headerSize()])
                | Byte.toUnsignedInt(compressed[info.headerSize() + 1]) << 8
                | Byte.toUnsignedInt(compressed[info.headerSize() + 2]) << 16;
        assertEquals(2, blockHeader >>> 1 & 3);
    }

    /// Verifies one block can encode more than 127 sequences with varying length and offset symbols.
    @Test
    public void nativeDecoderReadsMultipleVaryingSequences() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        Random random = new Random(0x6d75_6c74_6973_6571L);
        for (int index = 0; index < 160; index++) {
            byte[] repeated = new byte[48 + index % 13 * 7];
            random.nextBytes(repeated);
            input.write(repeated);
            input.write(repeated);

            byte[] separator = new byte[3 + index % 11];
            random.nextBytes(separator);
            input.write(separator);
        }
        byte[] expected = input.toByteArray();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);

        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
        assertTrue(firstBlockSequenceCount(codec, compressed) >= 128);
        assertTrue(compressed.length < expected.length * 3 / 4);
    }

    /// Verifies a compact Huffman literal section uses one reverse bitstream.
    @Test
    public void nativeDecoderReadsSingleStreamHuffmanLiterals() throws IOException {
        byte[] expected = huffmanFixture(6, 128, 0x51a9_2026L);
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);
        LiteralSectionInfo literals = firstLiteralSection(codec, compressed);

        assertEquals(2, literals.type());
        assertEquals(1, literals.streamCount());
        assertTrue(literals.regeneratedSize() > 0 && literals.regeneratedSize() <= 1023);
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
    }

    /// Verifies a large Huffman literal section uses four independently sized streams.
    @Test
    public void nativeDecoderReadsFourStreamHuffmanLiterals() throws IOException {
        byte[] expected = huffmanFixture(100, 160, 0x4f75_7220_2026L);
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);
        LiteralSectionInfo literals = firstLiteralSection(codec, compressed);

        assertEquals(2, literals.type());
        assertEquals(4, literals.streamCount());
        assertTrue(literals.regeneratedSize() > 1023);
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
        assertTrue(compressed.length < expected.length * 3 / 4);
    }

    /// Verifies compressed Huffman weights cover the complete byte alphabet.
    @Test
    public void nativeDecoderReadsFullAlphabetHuffmanWeights() throws IOException {
        byte[] expected = fullAlphabetHuffmanFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);
        LiteralSectionInfo literals = firstLiteralSection(codec, compressed);

        assertEquals(2, literals.type());
        assertEquals(4, literals.streamCount());
        assertTrue(literals.regeneratedSize() > 1023);
        assertTrue(firstHuffmanTableHeader(codec, compressed) < 128);
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
        assertTrue(compressed.length < expected.length);
    }

    /// Verifies a later block reuses the frame-local Huffman table through treeless literals.
    @Test
    public void nativeDecoderReadsTreelessHuffmanLiterals() throws IOException {
        byte[] expected = treelessHuffmanFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);

        assertArrayEquals(new int[]{2, 3}, compressedBlockLiteralTypes(codec, compressed));
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
        assertTrue(compressed.length < expected.length * 3 / 4);
    }

    /// Verifies later blocks can repeat all adaptive sequence-code tables.
    @Test
    public void nativeDecoderReadsRepeatedSequenceTables() throws IOException {
        byte[] expected = repeatedSequenceTableFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);
        int[] modes = compressedBlockSequenceModes(codec, compressed);

        assertEquals(2, modes.length);
        assertTrue(hasSequenceMode(modes[0], 2));
        assertTrue(hasSequenceMode(modes[1], 3));
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
        assertTrue(compressed.length < expected.length * 3 / 4);
    }

    /// Verifies randomized copied ranges across single- and multi-block frames with both decoders.
    @Test
    public void randomizedMultiSequenceBlocksInteroperate() throws IOException {
        Random random = new Random(0x5e71_6f6e_2026L);
        ZstdCodec codec = new ZstdCodec();
        for (int iteration = 0; iteration < 24; iteration++) {
            int size = 4_096 + random.nextInt(260_000);
            byte[] expected = new byte[size];
            random.nextBytes(expected);
            int position = 256;
            while (position + 4 < expected.length) {
                int distance = 16 + random.nextInt(Math.min(position, 8_192) - 15);
                int copyLength = Math.min(
                        4 + random.nextInt(509),
                        expected.length - position
                );
                System.arraycopy(expected, position - distance, expected, position, copyLength);
                position += copyLength + 3 + random.nextInt(97);
            }

            byte[] compressed = compress(codec, expected, CodecOptions.EMPTY);
            assertArrayEquals(expected, Zstd.decompress(compressed, expected.length), "iteration=" + iteration);
            assertArrayEquals(
                    expected,
                    codec.decompress(ByteBuffer.wrap(compressed), expected.length).array(),
                    "iteration=" + iteration
            );
        }
    }

    /// Verifies checksummed output over random and boundary-sized inputs with the native decoder.
    @Test
    public void nativeDecoderReadsChecksummedBoundarySizes() throws IOException {
        int[] sizes = {0, 1, 3, 4, 31, 32, 255, 256, 1_023, 1_024, 131_071, 131_072, 131_073};
        Random random = new Random(0x5a17_2026L);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        for (int size : sizes) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            byte[] compressed = compress(new ZstdCodec(), input, options);
            assertArrayEquals(input, Zstd.decompress(compressed, size), "size=" + size);
        }
    }

    /// Verifies pledged content-size encodings on both sides of compact header boundaries.
    @Test
    public void nativeDecoderReadsPledgedContentSizes() throws IOException {
        int[] sizes = {0, 1, 255, 256, 65_791, 65_792, 131_072, 131_073};
        Random random = new Random(0x73d0_2026L);
        ZstdCodec codec = new ZstdCodec();
        for (int size : sizes) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            CodecOptions options = CodecOptions.builder()
                    .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) size)
                    .build();
            byte[] compressed = compress(codec, input, options);
            ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(compressed));

            assertEquals(size, info.contentSize(), "size=" + size);
            assertArrayEquals(input, Zstd.decompress(compressed, size), "size=" + size);
        }
    }

    /// Verifies dictionary-backed matches are accepted by an independent native context.
    @Test
    public void nativeDecoderReadsRawDictionaryMatch() throws IOException {
        byte[] dictionary = (
                "raw dictionary material with unique segments 0123456789abcdef;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);
        byte[] input = java.util.Arrays.copyOfRange(
                dictionary,
                dictionary.length - 4_096,
                dictionary.length
        );
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, CompressionDictionary.of(dictionary))
                .set(ZstdCodec.STRATEGY, ZstdStrategy.BT_OPT)
                .build();
        byte[] compressed = compress(new ZstdCodec(), input, options);

        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionary);
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }
        assertTrue(compressed.length < input.length / 4);
    }

    /// Verifies a serial block can match the complete retained tail of its predecessor.
    @Test
    public void nativeDecoderReadsCrossBlockHistoryMatch() throws IOException {
        int blockSize = 1 << 17;
        byte[] block = new byte[blockSize];
        new Random(0x6815_70a1_2026L).nextBytes(block);
        byte[] input = new byte[blockSize * 2];
        System.arraycopy(block, 0, input, 0, blockSize);
        System.arraycopy(block, 0, input, blockSize, blockSize);
        CodecOptions options = CodecOptions.builder()
                .set(ZstdCodec.WINDOW_LOG, 17L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 8L)
                .build();

        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, input, options);

        assertArrayEquals(new int[]{0, 2}, standardBlockTypes(codec, compressed));
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(compressed), input.length).array());
        assertTrue(compressed.length < input.length * 3 / 5);
    }

    /// Verifies job size and overlap control history at independently scheduled boundaries.
    @Test
    public void parallelJobsHonorSizeAndOverlap() throws IOException {
        int blockSize = 1 << 17;
        byte[] block = new byte[blockSize];
        new Random(0x50a4_11e1_2026L).nextBytes(block);
        byte[] input = new byte[blockSize * 8];
        for (int offset = 0; offset < input.length; offset += blockSize) {
            System.arraycopy(block, 0, input, offset, blockSize);
        }

        ZstdCodec codec = new ZstdCodec();
        byte[] noOverlap = compress(codec, input, parallelJobOptions(1L));
        byte[] fullOverlap = compress(codec, input, parallelJobOptions(9L));

        assertArrayEquals(
                new int[]{0, 2, 2, 2, 0, 2, 2, 2},
                standardBlockTypes(codec, noOverlap)
        );
        assertArrayEquals(
                new int[]{0, 2, 2, 2, 2, 2, 2, 2},
                standardBlockTypes(codec, fullOverlap)
        );
        assertArrayEquals(input, Zstd.decompress(noOverlap, input.length));
        assertArrayEquals(input, Zstd.decompress(fullOverlap, input.length));
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(noOverlap), input.length).array());
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(fullOverlap), input.length).array());
        assertTrue(fullOverlap.length < noOverlap.length * 3 / 4);
    }

    /// Verifies parallel job offsets and overlap history reset at explicit frame boundaries.
    @Test
    public void parallelFramesResetJobHistory() throws IOException {
        byte[] input = new byte[1 << 17];
        new Random(0x64a6_e5e7_2026L).nextBytes(input);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(2))
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length)
                .set(ZstdCodec.JOB_SIZE, 512L * 1024L)
                .set(ZstdCodec.OVERLAP_LOG, 9L)
                .build();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ZstdCodec codec = new ZstdCodec();
        int firstFrameSize;
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(encoded),
                options,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(input));
            encoder.finishFrame();
            firstFrameSize = encoded.size();
            encoder.write(ByteBuffer.wrap(input));
            encoder.finishFrame();
        }

        byte[] stream = encoded.toByteArray();
        byte[] firstFrame = java.util.Arrays.copyOf(stream, firstFrameSize);
        byte[] secondFrame = java.util.Arrays.copyOfRange(stream, firstFrameSize, stream.length);
        assertArrayEquals(new int[]{0}, standardBlockTypes(codec, firstFrame));
        assertArrayEquals(new int[]{0}, standardBlockTypes(codec, secondFrame));
        assertArrayEquals(input, Zstd.decompress(firstFrame, input.length));
        assertArrayEquals(input, Zstd.decompress(secondFrame, input.length));
    }

    /// Verifies non-block-aligned parallel job boundaries interoperate with both decoders.
    @Test
    public void randomizedParallelJobBoundariesInteroperate() throws IOException {
        long @Unmodifiable [] jobSizes = {512L * 1024L, 600_000L, 700_000L};
        long @Unmodifiable [] overlapLogs = {1L, 6L, 9L};
        ZstdCodec codec = new ZstdCodec();
        for (int iteration = 0; iteration < jobSizes.length; iteration++) {
            int size = Math.toIntExact(jobSizes[iteration] * 2L + 12_345L);
            byte[] input = new byte[size];
            new Random(0x6a0b_2026L + iteration).nextBytes(input);
            for (int position = 131_072; position + 4_096 <= input.length; position += 12_289) {
                System.arraycopy(input, position - 65_536, input, position, 4_096);
            }
            CodecOptions options = CodecOptions.builder()
                    .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(3))
                    .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                    .set(ZstdCodec.JOB_SIZE, jobSizes[iteration])
                    .set(ZstdCodec.OVERLAP_LOG, overlapLogs[iteration])
                    .build();

            byte[] compressed = compress(codec, input, options);
            assertArrayEquals(
                    input,
                    Zstd.decompress(compressed, input.length),
                    "iteration=" + iteration
            );
            assertArrayEquals(
                    input,
                    codec.decompress(ByteBuffer.wrap(compressed), input.length).array(),
                    "iteration=" + iteration
            );
        }
    }

    /// Verifies parallel block compression preserves block order and frame checksum state.
    @Test
    public void parallelBlocksRemainOrdered() throws IOException {
        byte[] input = new byte[1_048_699];
        Random random = new Random(0x4b10_cafeL);
        random.nextBytes(input);
        byte[] phrase = "parallel Zstandard block ordering;".getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset + phrase.length <= input.length; offset += 97) {
            System.arraycopy(phrase, 0, input, offset, phrase.length);
        }
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(3))
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .set(ZstdCodec.STRATEGY, ZstdStrategy.BT_OPT)
                .build();

        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, input, options);
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
        assertTrue(compressedBlockLiteralTypes(codec, compressed).length > 1);
        assertTrue(compressedBlockSequenceModes(codec, compressed).length > 1);
        assertTrue(compressed.length < input.length);
    }

    /// Verifies flushed non-final blocks remain readable before the frame trailer is available.
    @Test
    public void flushExposesCompleteBlock() throws IOException {
        byte[] first = "flushed pure Java Zstandard block ".repeat(512)
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "final block".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ZstdCodec codec = new ZstdCodec();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(encoded),
                CodecOptions.EMPTY,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(first));
            encoder.flush();

            ByteBuffer prefix = ByteBuffer.allocate(first.length);
            try (var decoder = codec.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(encoded.toByteArray()))
            )) {
                while (prefix.hasRemaining()) {
                    assertTrue(decoder.read(prefix) > 0);
                }
            }

            encoder.write(ByteBuffer.wrap(second));
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, Zstd.decompress(encoded.toByteArray(), expected.length));
    }

    /// Verifies flushing a partial parallel job exposes all accepted bytes.
    @Test
    public void parallelFlushExposesCompleteJob() throws IOException {
        byte[] first = "flushed parallel Zstandard job ".repeat(2_048)
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "parallel final block".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ZstdCodec codec = new ZstdCodec();
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(2))
                .set(ZstdCodec.JOB_SIZE, 512L * 1024L)
                .set(ZstdCodec.OVERLAP_LOG, 9L)
                .build();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(encoded),
                options,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(first));
            encoder.flush();

            ByteBuffer prefix = ByteBuffer.allocate(first.length);
            try (var decoder = codec.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(encoded.toByteArray()))
            )) {
                while (prefix.hasRemaining()) {
                    assertTrue(decoder.read(prefix) > 0);
                }
            }

            encoder.write(ByteBuffer.wrap(second));
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        byte[] compressed = encoded.toByteArray();
        assertArrayEquals(expected, Zstd.decompress(compressed, expected.length));
        assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(compressed), expected.length).array());
    }

    /// Verifies frame-wide matching compresses data beyond the ordinary hash-chain distance.
    @Test
    public void nativeDecoderReadsLongDistanceMatch() throws IOException {
        int blockSize = 1 << 17;
        byte[] input = longDistanceFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] disabled = compress(codec, input, longDistanceOptions(false, 20L));
        byte[] enabled = compress(codec, input, longDistanceOptions(true, 20L));

        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, standardBlockTypes(codec, disabled));
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 2}, standardBlockTypes(codec, enabled));
        assertArrayEquals(input, Zstd.decompress(enabled, input.length));
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(enabled), input.length).array());
        assertTrue(enabled.length < disabled.length - blockSize / 2);
    }

    /// Verifies every public LDM tuning option reaches the matcher and remains native-compatible.
    @Test
    public void customLongDistanceParametersInteroperate() throws IOException {
        byte[] input = longDistanceFixture();
        ZstdCodec codec = new ZstdCodec();
        CodecOptions options = CodecOptions.builder()
                .set(ZstdCodec.LONG_DISTANCE_MATCHING, true)
                .set(ZstdCodec.WINDOW_LOG, 20L)
                .set(ZstdCodec.HASH_LOG, 18L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 6L)
                .set(ZstdCodec.LDM_HASH_LOG, 18L)
                .set(ZstdCodec.LDM_MIN_MATCH, 32L)
                .set(ZstdCodec.LDM_BUCKET_SIZE_LOG, 4L)
                .set(ZstdCodec.LDM_HASH_RATE_LOG, 2L)
                .build();
        byte[] compressed = compress(codec, input, options);

        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 2}, standardBlockTypes(codec, compressed));
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(compressed), input.length).array());
    }

    /// Verifies long-distance candidates beyond the configured frame window are rejected.
    @Test
    public void longDistanceMatchingHonorsWindow() throws IOException {
        byte[] input = longDistanceFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, input, longDistanceOptions(true, 19L));

        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, standardBlockTypes(codec, compressed));
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
    }

    /// Verifies frame-wide matching remains available across independently scheduled jobs.
    @Test
    public void parallelJobsUseLongDistanceMatches() throws IOException {
        byte[] input = longDistanceFixture();
        ZstdCodec codec = new ZstdCodec();
        byte[] compressed = compress(codec, input, parallelLongDistanceOptions());

        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 2}, standardBlockTypes(codec, compressed));
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
        assertArrayEquals(input, codec.decompress(ByteBuffer.wrap(compressed), input.length).array());
    }

    /// Verifies all strategy-specific parser outputs interoperate with the native decoder.
    @Test
    public void nativeDecoderReadsEveryStrategyParser() throws IOException {
        byte[] input = strategyFixture();
        ZstdCodec codec = new ZstdCodec();
        for (ZstdStrategy strategy : ZstdStrategy.values()) {
            byte[] compressed = compress(codec, input, strategyOptions(strategy));
            assertArrayEquals(input, Zstd.decompress(compressed, input.length), strategy.name());
            assertArrayEquals(
                    input,
                    codec.decompress(ByteBuffer.wrap(compressed), input.length).array(),
                    strategy.name()
            );
        }

        byte[] greedy = compress(codec, input, strategyOptions(ZstdStrategy.GREEDY));
        byte[] lazy = compress(codec, input, strategyOptions(ZstdStrategy.LAZY));
        byte[] optimal = compress(codec, input, strategyOptions(ZstdStrategy.BT_OPT));
        assertTrue(lazy.length < greedy.length);
        assertTrue(optimal.length <= greedy.length);
    }

    /// Returns the sequence count from the first raw-literals compressed block.
    private static int firstBlockSequenceCount(ZstdCodec codec, byte[] frame) throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int blockOffset = info.headerSize();
        int blockHeader = Byte.toUnsignedInt(frame[blockOffset])
                | Byte.toUnsignedInt(frame[blockOffset + 1]) << 8
                | Byte.toUnsignedInt(frame[blockOffset + 2]) << 16;
        assertEquals(2, blockHeader >>> 1 & 3);

        int literalOffset = blockOffset + 3;
        int literalHeader = Byte.toUnsignedInt(frame[literalOffset]);
        assertEquals(0, literalHeader & 3);
        int sizeFormat = literalHeader >>> 2 & 3;
        int literalHeaderSize;
        int literalSize;
        if (sizeFormat == 0 || sizeFormat == 2) {
            literalHeaderSize = 1;
            literalSize = literalHeader >>> 3;
        } else if (sizeFormat == 1) {
            literalHeaderSize = 2;
            literalSize = (literalHeader
                    | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8) >>> 4;
        } else {
            literalHeaderSize = 3;
            literalSize = (literalHeader
                    | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8
                    | Byte.toUnsignedInt(frame[literalOffset + 2]) << 16) >>> 4;
        }

        int sequenceOffset = literalOffset + literalHeaderSize + literalSize;
        int first = Byte.toUnsignedInt(frame[sequenceOffset]);
        if (first < 128) {
            return first;
        }
        if (first < 255) {
            return (first - 128) << 8 | Byte.toUnsignedInt(frame[sequenceOffset + 1]);
        }
        return (Byte.toUnsignedInt(frame[sequenceOffset + 1])
                | Byte.toUnsignedInt(frame[sequenceOffset + 2]) << 8) + 0x7f00;
    }

    /// Returns literal metadata from the first compressed block in a frame.
    private static LiteralSectionInfo firstLiteralSection(ZstdCodec codec, byte[] frame) throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int blockOffset = info.headerSize();
        int blockHeader = Byte.toUnsignedInt(frame[blockOffset])
                | Byte.toUnsignedInt(frame[blockOffset + 1]) << 8
                | Byte.toUnsignedInt(frame[blockOffset + 2]) << 16;
        assertEquals(2, blockHeader >>> 1 & 3);

        int literalOffset = blockOffset + 3;
        int first = Byte.toUnsignedInt(frame[literalOffset]);
        int type = first & 3;
        int sizeFormat = first >>> 2 & 3;
        if (type <= 1) {
            int regeneratedSize;
            if (sizeFormat == 0 || sizeFormat == 2) {
                regeneratedSize = first >>> 3;
            } else if (sizeFormat == 1) {
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8) >>> 4;
            } else {
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8
                        | Byte.toUnsignedInt(frame[literalOffset + 2]) << 16) >>> 4;
            }
            return new LiteralSectionInfo(type, regeneratedSize, 0);
        }

        if (sizeFormat <= 1) {
            int header = first
                    | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8
                    | Byte.toUnsignedInt(frame[literalOffset + 2]) << 16;
            return new LiteralSectionInfo(type, header >>> 4 & 0x3ff, sizeFormat == 0 ? 1 : 4);
        }
        if (sizeFormat == 2) {
            long header = Integer.toUnsignedLong(
                    first
                            | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8
                            | Byte.toUnsignedInt(frame[literalOffset + 2]) << 16
                            | frame[literalOffset + 3] << 24
            );
            return new LiteralSectionInfo(type, (int) (header >>> 4 & 0x3fff), 4);
        }
        long header = Integer.toUnsignedLong(
                first
                        | Byte.toUnsignedInt(frame[literalOffset + 1]) << 8
                        | Byte.toUnsignedInt(frame[literalOffset + 2]) << 16
                        | frame[literalOffset + 3] << 24
        ) | (long) Byte.toUnsignedInt(frame[literalOffset + 4]) << 32;
        return new LiteralSectionInfo(type, (int) (header >>> 4 & 0x3ffff), 4);
    }

    /// Creates options for one explicit strategy with a deep ordinary hash chain.
    private static CodecOptions strategyOptions(ZstdStrategy strategy) {
        return CodecOptions.builder()
                .set(ZstdCodec.STRATEGY, strategy)
                .set(ZstdCodec.HASH_LOG, 15L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 6L)
                .build();
    }

    /// Creates an input where delaying a four-byte match exposes a long match.
    private static byte[] strategyFixture() {
        byte[] pattern = new byte[4_096];
        new Random(0x57a7_e6d1_2026L).nextBytes(pattern);
        byte[] firstSeparator = new byte[31];
        byte[] secondSeparator = new byte[31];
        java.util.Arrays.fill(firstSeparator, (byte) 0x5a);
        java.util.Arrays.fill(secondSeparator, (byte) 0x6b);

        byte[] input = new byte[
                pattern.length + firstSeparator.length + 5
                        + secondSeparator.length + 1 + pattern.length
        ];
        int position = 0;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        position += pattern.length;
        System.arraycopy(firstSeparator, 0, input, position, firstSeparator.length);
        position += firstSeparator.length;
        input[position++] = 0x41;
        System.arraycopy(pattern, 0, input, position, 3);
        position += 3;
        input[position++] = 0x51;
        System.arraycopy(secondSeparator, 0, input, position, secondSeparator.length);
        position += secondSeparator.length;
        input[position++] = 0x41;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        return input;
    }

    /// Creates options for synchronous long-distance matching scenarios.
    private static CodecOptions longDistanceOptions(boolean enabled, long windowLog) {
        return CodecOptions.builder()
                .set(ZstdCodec.LONG_DISTANCE_MATCHING, enabled)
                .set(ZstdCodec.WINDOW_LOG, windowLog)
                .set(ZstdCodec.HASH_LOG, 18L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 6L)
                .build();
    }

    /// Creates options for long-distance matching across 512 KiB parallel jobs.
    private static CodecOptions parallelLongDistanceOptions() {
        return CodecOptions.builder()
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(2))
                .set(ZstdCodec.LONG_DISTANCE_MATCHING, true)
                .set(ZstdCodec.JOB_SIZE, 512L * 1024L)
                .set(ZstdCodec.OVERLAP_LOG, 1L)
                .set(ZstdCodec.WINDOW_LOG, 20L)
                .set(ZstdCodec.HASH_LOG, 18L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 6L)
                .build();
    }

    /// Creates a six-block input whose final block repeats data outside the ordinary chain limit.
    private static byte[] longDistanceFixture() {
        int blockSize = 1 << 17;
        byte[] input = new byte[blockSize * 6];
        new Random(0x1d15_7a6c_5eedL).nextBytes(input);
        System.arraycopy(input, 0, input, blockSize * 5, blockSize);
        return input;
    }

    /// Creates options for two 512 KiB jobs with a selected overlap mode.
    private static CodecOptions parallelJobOptions(long overlapLog) {
        return CodecOptions.builder()
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(2))
                .set(ZstdCodec.JOB_SIZE, 512L * 1024L)
                .set(ZstdCodec.OVERLAP_LOG, overlapLog)
                .set(ZstdCodec.WINDOW_LOG, 17L)
                .set(ZstdCodec.CHAIN_LOG, 17L)
                .set(ZstdCodec.SEARCH_LOG, 8L)
                .build();
    }

    /// Returns all block types from one standard frame.
    private static int[] standardBlockTypes(ZstdCodec codec, byte[] frame) throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int[] types = new int[8];
        int count = 0;
        int offset = info.headerSize();
        while (true) {
            int header = Byte.toUnsignedInt(frame[offset])
                    | Byte.toUnsignedInt(frame[offset + 1]) << 8
                    | Byte.toUnsignedInt(frame[offset + 2]) << 16;
            boolean last = (header & 1) != 0;
            int blockType = header >>> 1 & 3;
            int blockSize = header >>> 3;
            if (count == types.length) {
                types = java.util.Arrays.copyOf(types, types.length * 2);
            }
            types[count++] = blockType;
            int payloadSize = blockType == 1 ? 1 : blockSize;
            offset += 3 + payloadSize;
            if (last) {
                return java.util.Arrays.copyOf(types, count);
            }
        }
    }

    /// Returns literal types from compressed blocks in one standard frame.
    private static int[] compressedBlockLiteralTypes(ZstdCodec codec, byte[] frame) throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int[] types = new int[8];
        int count = 0;
        int offset = info.headerSize();
        while (true) {
            int header = Byte.toUnsignedInt(frame[offset])
                    | Byte.toUnsignedInt(frame[offset + 1]) << 8
                    | Byte.toUnsignedInt(frame[offset + 2]) << 16;
            boolean last = (header & 1) != 0;
            int blockType = header >>> 1 & 3;
            int blockSize = header >>> 3;
            if (blockType == 2) {
                if (count == types.length) {
                    types = java.util.Arrays.copyOf(types, types.length * 2);
                }
                types[count++] = frame[offset + 3] & 3;
            }
            int payloadSize = blockType == 1 ? 1 : blockSize;
            offset += 3 + payloadSize;
            if (last) {
                return java.util.Arrays.copyOf(types, count);
            }
        }
    }

    /// Returns sequence mode bytes from compressed blocks containing sequences.
    private static int[] compressedBlockSequenceModes(ZstdCodec codec, byte[] frame)
            throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int[] modes = new int[8];
        int count = 0;
        int offset = info.headerSize();
        while (true) {
            int header = Byte.toUnsignedInt(frame[offset])
                    | Byte.toUnsignedInt(frame[offset + 1]) << 8
                    | Byte.toUnsignedInt(frame[offset + 2]) << 16;
            boolean last = (header & 1) != 0;
            int blockType = header >>> 1 & 3;
            int blockSize = header >>> 3;
            if (blockType == 2) {
                int position = offset + 3;
                position += encodedLiteralSectionSize(frame, position);
                int firstCount = Byte.toUnsignedInt(frame[position++]);
                int sequenceCount;
                if (firstCount < 128) {
                    sequenceCount = firstCount;
                } else if (firstCount < 255) {
                    sequenceCount = (firstCount - 128) << 8
                            | Byte.toUnsignedInt(frame[position++]);
                } else {
                    sequenceCount = (Byte.toUnsignedInt(frame[position])
                            | Byte.toUnsignedInt(frame[position + 1]) << 8) + 0x7f00;
                    position += 2;
                }
                if (sequenceCount != 0) {
                    if (count == modes.length) {
                        modes = java.util.Arrays.copyOf(modes, modes.length * 2);
                    }
                    modes[count++] = Byte.toUnsignedInt(frame[position]);
                }
            }
            int payloadSize = blockType == 1 ? 1 : blockSize;
            offset += 3 + payloadSize;
            if (last) {
                return java.util.Arrays.copyOf(modes, count);
            }
        }
    }

    /// Returns the encoded byte size of one literal section.
    private static int encodedLiteralSectionSize(byte[] frame, int offset) {
        int first = Byte.toUnsignedInt(frame[offset]);
        int type = first & 3;
        int sizeFormat = first >>> 2 & 3;
        if (type <= 1) {
            int headerSize;
            int regeneratedSize;
            if (sizeFormat == 0 || sizeFormat == 2) {
                headerSize = 1;
                regeneratedSize = first >>> 3;
            } else if (sizeFormat == 1) {
                headerSize = 2;
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame[offset + 1]) << 8) >>> 4;
            } else {
                headerSize = 3;
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame[offset + 1]) << 8
                        | Byte.toUnsignedInt(frame[offset + 2]) << 16) >>> 4;
            }
            return headerSize + (type == 0 ? regeneratedSize : 1);
        }

        if (sizeFormat <= 1) {
            int header = first
                    | Byte.toUnsignedInt(frame[offset + 1]) << 8
                    | Byte.toUnsignedInt(frame[offset + 2]) << 16;
            return 3 + (header >>> 14 & 0x3ff);
        }
        if (sizeFormat == 2) {
            long header = Integer.toUnsignedLong(
                    first
                            | Byte.toUnsignedInt(frame[offset + 1]) << 8
                            | Byte.toUnsignedInt(frame[offset + 2]) << 16
                            | frame[offset + 3] << 24
            );
            return 4 + (int) (header >>> 18 & 0x3fff);
        }
        long header = Integer.toUnsignedLong(
                first
                        | Byte.toUnsignedInt(frame[offset + 1]) << 8
                        | Byte.toUnsignedInt(frame[offset + 2]) << 16
                        | frame[offset + 3] << 24
        ) | (long) Byte.toUnsignedInt(frame[offset + 4]) << 32;
        return 5 + (int) (header >>> 22 & 0x3ffff);
    }

    /// Returns whether one packed mode byte contains the requested table mode.
    private static boolean hasSequenceMode(int modes, int expected) {
        return modes >>> 6 == expected
                || (modes >>> 4 & 3) == expected
                || (modes >>> 2 & 3) == expected;
    }

    /// Creates two blocks with identical, varied match and literal-length populations.
    private static byte[] repeatedSequenceTableFixture() {
        int blockSize = 1 << 17;
        ByteArrayOutputStream block = new ByteArrayOutputStream(blockSize);
        Random random = new Random(0x5e71_7ab1_2026L);
        int index = 0;
        while (true) {
            int repeatedSize = 24 + index % 47 * 3;
            int separatorSize = 3 + index % 29;
            if (block.size() + repeatedSize * 2 + separatorSize > blockSize - 256) {
                break;
            }
            byte[] repeated = new byte[repeatedSize];
            random.nextBytes(repeated);
            block.writeBytes(repeated);
            block.writeBytes(repeated);
            byte[] separator = new byte[separatorSize];
            random.nextBytes(separator);
            block.writeBytes(separator);
            index++;
        }
        byte[] padding = new byte[blockSize - block.size()];
        random.nextBytes(padding);
        block.writeBytes(padding);

        byte[] first = block.toByteArray();
        byte[] input = new byte[blockSize * 2];
        System.arraycopy(first, 0, input, 0, blockSize);
        System.arraycopy(first, 0, input, blockSize, blockSize);
        return input;
    }

    /// Creates two blocks with identical Huffman populations and independent repeated halves.
    private static byte[] treelessHuffmanFixture() {
        int blockSize = 1 << 17;
        byte[] input = new byte[blockSize * 2];
        Random random = new Random(0x7ee1_e550_2026L);
        for (int symbol = 0; symbol < 64; symbol++) {
            input[symbol] = (byte) (32 + symbol);
        }
        for (int index = 64; index < blockSize / 2; index++) {
            input[index] = random.nextInt(100) < 55
                    ? (byte) ' '
                    : (byte) (32 + random.nextInt(64));
        }
        System.arraycopy(input, 0, input, blockSize / 2, blockSize / 2);
        System.arraycopy(input, 0, input, blockSize, blockSize);
        return input;
    }

    /// Returns the first byte of a compressed Huffman table description.
    private static int firstHuffmanTableHeader(ZstdCodec codec, byte[] frame) throws IOException {
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
        int literalOffset = info.headerSize() + 3;
        int first = Byte.toUnsignedInt(frame[literalOffset]);
        assertEquals(2, first & 3);
        int sizeFormat = first >>> 2 & 3;
        int headerSize = sizeFormat <= 1 ? 3 : sizeFormat == 2 ? 4 : 5;
        return Byte.toUnsignedInt(frame[literalOffset + headerSize]);
    }

    /// Creates one duplicated binary chunk containing every possible byte value.
    private static byte[] fullAlphabetHuffmanFixture() {
        byte[] chunk = new byte[24_576];
        for (int symbol = 0; symbol < 256; symbol++) {
            chunk[symbol] = (byte) symbol;
        }
        Random random = new Random(0x256a_1fab_2026L);
        for (int index = 256; index < chunk.length; index++) {
            chunk[index] = random.nextInt(100) < 80
                    ? (byte) random.nextInt(64)
                    : (byte) random.nextInt(256);
        }

        byte[] input = new byte[chunk.length * 2];
        System.arraycopy(chunk, 0, input, 0, chunk.length);
        System.arraycopy(chunk, 0, input, chunk.length, chunk.length);
        return input;
    }

    /// Creates duplicated skewed-ASCII chunks that leave a controllable literal population.
    private static byte[] huffmanFixture(int chunkCount, int chunkSize, long seed) {
        ByteArrayOutputStream input = new ByteArrayOutputStream(chunkCount * (chunkSize * 2 + 3));
        Random random = new Random(seed);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            byte[] chunk = new byte[chunkSize];
            for (int index = 0; index < chunk.length; index++) {
                int sample = random.nextInt(100);
                chunk[index] = sample < 50
                        ? (byte) ' '
                        : sample < 88
                        ? (byte) ('a' + random.nextInt(6))
                        : (byte) ('0' + random.nextInt(4));
            }
            input.writeBytes(chunk);
            input.writeBytes(chunk);
            input.write('a');
            input.write('0' + chunkIndex % 4);
            input.write('b');
        }
        return input.toByteArray();
    }

    /// Describes the first literal section representation.
    ///
    /// @param type literal block type
    /// @param regeneratedSize decoded literal byte count
    /// @param streamCount Huffman stream count, or zero for an uncompressed representation
    private record LiteralSectionInfo(int type, int regeneratedSize, int streamCount) {
    }

    /// Compresses bytes through the channel API without closing the caller-owned target.
    private static byte[] compress(ZstdCodec codec, byte[] input, CodecOptions options) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(encoded),
                options
        );
        return encoded.toByteArray();
    }
}
