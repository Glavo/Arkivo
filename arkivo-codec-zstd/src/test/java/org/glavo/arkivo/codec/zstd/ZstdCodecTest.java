// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.EncodeDirective;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// Converts native data-corruption failures into the channel API's checked exception contract.
    @Test
    public void reportsCorruptFrameAsIOException() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] content = "corrupt Zstandard frame".repeat(64).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed)
        );
        byte[] frame = compressed.toByteArray();
        frame[0] ^= 0x01;

        IOException failure = assertThrows(IOException.class, () -> codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(frame)),
                Channels.newChannel(new ByteArrayOutputStream())
        ));
        assertEquals("Invalid Zstandard frame", failure.getMessage());
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
        byte[] input = "shared zstd dictionary payload".getBytes(StandardCharsets.UTF_8);
        CodecOptions compressionOptions = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, -2L)
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(0))
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length)
                .build();
        CodecOptions decompressionOptions = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .build();

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

    /// Verifies multiple pledged frames reuse one encoder and decode as one concatenated stream.
    @Test
    public void pledgedMultiFrameRoundTrip() throws IOException {
        byte[] frame = (
                "pledged Zstandard frame 0123456789abcdef;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) frame.length)
                .build();
        ZstdCodec codec = new ZstdCodec();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        CompressionEncoder encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                options,
                ChannelOwnership.RETAIN
        );
        encoder.encode(ByteBuffer.wrap(frame), EncodeDirective.END_FRAME);
        int firstFrameSize = compressed.size();
        assertTrue(encoder.isOpen());
        encoder.encode(ByteBuffer.wrap(frame), EncodeDirective.END_FRAME);
        int completeSize = compressed.size();
        assertTrue(completeSize > firstFrameSize);
        encoder.finish();
        assertEquals(completeSize, compressed.size());

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray())),
                Channels.newChannel(decoded)
        );
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(frame);
        expected.writeBytes(frame);
        assertArrayEquals(expected.toByteArray(), decoded.toByteArray());
    }

    /// Verifies standard and skippable frame inspection without changing buffer state.
    @Test
    public void frameInspection() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] content = (
                "inspectable Zstandard frame 0123456789abcdef;"
        ).repeat(128).getBytes(StandardCharsets.UTF_8);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) content.length)
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(encoded),
                options
        );

        byte[] frame = encoded.toByteArray();
        ByteBuffer source = ByteBuffer.allocate(2 + frame.length * 2);
        source.position(2);
        source.put(frame);
        source.put(frame);
        source.flip();
        source.position(2);
        int initialPosition = source.position();

        ZstdFrameInfo parsed = codec.frameInfo(source);
        assertTrue(parsed instanceof ZstdStandardFrameInfo);
        ZstdStandardFrameInfo standard = (ZstdStandardFrameInfo) parsed;
        assertEquals(content.length, standard.contentSize());
        assertTrue(standard.windowSize() > 0L);
        assertEquals(CompressionDictionary.UNKNOWN_ID, standard.dictionaryId());
        assertTrue(standard.checksum());
        assertEquals(frame.length, codec.frameCompressedSize(source));
        assertEquals(initialPosition, source.position());

        CodecOptions noContentSize = CodecOptions.builder()
                .set(ZstdCodec.CONTENT_SIZE, false)
                .build();
        ByteArrayOutputStream noContentEncoded = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(noContentEncoded),
                noContentSize
        );
        ZstdStandardFrameInfo noContentInfo = (ZstdStandardFrameInfo) codec.frameInfo(
                ByteBuffer.wrap(noContentEncoded.toByteArray())
        );
        assertEquals(CompressionCodec.UNKNOWN_SIZE, noContentInfo.contentSize());
        assertTrue(noContentInfo.windowSize() > 0L);

        ByteBuffer dictionaryHeader = ByteBuffer.wrap(new byte[]{
                0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
                0x23,
                0x12, 0x34, 0x56, 0x78,
                0x05
        });
        ZstdStandardFrameInfo dictionaryInfo =
                (ZstdStandardFrameInfo) codec.frameInfo(dictionaryHeader);
        assertEquals(10, dictionaryInfo.headerSize());
        assertEquals(5L, dictionaryInfo.contentSize());
        assertEquals(5L, dictionaryInfo.windowSize());
        assertEquals(0x7856_3412L, dictionaryInfo.dictionaryId());
        assertEquals(false, dictionaryInfo.checksum());

        byte[] overflowHeader = new byte[13];
        overflowHeader[0] = 0x28;
        overflowHeader[1] = (byte) 0xb5;
        overflowHeader[2] = 0x2f;
        overflowHeader[3] = (byte) 0xfd;
        overflowHeader[4] = (byte) 0xe0;
        java.util.Arrays.fill(overflowHeader, 5, overflowHeader.length, (byte) 0xff);
        ZstdStandardFrameInfo overflowInfo =
                (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(overflowHeader));
        assertEquals(ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW, overflowInfo.contentSize());
        assertEquals(Long.MAX_VALUE, overflowInfo.windowSize());

        ByteBuffer skippable = ByteBuffer.wrap(new byte[]{
                0x57, 0x2a, 0x4d, 0x18,
                0x03, 0x00, 0x00, 0x00,
                1, 2, 3
        });
        ZstdFrameInfo skippableParsed = codec.frameInfo(skippable);
        assertTrue(skippableParsed instanceof ZstdSkippableFrameInfo);
        ZstdSkippableFrameInfo skippableInfo = (ZstdSkippableFrameInfo) skippableParsed;
        assertEquals(7, skippableInfo.id());
        assertEquals(3L, skippableInfo.payloadSize());
        assertEquals(11L, codec.frameCompressedSize(skippable));

        ByteArrayOutputStream skippableAndFrame = new ByteArrayOutputStream();
        skippableAndFrame.writeBytes(skippable.array());
        skippableAndFrame.writeBytes(frame);
        ByteArrayOutputStream decodedAfterSkippable = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(skippableAndFrame.toByteArray())),
                Channels.newChannel(decodedAfterSkippable)
        );
        assertArrayEquals(content, decodedAfterSkippable.toByteArray());

        assertThrows(
                EOFException.class,
                () -> codec.frameInfo(ByteBuffer.wrap(new byte[]{0x28, (byte) 0xb5}))
        );
        assertThrows(
                EOFException.class,
                () -> codec.frameCompressedSize(ByteBuffer.wrap(new byte[]{
                        0x50, 0x2a, 0x4d, 0x18,
                        0x02, 0x00, 0x00, 0x00,
                        1
                }))
        );
        assertThrows(
                IOException.class,
                () -> codec.frameInfo(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}))
        );
        assertThrows(
                IOException.class,
                () -> codec.frameCompressedSize(ByteBuffer.wrap(new byte[]{
                        0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
                        0x20, 0x00,
                        0x07, 0x00, 0x00
                }))
        );
    }

    /// Verifies advanced Zstandard context parameters, dynamic bounds, and every native strategy.
    @Test
    public void advancedCompressionOptions() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        assertTrue(codec.minimumWindowLog() <= codec.maximumWindowLog());
        assertTrue(codec.minimumHashLog() <= codec.maximumHashLog());
        assertTrue(codec.minimumChainLog() <= codec.maximumChainLog());
        assertTrue(codec.minimumSearchLog() <= codec.maximumSearchLog());
        assertTrue(codec.minimumMatchLength() <= codec.maximumMatchLength());

        assertTrue(codec.capabilities().compressionOptions().containsAll(Set.of(
                ZstdCodec.WINDOW_LOG,
                ZstdCodec.HASH_LOG,
                ZstdCodec.CHAIN_LOG,
                ZstdCodec.SEARCH_LOG,
                ZstdCodec.MIN_MATCH,
                ZstdCodec.TARGET_LENGTH,
                ZstdCodec.STRATEGY,
                ZstdCodec.JOB_SIZE,
                ZstdCodec.OVERLAP_LOG,
                ZstdCodec.CONTENT_SIZE,
                ZstdCodec.DICTIONARY_ID,
                ZstdCodec.LONG_DISTANCE_MATCHING
        )));

        byte[] input = (
                "advanced Zstandard compression parameters 0123456789abcdef;"
        ).repeat(1_024).getBytes(StandardCharsets.UTF_8);
        CodecOptions combined = CodecOptions.builder()
                .set(ZstdCodec.WINDOW_LOG, codec.minimumWindowLog())
                .set(ZstdCodec.HASH_LOG, codec.minimumHashLog())
                .set(ZstdCodec.CHAIN_LOG, codec.minimumChainLog())
                .set(ZstdCodec.SEARCH_LOG, codec.minimumSearchLog())
                .set(ZstdCodec.MIN_MATCH, codec.minimumMatchLength())
                .set(ZstdCodec.TARGET_LENGTH, 0L)
                .set(ZstdCodec.STRATEGY, ZstdStrategy.BT_OPT)
                .set(ZstdCodec.JOB_SIZE, 0L)
                .set(ZstdCodec.OVERLAP_LOG, 0L)
                .set(ZstdCodec.CONTENT_SIZE, false)
                .set(ZstdCodec.DICTIONARY_ID, false)
                .set(ZstdCodec.LONG_DISTANCE_MATCHING, false)
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length)
                .build();
        assertArrayEquals(input, transferRoundTrip(codec, input, combined));

        for (ZstdStrategy strategy : ZstdStrategy.values()) {
            CodecOptions options = CodecOptions.builder()
                    .set(ZstdCodec.STRATEGY, strategy)
                    .build();
            assertArrayEquals(input, transferRoundTrip(codec, input, options), strategy.name());
        }

        assertInvalidCompressionOption(codec, ZstdCodec.WINDOW_LOG, codec.minimumWindowLog() - 1L);
        assertInvalidCompressionOption(codec, ZstdCodec.HASH_LOG, codec.maximumHashLog() + 1L);
        assertInvalidCompressionOption(codec, ZstdCodec.TARGET_LENGTH, -1L);
        assertInvalidCompressionOption(codec, ZstdCodec.JOB_SIZE, (long) Integer.MAX_VALUE + 1L);
        assertInvalidCompressionOption(codec, ZstdCodec.OVERLAP_LOG, 10L);
    }

    /// Verifies an invalid numeric compression option is rejected before an encoder is returned.
    private static void assertInvalidCompressionOption(
            ZstdCodec codec,
            CodecOption<Long> option,
            long value
    ) {
        CodecOptions options = CodecOptions.builder().set(option, value).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openEncoder(
                        Channels.newChannel(new ByteArrayOutputStream()),
                        options,
                        ChannelOwnership.RETAIN
                )
        );
    }

    /// Round-trips bytes through channel transfer with configured compression options.
    private static byte[] transferRoundTrip(
            ZstdCodec codec,
            byte[] input,
            CodecOptions compressionOptions
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressed),
                compressionOptions
        );

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray())),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
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
