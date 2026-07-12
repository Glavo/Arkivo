// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests Zstandard codec behavior.
@NotNullByDefault
public final class ZstdCodecTest {
    /// Verifies that Zstandard compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        ZstdCodec codec = new ZstdCodec().withCompressionLevel(1);
        byte[] input = "hello zstd".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(ZstdCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the Zstandard codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(ZstdCodec.class, Objects.requireNonNull(CompressionCodecs.find(ZstdCodec.NAME)).getClass());
    }

    /// Verifies Zstandard metadata and ByteBuffer one-shot compression.
    @Test
    public void byteBufferRoundTrip() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd bytebuffer".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();
        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));

        assertEquals(true, codec.canCompressBuffers());
        assertEquals(true, codec.canDecompressBuffers());
        codec.compress(source, compressed);
        compressed.flip();
        assertEquals(true, codec.matches(compressed.duplicate()));

        ByteBuffer decompressed = ByteBuffer.allocateDirect(input.length);
        codec.decompress(compressed, decompressed);
        decompressed.flip();

        byte[] output = new byte[decompressed.remaining()];
        decompressed.get(output);
        assertArrayEquals(input, output);
    }

    /// Verifies ByteBuffer one-shot operations honor non-zero positions and limits.
    @Test
    public void byteBufferRoundTripWithSlicedRanges() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd bytebuffer slice".getBytes(StandardCharsets.UTF_8);

        int sourceStart = 3;
        ByteBuffer source = ByteBuffer.allocateDirect(sourceStart + input.length + 4);
        source.position(sourceStart);
        source.put(input);
        int sourceLimit = source.position();
        source.limit(sourceLimit);
        source.position(sourceStart);

        int compressedStart = 5;
        ByteBuffer compressed =
                ByteBuffer.allocateDirect(compressedStart + (int) codec.maxCompressedSize(input.length) + 3);
        compressed.position(compressedStart);
        compressed.limit(compressed.capacity() - 3);

        codec.compress(source, compressed);
        assertEquals(sourceLimit, source.position());
        int compressedEnd = compressed.position();
        assertEquals(true, compressedEnd > compressedStart);

        ByteBuffer compressedRange = compressed.duplicate();
        compressedRange.position(compressedStart);
        compressedRange.limit(compressedEnd);

        int decompressedStart = 4;
        ByteBuffer decompressed = ByteBuffer.allocateDirect(decompressedStart + input.length + 2);
        decompressed.position(decompressedStart);
        decompressed.limit(decompressedStart + input.length);

        codec.decompress(compressedRange, decompressed);
        assertEquals(compressedEnd, compressedRange.position());
        assertEquals(decompressedStart + input.length, decompressed.position());

        ByteBuffer outputRange = decompressed.duplicate();
        outputRange.position(decompressedStart);
        outputRange.limit(decompressed.position());
        byte[] output = new byte[outputRange.remaining()];
        outputRange.get(output);
        assertArrayEquals(input, output);
    }

    /// Verifies Zstandard ByteBuffer one-shot compression with dictionary bytes.
    @Test
    public void byteBufferDictionaryRoundTrip() throws IOException {
        byte[] dictionary = "hello zstd dictionary".getBytes(StandardCharsets.UTF_8);
        ZstdCodec codec = new ZstdCodec()
                .withCompressionLevel(1)
                .withDictionary(dictionary);
        byte[] input = "hello zstd dictionary bytebuffer".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();
        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));

        codec.compress(source, compressed);
        compressed.flip();

        ByteBuffer decompressed = ByteBuffer.allocateDirect(input.length);
        codec.decompress(compressed, decompressed);
        decompressed.flip();

        byte[] output = new byte[decompressed.remaining()];
        decompressed.get(output);
        assertArrayEquals(input, output);
    }

    /// Verifies that ByteBuffer operations reject read-only target buffers with `IOException`.
    @Test
    public void byteBufferReadOnlyTarget() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd readonly target".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();

        ByteBuffer readOnlyCompressed = ByteBuffer
                .allocateDirect((int) codec.maxCompressedSize(input.length))
                .asReadOnlyBuffer();
        IOException compressionException =
                assertThrows(IOException.class, () -> codec.compress(source, readOnlyCompressed));
        assertEquals(true, compressionException.getMessage().contains("target buffer must be writable"));

        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));
        codec.compress(source.rewind(), compressed);
        compressed.flip();

        ByteBuffer readOnlyDecompressed = ByteBuffer.allocateDirect(input.length).asReadOnlyBuffer();
        IOException decompressionException =
                assertThrows(IOException.class, () -> codec.decompress(compressed, readOnlyDecompressed));
        assertEquals(true, decompressionException.getMessage().contains("target buffer must be writable"));
    }

    /// Verifies configured Zstandard codec instances are immutable.
    @Test
    public void configuredCodec() {
        byte[] dictionary = {1, 2, 3};
        ZstdCodec codec = new ZstdCodec()
                .withCompressionLevel(2)
                .withDictionary(dictionary);

        dictionary[0] = 9;
        assertEquals(2, codec.compressionLevel());
        assertArrayEquals(new byte[]{1, 2, 3}, codec.dictionary());

        byte[] returned = codec.dictionary();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, codec.dictionary());
        assertNull(codec.withoutDictionary().dictionary());
        assertEquals(-1L, new ZstdCodec().withCompressionLevel(-1L).compressionLevel());
        assertEquals(ZstdCodec.DEFAULT_COMPRESSION_LEVEL, new ZstdCodec().compressionLevel());
    }

    /// Verifies per-operation channel options and advertised capabilities.
    @Test
    public void channelOperationOptions() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] dictionaryBytes = "shared zstd dictionary".getBytes(StandardCharsets.UTF_8);
        CompressionDictionary dictionary = CompressionDictionary.of(dictionaryBytes);
        CodecOptions compressionOptions = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, -2L)
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(0))
                .build();
        CodecOptions decompressionOptions = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .build();
        byte[] input = "shared zstd dictionary payload".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(input));
             WritableByteChannel target = Channels.newChannel(compressedBytes)) {
            codec.compress(source, target, compressionOptions);
            assertEquals(true, source.isOpen());
            assertEquals(true, target.isOpen());
        }

        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        try (ReadableByteChannel source = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        ); WritableByteChannel target = Channels.newChannel(decodedBytes)) {
            codec.decompress(source, target, decompressionOptions);
        }

        assertArrayEquals(input, decodedBytes.toByteArray());
        assertEquals(true, codec.capabilities().supports(CompressionFeature.FLUSH));
        assertEquals(true, codec.capabilities().compressionOptions().contains(
                StandardCodecOptions.COMPRESSION_LEVEL
        ));
        assertEquals(true, codec.minimumCompressionLevel() <= -2);
        assertEquals(true, codec.maximumCompressionLevel() >= -2);
        assertEquals(Zstd.defaultCompressionLevel(), codec.defaultCompressionLevel());
    }

    /// Compresses and decompresses the given bytes.
    private static byte[] roundTrip(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codec.compressTo(compressed)) {
            output.write(input);
        }

        try (InputStream inputStream = codec.decompressFrom(new ByteArrayInputStream(compressed.toByteArray()))) {
            return inputStream.readAllBytes();
        }
    }
}
