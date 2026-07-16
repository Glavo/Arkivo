// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionLevelCodec;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.CompressionStrategyCodec;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DictionaryCompressionCodec;
import org.glavo.arkivo.codec.EncodeDirective;
import org.glavo.arkivo.codec.FlushableCompressionEncoder;
import org.glavo.arkivo.codec.FramedCompressionDecoder;
import org.glavo.arkivo.codec.FramedCompressionEncoder;
import org.glavo.arkivo.codec.PledgedSourceSizeCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the first-class channel contract across all installed codec providers.
@NotNullByDefault
final class CodecChannelContractTest {
    /// Verifies channel transfer, counters, endpoint ownership, and interface consistency.
    @Test
    void roundTripsEveryBidirectionalCodecThroughChannels() throws IOException {
        byte[] input = ("Arkivo channel codec contract: " + "0123456789".repeat(64))
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            ReadableByteChannel uncompressedSource = Channels.newChannel(new ByteArrayInputStream(input));
            WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
            CodecTransferResult compression = codec.compress(uncompressedSource, compressedTarget);

            assertEquals(input.length, compression.inputBytes(), codec.name());
            assertEquals(compressedBytes.size(), compression.outputBytes(), codec.name());
            assertTrue(uncompressedSource.isOpen(), codec.name());
            assertTrue(compressedTarget.isOpen(), codec.name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            ReadableByteChannel compressedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            WritableByteChannel decodedTarget = Channels.newChannel(decodedBytes);
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            CodecTransferResult decompression = decoderCodec.decompress(
                    compressedSource,
                    decodedTarget
            );

            assertEquals(input.length, decompression.outputBytes(), codec.name());
            assertTrue(compressedSource.isOpen(), codec.name());
            assertTrue(decodedTarget.isOpen(), codec.name());
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
        }
    }

