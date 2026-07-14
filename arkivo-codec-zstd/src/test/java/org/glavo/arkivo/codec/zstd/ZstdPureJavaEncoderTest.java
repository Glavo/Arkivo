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
