// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel.Directive;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
        assertEquals(ZstdCodec.NAME, codec.format().name());
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
        assertEquals(ZstdCodec.class, CompressionFormats.require(ZstdCodec.NAME).defaultCodec().getClass());
    }

    /// Verifies Zstandard metadata and ByteBuffer one-shot compression.
    @Test
    public void byteBufferRoundTrip() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd bytebuffer".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();
        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));

        codec.compress(source, compressed);
        compressed.flip();
        assertEquals(true, codec.format().matches(compressed.duplicate()));

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

    /// Verifies that ByteBuffer operations reject read-only target buffers consistently.
    @Test
    public void byteBufferReadOnlyTarget() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd readonly target".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();

        ByteBuffer readOnlyCompressed = ByteBuffer
                .allocateDirect((int) codec.maxCompressedSize(input.length))
                .asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> codec.compress(source, readOnlyCompressed));

        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));
        codec.compress(source.rewind(), compressed);
        compressed.flip();

        ByteBuffer readOnlyDecompressed = ByteBuffer.allocateDirect(input.length).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> codec.decompress(compressed, readOnlyDecompressed));
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
        CompressionDictionary configuredDictionary =
                Objects.requireNonNull(codec.dictionary());
        assertArrayEquals(new byte[]{1, 2, 3}, configuredDictionary.bytes());

        byte[] returned = configuredDictionary.bytes();
        returned[1] = 9;
        assertArrayEquals(
                new byte[]{1, 2, 3},
                Objects.requireNonNull(codec.dictionary()).bytes()
        );
        assertNull(codec.withoutDictionary().dictionary());
        assertEquals(-1L, new ZstdCodec().withCompressionLevel(-1L).compressionLevel());
        assertEquals(ZstdCodec.DEFAULT_COMPRESSION_LEVEL, new ZstdCodec().compressionLevel());
    }

    /// Verifies immutable channel configuration and endpoint ownership.
    @Test
    public void channelConfiguration() throws IOException {
        byte[] dictionaryBytes = "shared zstd dictionary".getBytes(StandardCharsets.UTF_8);
        CompressionDictionary dictionary = CompressionDictionary.of(dictionaryBytes);
        byte[] input = "shared zstd dictionary payload".getBytes(StandardCharsets.UTF_8);
        ZstdCodec codec = ZstdCodec.builder()
                .compressionLevel(-2L)
                .dictionary(dictionary)
                .frameChecksum(true)
                .workerCount(0)
                .build();

        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(input));
             WritableByteChannel target = Channels.newChannel(compressedBytes)) {
            codec.compress(source, target);
            assertEquals(true, source.isOpen());
            assertEquals(true, target.isOpen());
        }

        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        try (ReadableByteChannel source = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        ); WritableByteChannel target = Channels.newChannel(decodedBytes)) {
            codec.decompress(source, target);
        }

        assertArrayEquals(input, decodedBytes.toByteArray());
        assertEquals(-2L, codec.compressionLevel());
        assertEquals(dictionary, codec.dictionary());
        assertTrue(codec.frameChecksum());
        assertEquals(0, codec.workerCount());
        assertTrue(codec.minimumCompressionLevel() <= -2);
        assertTrue(codec.maximumCompressionLevel() >= -2);
        assertEquals(Zstd.defaultCompressionLevel(), codec.defaultCompressionLevel());
    }

    /// Verifies multiple pledged frames reuse one encoder and decode as one concatenated stream.
    @Test
    public void pledgedMultiFrameRoundTrip() throws IOException {
        byte[] frame = (
                "pledged Zstandard frame 0123456789abcdef;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);
        ZstdCodec codec = new ZstdCodec();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                frame.length,
                ChannelOwnership.RETAIN
        );
        encoder.encode(ByteBuffer.wrap(frame), Directive.END_FRAME);
        int firstFrameSize = compressed.size();
        assertTrue(encoder.isOpen());
        encoder.encode(ByteBuffer.wrap(frame), Directive.END_FRAME);
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

    /// Recognizes the standard frame magic and all sixteen skippable frame identifiers without changing buffers.
    @Test
    public void recognizesEveryFrameMagic() {
        ZstdCodec codec = new ZstdCodec();
        ByteBuffer standard = ByteBuffer.wrap(new byte[]{
                0x28, (byte) 0xb5, 0x2f, (byte) 0xfd
        }).asReadOnlyBuffer();
        assertTrue(codec.format().matches(standard));
        assertEquals(0, standard.position());

        for (int id = 0; id < 16; id++) {
            ByteBuffer prefix = ByteBuffer.wrap(new byte[]{
                    0x01, (byte) (0x50 + id), 0x2a, 0x4d, 0x18, 0x02
            }).asReadOnlyBuffer();
            prefix.position(1);
            prefix.limit(5);
            prefix.mark();

            assertTrue(codec.format().matches(prefix), Integer.toString(id));
            assertEquals(1, prefix.position(), Integer.toString(id));
            assertEquals(5, prefix.limit(), Integer.toString(id));
            prefix.reset();
            assertEquals(
                    ZstdCodec.class,
                    Objects.requireNonNull(CompressionFormats.detect(prefix)).defaultCodec().getClass(),
                    Integer.toString(id)
            );
        }

        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{0x50, 0x2a, 0x4d})));
        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{0x4f, 0x2a, 0x4d, 0x18})));
        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{0x60, 0x2a, 0x4d, 0x18})));
    }

    /// Verifies standard and skippable frame inspection without changing buffer state.
    @Test
    public void frameInspection() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] content = (
                "inspectable Zstandard frame 0123456789abcdef;"
        ).repeat(128).getBytes(StandardCharsets.UTF_8);
        ZstdCodec checksumCodec = codec.withFrameChecksum(true);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = checksumCodec.openEncoder(
                Channels.newChannel(encoded),
                content.length
        )) {
            encoder.write(ByteBuffer.wrap(content));
        }

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

        ZstdCodec noContentSize = codec.toBuilder().contentSize(false).build();
        ByteArrayOutputStream noContentEncoded = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = noContentSize.openEncoder(
                Channels.newChannel(noContentEncoded),
                content.length
        )) {
            encoder.write(ByteBuffer.wrap(content));
        }
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
        byte[] prefixedFrame = skippableAndFrame.toByteArray();
        assertEquals(
                ZstdCodec.class,
                Objects.requireNonNull(CompressionFormats.detect(ByteBuffer.wrap(prefixedFrame))).defaultCodec().getClass()
        );
        try (InputStream decodedAfterSkippable = CompressionFormats.decompressFrom(
                new ByteArrayInputStream(prefixedFrame)
        )) {
            assertArrayEquals(content, decodedAfterSkippable.readAllBytes());
        }

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

    /// Verifies magicless framing across multi-frame channels, inspection, and native interoperability.
    @Test
    public void magiclessFrameControl() throws IOException {
        ZstdCodec standardCodec = new ZstdCodec();
        ZstdCodec codec = standardCodec.toBuilder()
                .frameFormat(ZstdFrameFormat.MAGICLESS)
                .frameChecksum(true)
                .build();
        byte[] first = "first magicless frame".repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second magicless frame".repeat(96).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(encoded),
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(first));
            encoder.finishFrame();
            encoder.write(ByteBuffer.wrap(second));
        }

        byte[] frames = encoded.toByteArray();
        assertEquals(false, standardCodec.format().matches(ByteBuffer.wrap(frames)));
        assertThrows(IOException.class, () -> standardCodec.frameInfo(ByteBuffer.wrap(frames)));

        ByteBuffer inspection = ByteBuffer.wrap(frames);
        int initialPosition = inspection.position();
        ZstdStandardFrameInfo firstInfo = (ZstdStandardFrameInfo) standardCodec.frameInfo(
                inspection,
                ZstdFrameFormat.MAGICLESS
        );
        long firstFrameSize = standardCodec.frameCompressedSize(
                inspection,
                ZstdFrameFormat.MAGICLESS
        );
        assertTrue(firstInfo.headerSize() >= 2);
        assertTrue(firstFrameSize < frames.length);
        assertEquals(initialPosition, inspection.position());

        inspection.position(Math.toIntExact(firstFrameSize));
        assertEquals(
                frames.length - firstFrameSize,
                standardCodec.frameCompressedSize(inspection, ZstdFrameFormat.MAGICLESS)
        );

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(frames)),
                Channels.newChannel(decoded)
        );
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(first);
        expected.writeBytes(second);
        assertArrayEquals(expected.toByteArray(), decoded.toByteArray());

        ByteBuffer directSource = ByteBuffer.allocateDirect(first.length);
        directSource.put(first).flip();
        ByteBuffer compressedBuffer = codec.compress(directSource);
        ByteBuffer decompressedBuffer = codec.decompress(compressedBuffer, first.length);
        byte[] oneShotOutput = new byte[decompressedBuffer.remaining()];
        decompressedBuffer.get(oneShotOutput);
        assertArrayEquals(first, oneShotOutput);

        byte[] nativeFrame = new byte[Math.toIntExact(firstFrameSize) + Integer.BYTES];
        nativeFrame[0] = 0x28;
        nativeFrame[1] = (byte) 0xb5;
        nativeFrame[2] = 0x2f;
        nativeFrame[3] = (byte) 0xfd;
        System.arraycopy(frames, 0, nativeFrame, Integer.BYTES, Math.toIntExact(firstFrameSize));
        assertArrayEquals(first, Zstd.decompress(nativeFrame, first.length));
    }

    /// Verifies advanced Zstandard context parameters, immutable state, and every strategy.
    @Test
    public void advancedCompressionConfiguration() throws IOException {
        assertTrue(ZstdCodec.MINIMUM_WINDOW_LOG <= ZstdCodec.MAXIMUM_WINDOW_LOG);
        assertTrue(ZstdCodec.MINIMUM_HASH_LOG <= ZstdCodec.MAXIMUM_HASH_LOG);
        assertTrue(ZstdCodec.MINIMUM_CHAIN_LOG <= ZstdCodec.MAXIMUM_CHAIN_LOG);
        assertTrue(ZstdCodec.MINIMUM_SEARCH_LOG <= ZstdCodec.MAXIMUM_SEARCH_LOG);
        assertTrue(ZstdCodec.MINIMUM_MATCH_LENGTH <= ZstdCodec.MAXIMUM_MATCH_LENGTH);
        assertEquals(18, ZstdCodec.MAXIMUM_HASH_LOG);
        assertEquals(17, ZstdCodec.MAXIMUM_CHAIN_LOG);
        assertEquals(10, ZstdCodec.MAXIMUM_SEARCH_LOG);

        byte[] input = (
                "advanced Zstandard compression parameters 0123456789abcdef;"
        ).repeat(1_024).getBytes(StandardCharsets.UTF_8);
        ZstdCodec combined = ZstdCodec.builder()
                .windowLog(ZstdCodec.MINIMUM_WINDOW_LOG)
                .hashLog(ZstdCodec.MINIMUM_HASH_LOG)
                .chainLog(ZstdCodec.MINIMUM_CHAIN_LOG)
                .searchLog(ZstdCodec.MINIMUM_SEARCH_LOG)
                .minimumMatch(ZstdCodec.MINIMUM_MATCH_LENGTH)
                .targetLength(0L)
                .strategy(ZstdStrategy.BT_OPT)
                .jobSize(0L)
                .overlapLog(0)
                .contentSize(false)
                .dictionaryId(false)
                .longDistanceMatching(false)
                .longDistanceHashLog(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_LOG)
                .longDistanceMinimumMatch(ZstdCodec.MINIMUM_LONG_DISTANCE_MATCH_LENGTH)
                .longDistanceBucketSizeLog(ZstdCodec.MINIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG)
                .longDistanceHashRateLog(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_RATE_LOG)
                .build();
        assertEquals(ZstdCodec.MINIMUM_WINDOW_LOG, combined.windowLog());
        assertEquals(ZstdStrategy.BT_OPT, combined.strategy());
        assertEquals(false, combined.contentSize());
        assertEquals(false, combined.dictionaryId());
        assertArrayEquals(input, transferRoundTrip(combined, input));

        for (ZstdStrategy strategy : ZstdStrategy.values()) {
            ZstdCodec configured = ZstdCodec.builder().strategy(strategy).build();
            assertArrayEquals(input, transferRoundTrip(configured, input), strategy.name());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().windowLog(ZstdCodec.MINIMUM_WINDOW_LOG - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().hashLog(ZstdCodec.MAXIMUM_HASH_LOG + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().targetLength(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().jobSize((long) Integer.MAX_VALUE + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().overlapLog(10));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().longDistanceHashLog(
                        ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_LOG + 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().longDistanceMinimumMatch(
                        ZstdCodec.MINIMUM_LONG_DISTANCE_MATCH_LENGTH - 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().longDistanceBucketSizeLog(
                        ZstdCodec.MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG + 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().longDistanceHashRateLog(
                        ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG + 1L
                )
        );

        ZstdCodec incompatibleLongDistanceTable = ZstdCodec.builder()
                .longDistanceHashLog(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_LOG)
                .longDistanceBucketSizeLog(ZstdCodec.MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG)
                .build();
        assertThrows(IllegalArgumentException.class, incompatibleLongDistanceTable::newEncoder);
    }

    /// Round-trips bytes through channel transfer with one immutable codec configuration.
    private static byte[] transferRoundTrip(
            ZstdCodec codec,
            byte[] input
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressed)
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