    /// Verifies logical input consumption is distinct from backing-channel read-ahead for every decoder.
    @Test
    void tracksLogicalAndSourceInputBytesAcrossAllCodecs() throws IOException {
        byte[] content = (
                "logical compressed input counters 0123456789abcdef;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            byte[] compressed = compressFrame(codec, content);
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, content.length);
            try (DecompressingReadableByteChannel decoder = decoderCodec.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(compressed)),
                    ChannelOwnership.RETAIN
            )) {
                assertUnconsumedInput(decoder, compressed, codec.name());
                ByteBuffer target = ByteBuffer.allocate(7);
                while (true) {
                    target.clear();
                    int read = decoder.read(target);
                    assertUnconsumedInput(decoder, compressed, codec.name());
                    if (read < 0) {
                        break;
                    }
                    assertTrue(read > 0, codec.name());
                }

                if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                    assertTrue(decoder.inputBytes() <= compressed.length, codec.name());
                } else {
                    assertEquals(compressed.length, decoder.inputBytes(), codec.name());
                }
                assertEquals(compressed.length, decoder.sourceBytes(), codec.name());
                assertEquals(content.length, decoder.outputBytes(), codec.name());
            }
        }
    }

    /// Verifies flushable encoders through explicit incremental directives.
    @Test
    void flushesIncrementalFramesForEveryFlushableCodec() throws IOException {
        Set<String> flushCodecs = Set.of("deflate", "deflate64", "gzip", "xz", "zlib", "zstd");
        byte[] first = ("first flushed segment " + "0123456789abcdef".repeat(512))
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = " and final segment".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean flushable = hasFlushableEncoder(codec);
            assertEquals(flushCodecs.contains(codec.name()), flushable, codec.name());
            if (!flushable) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(compressedBytes);
            CompressingWritableByteChannel encoder = codec.openEncoder(target);
            try {
                ByteBuffer firstSource = ByteBuffer.allocateDirect(first.length).put(first).flip();
                int beforeFlush = compressedBytes.size();
                long outputBeforeFlush = encoder.outputBytes();
                CodecResult flushed = encoder.encode(firstSource, EncodeDirective.FLUSH);
                int flushedSize = compressedBytes.size();

                assertFalse(firstSource.hasRemaining(), codec.name());
                assertEquals(CodecStatus.FLUSHED, flushed.status(), codec.name());
                assertEquals(first.length, flushed.inputBytes(), codec.name());
                assertEquals(flushedSize - beforeFlush, flushed.outputBytes(), codec.name());
                assertEquals(outputBeforeFlush + flushed.outputBytes(), encoder.outputBytes(), codec.name());
                assertEquals(first.length, encoder.inputBytes(), codec.name());
                assertEquals(flushedSize, encoder.outputBytes(), codec.name());
                assertTrue(flushedSize > 0, codec.name());
                assertTrue(encoder.isOpen(), codec.name());
                assertTrue(target.isOpen(), codec.name());
                assertArrayEquals(
                        first,
                        decodeAvailablePrefix(codec, compressedBytes.toByteArray(), first.length),
                        codec.name()
                );

                int beforeRepeatedFlush = compressedBytes.size();
                CodecResult repeatedFlush = encoder.encode(ByteBuffer.allocateDirect(0), EncodeDirective.FLUSH);
                assertEquals(CodecStatus.FLUSHED, repeatedFlush.status(), codec.name());
                assertEquals(0L, repeatedFlush.inputBytes(), codec.name());
                assertEquals(
                        compressedBytes.size() - beforeRepeatedFlush,
                        repeatedFlush.outputBytes(),
                        codec.name()
                );
                assertTrue(encoder.isOpen(), codec.name());

                int beforeContinue = compressedBytes.size();
                CodecResult continued = encoder.encode(ByteBuffer.wrap(second), EncodeDirective.CONTINUE);
                assertEquals(CodecStatus.ACTIVE, continued.status(), codec.name());
                assertEquals(second.length, continued.inputBytes(), codec.name());
                assertEquals(compressedBytes.size() - beforeContinue, continued.outputBytes(), codec.name());

                int beforeFinish = compressedBytes.size();
                CodecResult finished = encoder.encode(ByteBuffer.allocate(0), EncodeDirective.END_FRAME);
                assertEquals(CodecStatus.FRAME_FINISHED, finished.status(), codec.name());
                assertEquals(0L, finished.inputBytes(), codec.name());
                assertEquals(compressedBytes.size() - beforeFinish, finished.outputBytes(), codec.name());
                assertEquals(first.length + second.length, encoder.inputBytes(), codec.name());
                assertEquals(compressedBytes.size(), encoder.outputBytes(), codec.name());

                boolean multiFrame = hasFramedEncoder(codec);
                assertEquals(multiFrame, encoder.isOpen(), codec.name());
                int completeSize = compressedBytes.size();
                encoder.finish();
                assertFalse(encoder.isOpen(), codec.name());
                assertEquals(completeSize, compressedBytes.size(), codec.name());
                assertTrue(target.isOpen(), codec.name());

                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.writeBytes(first);
                expected.writeBytes(second);
                assertArrayEquals(expected.toByteArray(), decodedBytes.toByteArray(), codec.name());
            } finally {
                encoder.close();
            }
        }
    }

    /// Verifies multi-frame encoders and concatenated-frame decoders share one cumulative context.
    @Test
    void roundTripsMultipleFramesForEveryFramedCodec() throws IOException {
        Set<String> multiFrameCodecs = Set.of("bzip2", "gzip", "xz", "zstd");
        Set<String> concatenatedFrameCodecs = Set.of("bzip2", "gzip", "xz", "zstd");
        byte[] first = "first independent frame".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second independent frame".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean expected = multiFrameCodecs.contains(codec.name());
            assertEquals(expected, hasFramedEncoder(codec), codec.name());
            assertEquals(
                    concatenatedFrameCodecs.contains(codec.name()),
                    hasFramedDecoder(codec),
                    codec.name()
            );
            if (!expected) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(compressedBytes);
            CompressingWritableByteChannel encoder = codec.openEncoder(target);
            CodecResult firstResult = encoder.encode(ByteBuffer.wrap(first), EncodeDirective.END_FRAME);
            int firstFrameSize = compressedBytes.size();

            assertEquals(CodecStatus.FRAME_FINISHED, firstResult.status(), codec.name());
            assertTrue(encoder.isOpen(), codec.name());
            assertEquals(first.length, encoder.inputBytes(), codec.name());
            encoder.flush();
            assertEquals(firstFrameSize, compressedBytes.size(), codec.name());

            CodecResult secondResult = encoder.encode(ByteBuffer.wrap(second), EncodeDirective.END_FRAME);
            int completeSize = compressedBytes.size();
            assertEquals(CodecStatus.FRAME_FINISHED, secondResult.status(), codec.name());
            assertTrue(encoder.isOpen(), codec.name());
            assertEquals(first.length + second.length, encoder.inputBytes(), codec.name());
            assertEquals(completeSize, encoder.outputBytes(), codec.name());

            encoder.finish();
            assertFalse(encoder.isOpen(), codec.name());
            assertEquals(completeSize, compressedBytes.size(), codec.name());
            assertTrue(target.isOpen(), codec.name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
            );
            ByteArrayOutputStream expectedBytes = new ByteArrayOutputStream();
            expectedBytes.writeBytes(first);
            expectedBytes.writeBytes(second);
            assertArrayEquals(expectedBytes.toByteArray(), decodedBytes.toByteArray(), codec.name());
        }
    }

    /// Verifies explicit empty frames and lazy next-frame creation for every multi-frame encoder.
    @Test
    void preservesExplicitEmptyFramesWithoutAddingTrailingFrames() throws IOException {
        byte[] content = "content after an explicit empty frame".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!hasFramedEncoder(codec)) {
                continue;
            }

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel encoder = codec.openEncoder(
                    Channels.newChannel(compressed),
                    ChannelOwnership.RETAIN
            )) {
                encoder.finishFrame();
                int emptyFrameSize = compressed.size();
                assertTrue(emptyFrameSize > 0, codec.name());
                assertTrue(encoder.isOpen(), codec.name());

                assertEquals(0, encoder.write(ByteBuffer.allocate(0)), codec.name());
                encoder.flush();
                assertEquals(emptyFrameSize, compressed.size(), codec.name());

                CodecResult finished = encoder.encode(
                        ByteBuffer.wrap(content),
                        EncodeDirective.END_FRAME
                );
                assertEquals(CodecStatus.FRAME_FINISHED, finished.status(), codec.name());
                int completeSize = compressed.size();
                encoder.finish();
                assertEquals(completeSize, compressed.size(), codec.name());
            }

            try (DecompressingReadableByteChannel decoder = codec.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray()))
            )) {
                CodecResult empty = decoder.decode(
                        ByteBuffer.allocate(3),
                        DecodeDirective.STOP_AT_FRAME
                );
                assertEquals(CodecStatus.FRAME_FINISHED, empty.status(), codec.name());
                assertEquals(0L, empty.outputBytes(), codec.name());

                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                while (true) {
                    ByteBuffer target = ByteBuffer.allocate(3);
                    CodecResult result = decoder.decode(target, DecodeDirective.STOP_AT_FRAME);
                    target.flip();
                    byte[] chunk = new byte[target.remaining()];
                    target.get(chunk);
                    decoded.writeBytes(chunk);
                    if (result.status() == CodecStatus.FRAME_FINISHED) {
                        break;
                    }
                    assertEquals(CodecStatus.ACTIVE, result.status(), codec.name());
                }
                assertArrayEquals(content, decoded.toByteArray(), codec.name());

                CodecResult ended = decoder.decode(
                        ByteBuffer.allocate(1),
                        DecodeDirective.STOP_AT_FRAME
                );
                assertEquals(CodecStatus.END_OF_INPUT, ended.status(), codec.name());
                assertEquals(compressed.size(), decoder.inputBytes(), codec.name());
            }
        }
    }

    /// Verifies concatenated decoders can expose validated boundaries, including an empty first frame.
    @Test
    void stopsIncrementalDecodingAtAdvertisedFrameBoundaries() throws IOException {
        byte[][] frames = {
                new byte[0],
                "first decoded frame with several output chunks".getBytes(StandardCharsets.UTF_8),
                "second decoded frame".getBytes(StandardCharsets.UTF_8)
        };
        int totalOutputSize = frames[1].length + frames[2].length;

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!hasFramedDecoder(codec)) {
                continue;
            }

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[][] encodedFrames = new byte[frames.length][];
            for (int index = 0; index < frames.length; index++) {
                encodedFrames[index] = compressFrame(codec, frames[index]);
                compressed.writeBytes(encodedFrames[index]);
            }
            DecompressionLimits limits =
                    DecompressionLimits.ofMaximumOutputSize(totalOutputSize);
            byte[] compressedStream = compressed.toByteArray();

            try (DecompressingReadableByteChannel decoder = codec.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(compressedStream)),
                    limits,
                    ChannelOwnership.RETAIN
            )) {
                long completedInputBytes = 0L;
                for (int frameIndex = 0; frameIndex < frames.length; frameIndex++) {
                    byte[] expectedFrame = frames[frameIndex];
                    ByteArrayOutputStream decodedFrame = new ByteArrayOutputStream();
                    int operations = 0;
                    while (true) {
                        ByteBuffer target = ByteBuffer.allocate(5);
                        CodecResult result = decoder.decode(target, DecodeDirective.STOP_AT_FRAME);
                        target.flip();
                        byte[] chunk = new byte[target.remaining()];
                        target.get(chunk);
                        decodedFrame.writeBytes(chunk);
                        assertEquals(chunk.length, result.outputBytes(), codec.name());
                        assertUnconsumedInput(decoder, compressedStream, codec.name());
                        assertTrue(++operations < 100, codec.name());

                        if (result.status() == CodecStatus.FRAME_FINISHED) {
                            break;
                        }
                        assertEquals(CodecStatus.ACTIVE, result.status(), codec.name());
                        assertTrue(result.inputBytes() > 0L || result.outputBytes() > 0L, codec.name());
                    }
                    assertArrayEquals(expectedFrame, decodedFrame.toByteArray(), codec.name());
                    completedInputBytes += encodedFrames[frameIndex].length;
                    assertEquals(completedInputBytes, decoder.inputBytes(), codec.name());
                    if (frameIndex == 0) {
                        assertTrue(decoder.sourceBytes() > decoder.inputBytes(), codec.name());
                    }
                }

                CodecResult ended = decoder.decode(
                        ByteBuffer.allocate(5),
                        DecodeDirective.STOP_AT_FRAME
                );
                assertEquals(CodecStatus.END_OF_INPUT, ended.status(), codec.name());
                assertEquals(totalOutputSize, decoder.outputBytes(), codec.name());
                assertEquals(compressed.size(), decoder.inputBytes(), codec.name());
                assertEquals(compressed.size(), decoder.sourceBytes(), codec.name());
            }
        }
    }

    /// Verifies allocating ByteBuffer one-shot operations across every bidirectional codec.
    @Test
    void roundTripsEveryCodecThroughAllocatingByteBuffers() throws IOException {
        byte[] input = (
                "allocating ByteBuffer codec contract 0123456789abcdef;"
        ).repeat(1_024).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            ByteBuffer source = ByteBuffer.allocateDirect(input.length + 4);
            source.position(2);
            source.put(input);
            source.flip();
            source.position(2);
            ByteBuffer compressed = codec.compress(source);
            assertEquals(source.limit(), source.position(), codec.name());
            assertEquals(0, compressed.position(), codec.name());
            assertTrue(compressed.hasRemaining(), codec.name());

            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            ByteBuffer decoded = decoderCodec.decompress(compressed, input.length);
            if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                assertTrue(compressed.position() <= compressed.limit(), codec.name());
            } else {
                assertEquals(compressed.limit(), compressed.position(), codec.name());
            }
            assertEquals(0, decoded.position(), codec.name());
            byte[] actual = new byte[decoded.remaining()];
            decoded.get(actual);
            assertArrayEquals(input, actual, codec.name());

            ByteBuffer overflowSource = codec.compress(ByteBuffer.wrap(input));
            assertThrows(
                    DecompressionLimitException.class,
                    () -> decoderCodec.decompress(overflowSource, input.length - 1L),
                    codec.name()
            );

            ByteBuffer emptyCompressed = codec.compress(ByteBuffer.allocate(0));
            CompressionCodec emptyDecoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, 0L);
            ByteBuffer emptyDecoded = emptyDecoderCodec.decompress(emptyCompressed, 0L);
            assertEquals(0, emptyDecoded.remaining(), codec.name());
        }
    }

    /// Verifies one-shot frame decompression preserves following compressed frames across every codec.
    @Test
    void decompressesOneFrameAtATimeAcrossEveryCodec() throws IOException {
        byte[] first = (
                "first one-shot frame 0123456789abcdef;"
        ).repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] second = (
                "second one-shot frame fed from the same ByteBuffer;"
        ).repeat(32).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                continue;
            }

            byte[] emptyEncoded = compressFrame(codec, new byte[0]);
            byte[] firstEncoded = compressFrame(codec, first);
            byte[] secondEncoded = compressFrame(codec, second);

            ByteBuffer emptySource = ByteBuffer.allocate(emptyEncoded.length + firstEncoded.length);
            emptySource.put(emptyEncoded).put(firstEncoded).flip();
            ByteBuffer emptyDecoded = codec.decompressFrame(emptySource, 0L);
            assertEquals(0, emptyDecoded.remaining(), codec.name());
            assertEquals(emptyEncoded.length, emptySource.position(), codec.name());

            ByteBuffer emptyFixedSource = ByteBuffer.allocate(emptyEncoded.length + firstEncoded.length);
            emptyFixedSource.put(emptyEncoded).put(firstEncoded).flip();
            codec.decompressFrame(emptyFixedSource, ByteBuffer.allocate(0));
            assertEquals(emptyEncoded.length, emptyFixedSource.position(), codec.name());

            ByteBuffer source = ByteBuffer.allocate(2 + firstEncoded.length + secondEncoded.length + 3);
            source.position(2);
            source.put(firstEncoded);
            source.put(secondEncoded);
            source.flip();
            source.position(2);
            source.limit(2 + firstEncoded.length + secondEncoded.length);
            DecompressionLimits firstLimits =
                    DecompressionLimits.ofMaximumOutputSize(first.length);

            ByteBuffer firstDecoded = codec.decompressFrame(source, firstLimits);
            assertArrayEquals(first, bufferBytes(firstDecoded), codec.name());
            assertEquals(2 + firstEncoded.length, source.position(), codec.name());

            ByteBuffer secondTarget = ByteBuffer.allocate(second.length);
            codec.decompressFrame(source, secondTarget);
            secondTarget.flip();
            assertArrayEquals(second, bufferBytes(secondTarget), codec.name());
            assertEquals(source.limit(), source.position(), codec.name());

            ByteBuffer limitedSource = ByteBuffer.wrap(firstEncoded);
            assertThrows(
                    DecompressionLimitException.class,
                    () -> codec.decompressFrame(limitedSource, first.length - 1L),
                    codec.name()
            );

            ByteBuffer overflowSource = ByteBuffer.wrap(firstEncoded);
            assertThrows(
                    java.nio.BufferOverflowException.class,
                    () -> codec.decompressFrame(
                            overflowSource,
                            ByteBuffer.allocate(first.length - 1)
                    ),
                    codec.name()
            );
        }
    }

    /// Verifies fixed-buffer operations across heap, direct, sliced, and read-only endpoint combinations.
    @Test
    void roundTripsHeapAndDirectFixedBuffersAcrossEveryCodec() throws IOException {
        byte[] input = ("fixed codec buffers " + "abcdef".repeat(256)).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {

            assertFixedBufferRoundTrip(codec, input, false, false, false, false);
            assertFixedBufferRoundTrip(codec, input, false, true, true, true);
            assertFixedBufferRoundTrip(codec, input, true, false, true, false);
            assertFixedBufferRoundTrip(codec, input, true, true, false, true);

            ByteBuffer compressionSource = ByteBuffer.wrap(input);
            ByteBuffer readOnlyCompressionTarget = ByteBuffer.allocate(input.length * 4 + 8_192)
                    .asReadOnlyBuffer();
            assertThrows(
                    ReadOnlyBufferException.class,
                    () -> codec.compress(compressionSource, readOnlyCompressionTarget),
                    codec.name()
            );
            assertEquals(0, compressionSource.position(), codec.name());

            ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
            int compressedPosition = compressed.position();
            ByteBuffer readOnlyDecompressionTarget = ByteBuffer.allocate(input.length).asReadOnlyBuffer();
            assertThrows(
                    ReadOnlyBufferException.class,
                    () -> decompressFixed(codec, compressed, readOnlyDecompressionTarget, input.length),
                    codec.name()
            );
            assertEquals(compressedPosition, compressed.position(), codec.name());

            ByteBuffer sameCompressionBuffer = ByteBuffer.allocate(input.length + 8_192);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.compress(sameCompressionBuffer, sameCompressionBuffer),
                    codec.name()
            );
            ByteBuffer sameDecompressionBuffer = codec.compress(ByteBuffer.wrap(input));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decompressFixed(codec, sameDecompressionBuffer, sameDecompressionBuffer, input.length),
                    codec.name()
            );
        }
    }

    /// Verifies one fixed-buffer layout while preserving nonzero source and target range offsets.
    private static void assertFixedBufferRoundTrip(
            CompressionCodec codec,
            byte[] input,
            boolean directSource,
            boolean directCompressed,
            boolean directDecoded,
            boolean readOnlySources
    ) throws IOException {
        int sourceOffset = 3;
        ByteBuffer sourceStorage = allocateBuffer(directSource, sourceOffset + input.length + 5);
        sourceStorage.position(sourceOffset);
        sourceStorage.put(input);
        sourceStorage.flip();
        sourceStorage.position(sourceOffset);
        ByteBuffer source = readOnlySources ? sourceStorage.asReadOnlyBuffer() : sourceStorage;

        int compressedOffset = 5;
        int compressedCapacity = input.length * 4 + 8_192;
        ByteBuffer compressed = allocateBuffer(
                directCompressed,
                compressedOffset + compressedCapacity + 7
        );
        compressed.position(compressedOffset);
        compressed.limit(compressedOffset + compressedCapacity);
        codec.compress(source, compressed);
        assertEquals(source.limit(), source.position(), codec.name());

        int compressedEnd = compressed.position();
        ByteBuffer compressedSource = compressed.duplicate();
        compressedSource.position(compressedOffset);
        compressedSource.limit(compressedEnd);
        if (readOnlySources) {
            compressedSource = compressedSource.asReadOnlyBuffer();
        }

        int decodedOffset = 7;
        ByteBuffer decoded = allocateBuffer(directDecoded, decodedOffset + input.length + 3);
        decoded.position(decodedOffset);
        decoded.limit(decodedOffset + input.length);
        decompressFixed(codec, compressedSource, decoded, input.length);
        assertEquals(decoded.limit(), decoded.position(), codec.name());

        ByteBuffer output = decoded.duplicate();
        output.position(decodedOffset);
        output.limit(decodedOffset + input.length);
        byte[] outputBytes = new byte[output.remaining()];
        output.get(outputBytes);
        assertArrayEquals(input, outputBytes, codec.name());
    }

    /// Decompresses through a codec carrying any externally required stream metadata.
    private static void decompressFixed(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            long decodedSize
    ) throws IOException {
        CompressionCodec decoderCodec =
                CodecContractConfigurations.decoderCodec(codec, decodedSize);
        decoderCodec.decompress(source, target);
    }

    /// Allocates one heap or direct buffer with the requested capacity.
    private static ByteBuffer allocateBuffer(boolean direct, int capacity) {
        return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
    }

    /// Verifies every compression-level codec can derive and use its documented default configuration.
    @Test
    void acceptsCompressionLevels() throws IOException {
        Set<String> levelCodecs = Set.of("bzip2", "deflate", "deflate64", "gzip", "zlib", "zstd");
        byte[] input = "configured compression level".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertEquals(levelCodecs.contains(codec.name()), codec instanceof CompressionLevelCodec, codec.name());
            if (!(codec instanceof CompressionLevelCodec levelCodec)) {
                continue;
            }

            CompressionLevelCodec configured =
                    levelCodec.withCompressionLevel(levelCodec.defaultCompressionLevel());
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel encoder = configured.openEncoder(
                    Channels.newChannel(compressedBytes),
                    ChannelOwnership.RETAIN
            )) {
                encoder.encode(ByteBuffer.wrap(input), EncodeDirective.END_FRAME);
            }

            ByteBuffer decoded = ByteBuffer.allocate(input.length);
            try (DecompressingReadableByteChannel decoder = configured.openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray()))
            )) {
                while (decoded.hasRemaining()) {
                    if (decoder.read(decoded) < 0) {
                        break;
                    }
                }
            }
            assertArrayEquals(input, decoded.array(), codec.name());
        }
    }

    /// Verifies Deflate-family strategy subinterfaces and observable strategy behavior.
    @Test
    void appliesCompressionStrategies() throws IOException {
        Set<String> strategyCodecs = Set.of("deflate", "gzip", "zlib");
        byte[] input = (
                "Arkivo compression strategy repeated payload 0123456789abcdef;"
        ).repeat(4_096).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean expected = strategyCodecs.contains(codec.name());
            assertEquals(expected, codec instanceof CompressionStrategyCodec, codec.name());
            if (!(codec instanceof CompressionStrategyCodec strategyCodec)) {
                continue;
            }

            long defaultSize = CompressionCodec.UNKNOWN_SIZE;
            long huffmanOnlySize = CompressionCodec.UNKNOWN_SIZE;
            for (CompressionStrategy strategy : CompressionStrategy.values()) {
                CompressionStrategyCodec configured =
                        strategyCodec.withCompressionStrategy(strategy);
                ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
                configured.compress(
                        Channels.newChannel(new ByteArrayInputStream(input)),
                        Channels.newChannel(compressedBytes)
                );

                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                configured.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                assertArrayEquals(input, decodedBytes.toByteArray(), codec.name() + ": " + strategy);
                if (strategy == CompressionStrategy.DEFAULT) {
                    defaultSize = compressedBytes.size();
                } else if (strategy == CompressionStrategy.HUFFMAN_ONLY) {
                    huffmanOnlySize = compressedBytes.size();
                }
            }
            assertTrue(defaultSize > 0L, codec.name());
            assertTrue(huffmanOnlySize > defaultSize, codec.name());

            for (CompressionStrategy strategy : Set.of(
                    CompressionStrategy.FILTERED,
                    CompressionStrategy.HUFFMAN_ONLY
            )) {
                CompressionStrategyCodec configured =
                        strategyCodec.withCompressionStrategy(strategy);
                ByteArrayOutputStream emptyCompressed = new ByteArrayOutputStream();
                try (CompressingWritableByteChannel encoder = configured.openEncoder(
                        Channels.newChannel(emptyCompressed),
                        ChannelOwnership.RETAIN
                )) {
                    if (strategy == CompressionStrategy.HUFFMAN_ONLY) {
                        encoder.flush();
                    }
                    encoder.finish();
                }
                ByteArrayOutputStream emptyDecoded = new ByteArrayOutputStream();
                configured.decompress(
                        Channels.newChannel(new ByteArrayInputStream(emptyCompressed.toByteArray())),
                        Channels.newChannel(emptyDecoded)
                );
                assertEquals(0, emptyDecoded.size(), codec.name() + ": " + strategy);
            }
        }
    }

    /// Verifies dictionary configuration subinterfaces and preset-dictionary round trips.
    @Test
    void roundTripsPresetDictionariesForEveryDictionaryCodec() throws IOException {
        Set<String> dictionaryCodecs = Set.of("deflate", "zlib", "zstd");
        byte[] dictionaryBytes = (
                "Arkivo shared preset dictionary: "
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] input = (
                "common-prefix/alpha/beta/gamma/0123456789;"
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).getBytes(StandardCharsets.UTF_8);
        CompressionDictionary dictionary = CompressionDictionary.of(dictionaryBytes);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertEquals(
                    dictionaryCodecs.contains(codec.name()),
                    codec instanceof DictionaryCompressionCodec,
                    codec.name()
            );
            if (!(codec instanceof DictionaryCompressionCodec dictionaryCodec)) {
                continue;
            }

            CompressionCodec configured = dictionaryCodec.withDictionary(dictionary);
            if (configured instanceof CompressionStrategyCodec strategyCodec) {
                configured = strategyCodec.withCompressionStrategy(CompressionStrategy.FILTERED);
            }
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            configured.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );
            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            configured.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
            );
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
        }
    }

    /// Verifies pledged source-size subinterfaces encode and enforce exact byte counts consistently.
    @Test
    void enforcesPledgedSourceSizes() throws IOException {
        Set<String> pledgedSizeCodecs = Set.of("lzma", "lzma-raw", "zstd");
        byte[] input = (
                "pledged source size contract 0123456789abcdef;"
        ).repeat(512).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean expected = pledgedSizeCodecs.contains(codec.name());
            assertEquals(expected, codec instanceof PledgedSourceSizeCodec, codec.name());
            if (!(codec instanceof PledgedSourceSizeCodec pledgedCodec)) {
                continue;
            }

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel encoder = pledgedCodec.openEncoder(
                    Channels.newChannel(compressed),
                    input.length,
                    ChannelOwnership.RETAIN
            )) {
                writeAll(encoder, input);
                encoder.finish();
            }
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            decoderCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray())),
                    Channels.newChannel(decoded)
            );
            assertArrayEquals(input, decoded.toByteArray(), codec.name());

            assertThrows(
                    Exception.class,
                    () -> {
                        try (CompressingWritableByteChannel encoder = pledgedCodec.openEncoder(
                                Channels.newChannel(new ByteArrayOutputStream()),
                                input.length - 1L,
                                ChannelOwnership.RETAIN
                        )) {
                            writeAll(encoder, input);
                            encoder.finish();
                        }
                    },
                    codec.name()
            );
        }
    }

    /// Verifies every decompressor enforces exact, exceeded, and invalid output limits consistently.
    @Test
    void enforcesMaximumOutputSizeAcrossAllCodecs() throws IOException {
        byte[] input = ("bounded decompression output " + "0123456789abcdef".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        long smallerLimit = input.length - 1L;

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            DecompressionLimits exactLimits =
                    DecompressionLimits.ofMaximumOutputSize(input.length);
            DecompressionLimits smallerLimits =
                    DecompressionLimits.ofMaximumOutputSize(smallerLimit);
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );

            ByteArrayOutputStream exactBytes = new ByteArrayOutputStream();
            CodecTransferResult exactResult = decoderCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(exactBytes),
                    exactLimits
            );
            assertEquals(input.length, exactResult.outputBytes(), codec.name());
            assertArrayEquals(input, exactBytes.toByteArray(), codec.name());

            ReadableByteChannel limitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream limitedBytes = new ByteArrayOutputStream();
            WritableByteChannel limitedTarget = Channels.newChannel(limitedBytes);
            DecompressionLimitException exception = assertThrows(
                    DecompressionLimitException.class,
                    () -> decoderCodec.decompress(limitedSource, limitedTarget, smallerLimits),
                    codec.name()
            );
            assertEquals(smallerLimit, exception.maximumOutputSize(), codec.name());
            assertEquals(smallerLimit, limitedBytes.size(), codec.name());

            assertTrue(limitedSource.isOpen(), codec.name());
            assertTrue(limitedTarget.isOpen(), codec.name());

            ReadableByteChannel invalidSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decoderCodec.openDecoder(
                            invalidSource,
                            DecompressionLimits.ofMaximumOutputSize(-2L),
                            ChannelOwnership.RETAIN
                    ),
                    codec.name()
            );
            assertTrue(invalidSource.isOpen(), codec.name());
        }
    }
    /// Returns all remaining bytes from a buffer without changing the original buffer state.
    private static byte[] bufferBytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Verifies the decoder's read-ahead view against the corresponding compressed-source slice.
    private static void assertUnconsumedInput(
            DecompressingReadableByteChannel decoder,
            byte[] compressed,
            String message
    ) {
        long inputBytes = decoder.inputBytes();
        long sourceBytes = decoder.sourceBytes();
        assertTrue(sourceBytes >= inputBytes, message);
        assertTrue(sourceBytes <= compressed.length, message);

        ByteBuffer unconsumed = decoder.unconsumedInput();
        assertTrue(unconsumed.isReadOnly(), message);
        assertEquals(sourceBytes - inputBytes, unconsumed.remaining(), message);
        byte[] actual = new byte[unconsumed.remaining()];
        unconsumed.get(actual);
        assertArrayEquals(
                Arrays.copyOfRange(compressed, Math.toIntExact(inputBytes), Math.toIntExact(sourceBytes)),
                actual,
                message
        );
    }

    /// Decodes exactly the caller-visible prefix available after an encoder flush.
    private static byte[] decodeAvailablePrefix(
            CompressionCodec codec,
            byte[] compressed,
            int expectedSize
    ) throws IOException {
        ByteBuffer decoded = ByteBuffer.allocateDirect(expectedSize);
        try (DecompressingReadableByteChannel decoder = codec.openDecoder(
                Channels.newChannel(new ByteArrayInputStream(compressed))
        )) {
            while (decoded.hasRemaining()) {
                int read = decoder.read(decoded);
                assertTrue(read > 0, codec.name());
            }
            assertEquals(expectedSize, decoder.outputBytes(), codec.name());
        }
        decoded.flip();
        byte[] bytes = new byte[decoded.remaining()];
        decoded.get(bytes);
        return bytes;
    }

    /// Compresses one independent frame without closing the caller-owned target.
    private static byte[] compressFrame(CompressionCodec codec, byte[] content) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(encoded)
        );
        return encoded.toByteArray();
    }

    /// Returns whether a new encoder exposes incremental flush support.
    private static boolean hasFlushableEncoder(CompressionCodec codec) throws IOException {
        try (CompressionEncoder encoder = codec.newEncoder()) {
            return encoder instanceof FlushableCompressionEncoder;
        }
    }

    /// Returns whether a new encoder can finish multiple independent frames.
    private static boolean hasFramedEncoder(CompressionCodec codec) throws IOException {
        try (CompressionEncoder encoder = codec.newEncoder()) {
            return encoder instanceof FramedCompressionEncoder;
        }
    }

    /// Returns whether a new decoder can traverse concatenated independent frames.
    private static boolean hasFramedDecoder(CompressionCodec codec) throws IOException {
        CompressionCodec decoderCodec = CodecContractConfigurations.decoderCodec(codec, 0L);
        try (CompressionDecoder decoder = decoderCodec.newDecoder()) {
            return decoder instanceof FramedCompressionDecoder;
        }
    }

    /// Writes all bytes to a channel while requiring forward progress.
    private static void writeAll(WritableByteChannel target, byte[] bytes) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            if (target.write(source) == 0) {
                throw new IOException("Test channel write made no progress");
            }
        }
    }

    /// Verifies applicable codecs enforce fixed and declared decoding-window limits before producing output.
    @Test
    void enforcesMaximumWindowSizeAcrossApplicableCodecs() throws IOException {
        Set<String> windowCodecs = Set.of(
                "deflate",
                "deflate64",
                "gzip",
                "lzma",
                "lzma-raw",
                "lzma2",
                "xz",
                "zlib",
                "zstd"
        );
        byte[] input = ("bounded decoding window " + "abcdefghijklmnop".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean expected = windowCodecs.contains(codec.name());
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );
            DecompressionLimits unlimitedLimits =
                    DecompressionLimits.ofMaximumWindowSize(Long.MAX_VALUE);
            DecompressionLimits zeroLimits =
                    DecompressionLimits.ofMaximumWindowSize(0L);
            if (!expected) {
                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                decoderCodec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes),
                        zeroLimits
                );
                assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
                continue;
            }

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            decoderCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes),
                    unlimitedLimits
            );
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());

            ReadableByteChannel limitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream limitedBytes = new ByteArrayOutputStream();
            DecompressionWindowLimitException exception = assertThrows(
                    DecompressionWindowLimitException.class,
                    () -> decoderCodec.decompress(
                            limitedSource,
                            Channels.newChannel(limitedBytes),
                            zeroLimits
                    ),
                    codec.name()
            );
            assertEquals(0L, exception.maximumWindowSize(), codec.name());
            assertTrue(exception.requiredWindowSize() > 0L, codec.name());
            assertEquals(0, limitedBytes.size(), codec.name());
            assertTrue(limitedSource.isOpen(), codec.name());

            ReadableByteChannel invalidSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decoderCodec.openDecoder(
                            invalidSource,
                            DecompressionLimits.ofMaximumWindowSize(-2L),
                            ChannelOwnership.RETAIN
                    ),
                    codec.name()
            );
            assertTrue(invalidSource.isOpen(), codec.name());
        }
    }}
