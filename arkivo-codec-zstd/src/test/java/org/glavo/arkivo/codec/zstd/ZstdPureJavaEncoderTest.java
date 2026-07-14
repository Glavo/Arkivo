// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.jetbrains.annotations.NotNullByDefault;
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
                .build();
        byte[] compressed = compress(new ZstdCodec(), input, options);

        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionary);
            assertArrayEquals(input, context.decompress(compressed, input.length));
        }
        assertTrue(compressed.length < input.length / 4);
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
                .build();

        byte[] compressed = compress(new ZstdCodec(), input, options);
        assertArrayEquals(input, Zstd.decompress(compressed, input.length));
    }

    /// Verifies flushed non-final blocks remain readable before the frame trailer is available.
    @Test
    public void flushExposesCompleteBlock() throws IOException {
        byte[] first = "flushed pure Java Zstandard block ".repeat(512)
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "final block".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ZstdCodec codec = new ZstdCodec();
        try (CompressionEncoder encoder = codec.openEncoder(
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
