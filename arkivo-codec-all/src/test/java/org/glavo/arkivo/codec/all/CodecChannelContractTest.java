// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.glavo.arkivo.codec.lz4.LZ4Codec;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.codec.zstd.ZstdDictionary;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the first-class channel contract across default codecs for all installed formats.
@NotNullByDefault
final class CodecChannelContractTest {
    /// Verifies every installed codec exposes immutable, reusable decoding resource configuration.
    @Test
    void configuresReusableDecodingLimitsAcrossEveryCodec() {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            long originalOutputSize = codec.maximumOutputSize();
            long originalWindowSize = codec.maximumWindowSize();
            long originalMemorySize = codec.maximumMemorySize();

            CompressionCodec<?> configured = codec
                    .withMaximumOutputSize(123L)
                    .withMaximumWindowSize(456L)
                    .withMaximumMemorySize(789L);

            assertEquals(123L, configured.maximumOutputSize(), format.name());
            assertEquals(456L, configured.maximumWindowSize(), format.name());
            assertEquals(789L, configured.maximumMemorySize(), format.name());
            assertSame(configured, configured.withMaximumOutputSize(123L), format.name());
            assertSame(configured, configured.withMaximumWindowSize(456L), format.name());
            assertSame(configured, configured.withMaximumMemorySize(789L), format.name());
            assertEquals(originalOutputSize, codec.maximumOutputSize(), format.name());
            assertEquals(originalWindowSize, codec.maximumWindowSize(), format.name());
            assertEquals(originalMemorySize, codec.maximumMemorySize(), format.name());

            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumOutputSize(-2L), format.name());
            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumWindowSize(-2L), format.name());
            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumMemorySize(-2L), format.name());
        }
    }

    /// Verifies channel transfer, counters, endpoint ownership, and interface consistency.
    @Test
    void roundTripsEveryBidirectionalCodecThroughChannels() throws IOException {
        byte[] input = ("Arkivo channel codec contract: " + "0123456789".repeat(64))
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            ReadableByteChannel uncompressedSource = Channels.newChannel(new ByteArrayInputStream(input));
            WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
            CodecTransferResult compression = codec.compress(uncompressedSource, compressedTarget);

            assertEquals(input.length, compression.inputBytes(), codec.format().name());
            assertEquals(compressedBytes.size(), compression.outputBytes(), codec.format().name());
            assertTrue(uncompressedSource.isOpen(), codec.format().name());
            assertTrue(compressedTarget.isOpen(), codec.format().name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            ReadableByteChannel compressedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            WritableByteChannel decodedTarget = Channels.newChannel(decodedBytes);
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            CodecTransferResult decompression = decoderCodec.decompress(
                    compressedSource,
                    decodedTarget
            );

            assertEquals(input.length, decompression.outputBytes(), codec.format().name());
            assertTrue(compressedSource.isOpen(), codec.format().name());
            assertTrue(decodedTarget.isOpen(), codec.format().name());
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.format().name());
        }
    }

    /// Verifies logical input consumption is distinct from backing-channel read-ahead for every decoder.
    @Test
    void tracksLogicalAndSourceInputBytesAcrossAllCodecs() throws IOException {
        byte[] content = (
                "logical compressed input counters 0123456789abcdef;"
        ).repeat(256).getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            byte[] compressed = compressFrame(codec, content);
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, content.length);
            try (DecompressingReadableByteChannel decoder = decoderCodec.newReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(compressed)),
                    ResourceOwnership.BORROWED
            )) {
                assertUnconsumedInput(decoder, compressed, codec.format().name());
                ByteBuffer target = ByteBuffer.allocate(7);
                while (true) {
                    target.clear();
                    int read = decoder.read(target);
                    assertUnconsumedInput(decoder, compressed, codec.format().name());
                    if (read < 0) {
                        break;
                    }
                    assertTrue(read > 0, codec.format().name());
                }

                if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                    assertTrue(decoder.inputBytes() <= compressed.length, codec.format().name());
                } else {
                    assertEquals(compressed.length, decoder.inputBytes(), codec.format().name());
                }
                assertEquals(compressed.length, decoder.sourceBytes(), codec.format().name());
                assertEquals(content.length, decoder.outputBytes(), codec.format().name());
            }
        }
    }

    /// Verifies flushable encoders through explicit incremental directives.
    @Test
    void flushesIncrementalFramesForEveryFlushableCodec() throws IOException {
        Set<String> flushCodecs = Set.of("deflate", "deflate64", "gzip", "lz4", "xz", "zlib", "zstd");
        byte[] first = ("first flushed segment " + "0123456789abcdef".repeat(512))
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = " and final segment".getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            boolean flushable = hasFlushableEncoder(codec);
            assertEquals(flushCodecs.contains(codec.format().name()), flushable, codec.format().name());
            if (!flushable) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(compressedBytes);
            CompressionCodec.Flushable<?> flushableCodec = (CompressionCodec.Flushable<?>) codec;
            CompressingWritableByteChannel.Flushable encoder = flushableCodec.newWritableByteChannel(target);
            try {
                ByteBuffer firstSource = ByteBuffer.allocateDirect(first.length).put(first).flip();
                int beforeFlush = compressedBytes.size();
                long outputBeforeFlush = encoder.outputBytes();
                CodecResult flushed = encoder.flush(firstSource);
                int flushedSize = compressedBytes.size();

                assertFalse(firstSource.hasRemaining(), codec.format().name());
                assertEquals(CodecResult.Status.FLUSHED, flushed.status(), codec.format().name());
                assertEquals(first.length, flushed.inputBytes(), codec.format().name());
                assertEquals(flushedSize - beforeFlush, flushed.outputBytes(), codec.format().name());
                assertEquals(outputBeforeFlush + flushed.outputBytes(), encoder.outputBytes(), codec.format().name());
                assertEquals(first.length, encoder.inputBytes(), codec.format().name());
                assertEquals(flushedSize, encoder.outputBytes(), codec.format().name());
                assertTrue(flushedSize > 0, codec.format().name());
                assertTrue(encoder.isOpen(), codec.format().name());
                assertTrue(target.isOpen(), codec.format().name());
                assertArrayEquals(
                        first,
                        decodeAvailablePrefix(codec, compressedBytes.toByteArray(), first.length),
                        codec.format().name()
                );

                int beforeRepeatedFlush = compressedBytes.size();
                CodecResult repeatedFlush = encoder.flush(ByteBuffer.allocateDirect(0));
                assertEquals(CodecResult.Status.FLUSHED, repeatedFlush.status(), codec.format().name());
                assertEquals(0L, repeatedFlush.inputBytes(), codec.format().name());
                assertEquals(
                        compressedBytes.size() - beforeRepeatedFlush,
                        repeatedFlush.outputBytes(),
                        codec.format().name()
                );
                assertTrue(encoder.isOpen(), codec.format().name());

                int beforeContinue = compressedBytes.size();
                CodecResult continued = encoder.encode(ByteBuffer.wrap(second));
                assertEquals(CodecResult.Status.ACTIVE, continued.status(), codec.format().name());
                assertEquals(second.length, continued.inputBytes(), codec.format().name());
                assertEquals(compressedBytes.size() - beforeContinue, continued.outputBytes(), codec.format().name());

                int beforeFinish = compressedBytes.size();
                long outputBeforeFinish = encoder.outputBytes();
                encoder.finish();
                assertEquals(
                        compressedBytes.size() - beforeFinish,
                        encoder.outputBytes() - outputBeforeFinish,
                        codec.format().name()
                );
                assertEquals(first.length + second.length, encoder.inputBytes(), codec.format().name());
                assertEquals(compressedBytes.size(), encoder.outputBytes(), codec.format().name());
                assertFalse(encoder.isOpen(), codec.format().name());

                int completeSize = compressedBytes.size();
                encoder.finish();
                assertEquals(completeSize, compressedBytes.size(), codec.format().name());
                assertTrue(target.isOpen(), codec.format().name());
                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.writeBytes(first);
                expected.writeBytes(second);
                assertArrayEquals(expected.toByteArray(), decodedBytes.toByteArray(), codec.format().name());
            } finally {
                encoder.close();
            }
        }
    }

    /// Verifies multi-frame encoders and concatenated-frame decoders share one cumulative context.
    @Test
    void roundTripsMultipleFramesForEveryFramedCodec() throws IOException {
        Set<String> multiFrameCodecs = Set.of("bzip2", "gzip", "lz4", "lzip", "xz", "zstd");
        Set<String> concatenatedFrameCodecs = Set.of("bzip2", "gzip", "lz4", "lzip", "xz", "zstd");
        byte[] first = "first independent frame".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second independent frame".getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            boolean expected = multiFrameCodecs.contains(codec.format().name());
            assertEquals(expected, hasFramedEncoder(codec), codec.format().name());
            assertEquals(
                    concatenatedFrameCodecs.contains(codec.format().name()),
                    hasFramedDecoder(codec),
                    codec.format().name()
            );
            if (!expected) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(compressedBytes);
            CompressionCodec.Framed<?> framedCodec = (CompressionCodec.Framed<?>) codec;
            CompressingWritableByteChannel.Framed encoder = framedCodec.newWritableByteChannel(target);
            CodecResult firstResult = encoder.finishFrame(ByteBuffer.wrap(first));
            int firstFrameSize = compressedBytes.size();

            assertEquals(CodecResult.Status.FRAME_FINISHED, firstResult.status(), codec.format().name());
            assertTrue(encoder.isOpen(), codec.format().name());
            assertEquals(first.length, encoder.inputBytes(), codec.format().name());
            if (encoder instanceof CompressingWritableByteChannel.Flushable flushableEncoder) {
                flushableEncoder.flush();
            }
            assertEquals(firstFrameSize, compressedBytes.size(), codec.format().name());

            CodecResult secondResult = encoder.finishFrame(ByteBuffer.wrap(second));
            int completeSize = compressedBytes.size();
            assertEquals(CodecResult.Status.FRAME_FINISHED, secondResult.status(), codec.format().name());
            assertTrue(encoder.isOpen(), codec.format().name());
            assertEquals(first.length + second.length, encoder.inputBytes(), codec.format().name());
            assertEquals(completeSize, encoder.outputBytes(), codec.format().name());

            encoder.finish();
            assertFalse(encoder.isOpen(), codec.format().name());
            assertEquals(completeSize, compressedBytes.size(), codec.format().name());
            assertTrue(target.isOpen(), codec.format().name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
            );
            ByteArrayOutputStream expectedBytes = new ByteArrayOutputStream();
            expectedBytes.writeBytes(first);
            expectedBytes.writeBytes(second);
            assertArrayEquals(expectedBytes.toByteArray(), decodedBytes.toByteArray(), codec.format().name());
        }
    }

    /// Verifies explicit empty frames and lazy next-frame creation for every multi-frame encoder.
    @Test
    void preservesExplicitEmptyFramesWithoutAddingTrailingFrames() throws IOException {
        byte[] content = "content after an explicit empty frame".getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            if (!hasFramedEncoder(codec)) {
                continue;
            }

            CompressionCodec.Framed<?> framedCodec = (CompressionCodec.Framed<?>) codec;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel.Framed encoder = framedCodec.newWritableByteChannel(
                    Channels.newChannel(compressed),
                    EncodingOptions.DEFAULT,
                    ResourceOwnership.BORROWED
            )) {
                encoder.finishFrame();
                int emptyFrameSize = compressed.size();
                assertTrue(emptyFrameSize > 0, codec.format().name());
                assertTrue(encoder.isOpen(), codec.format().name());

                assertEquals(0, encoder.write(ByteBuffer.allocate(0)), codec.format().name());
                if (encoder instanceof CompressingWritableByteChannel.Flushable flushableEncoder) {
                    flushableEncoder.flush();
                }
                assertEquals(emptyFrameSize, compressed.size(), codec.format().name());

                encoder.startFrame();
                encoder.finishFrame();
                int twoEmptyFramesSize = compressed.size();
                assertTrue(twoEmptyFramesSize > emptyFrameSize, codec.format().name());

                CodecResult finished = encoder.finishFrame(ByteBuffer.wrap(content));
                assertEquals(CodecResult.Status.FRAME_FINISHED, finished.status(), codec.format().name());
                int completeSize = compressed.size();
                encoder.finish();
                assertEquals(completeSize, compressed.size(), codec.format().name());
            }

            try (DecompressingReadableByteChannel.Framed decoder = framedCodec.newReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray()))
            )) {
                CodecResult empty = decoder.decodeFrame(ByteBuffer.allocate(3));
                assertEquals(CodecResult.Status.FRAME_FINISHED, empty.status(), codec.format().name());
                assertEquals(0L, empty.outputBytes(), codec.format().name());

                CodecResult explicitlyStartedEmpty = decoder.decodeFrame(ByteBuffer.allocate(3));
                assertEquals(
                        CodecResult.Status.FRAME_FINISHED,
                        explicitlyStartedEmpty.status(),
                        codec.format().name()
                );
                assertEquals(0L, explicitlyStartedEmpty.outputBytes(), codec.format().name());

                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                while (true) {
                    ByteBuffer target = ByteBuffer.allocate(3);
                    CodecResult result = decoder.decodeFrame(target);
                    target.flip();
                    byte[] chunk = new byte[target.remaining()];
                    target.get(chunk);
                    decoded.writeBytes(chunk);
                    if (result.status() == CodecResult.Status.FRAME_FINISHED) {
                        break;
                    }
                    assertEquals(CodecResult.Status.ACTIVE, result.status(), codec.format().name());
                }
                assertArrayEquals(content, decoded.toByteArray(), codec.format().name());

                CodecResult ended = decoder.decodeFrame(ByteBuffer.allocate(1));
                assertEquals(CodecResult.Status.END_OF_INPUT, ended.status(), codec.format().name());
                assertEquals(compressed.size(), decoder.inputBytes(), codec.format().name());
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

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            if (!hasFramedDecoder(codec)) {
                continue;
            }

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[][] encodedFrames = new byte[frames.length][];
            for (int index = 0; index < frames.length; index++) {
                encodedFrames[index] = compressFrame(codec, frames[index]);
                compressed.writeBytes(encodedFrames[index]);
            }
            byte[] compressedStream = compressed.toByteArray();

            try (DecompressingReadableByteChannel.Framed decoder =
                         ((CompressionCodec.Framed<?>) codec.withMaximumOutputSize(totalOutputSize))
                                 .newReadableByteChannel(
                                 Channels.newChannel(new ByteArrayInputStream(compressedStream)),
                                 ResourceOwnership.BORROWED
                         )) {
                long completedInputBytes = 0L;
                for (int frameIndex = 0; frameIndex < frames.length; frameIndex++) {
                    byte[] expectedFrame = frames[frameIndex];
                    ByteArrayOutputStream decodedFrame = new ByteArrayOutputStream();
                    int operations = 0;
                    while (true) {
                        ByteBuffer target = ByteBuffer.allocate(5);
                        CodecResult result = decoder.decodeFrame(target);
                        target.flip();
                        byte[] chunk = new byte[target.remaining()];
                        target.get(chunk);
                        decodedFrame.writeBytes(chunk);
                        assertEquals(chunk.length, result.outputBytes(), codec.format().name());
                        assertUnconsumedInput(decoder, compressedStream, codec.format().name());
                        assertTrue(++operations < 100, codec.format().name());

                        if (result.status() == CodecResult.Status.FRAME_FINISHED) {
                            break;
                        }
                        assertEquals(CodecResult.Status.ACTIVE, result.status(), codec.format().name());
                        assertTrue(result.inputBytes() > 0L || result.outputBytes() > 0L, codec.format().name());
                    }
                    assertArrayEquals(expectedFrame, decodedFrame.toByteArray(), codec.format().name());
                    completedInputBytes += encodedFrames[frameIndex].length;
                    assertEquals(completedInputBytes, decoder.inputBytes(), codec.format().name());
                    if (frameIndex == 0) {
                        assertTrue(decoder.sourceBytes() > decoder.inputBytes(), codec.format().name());
                    }
                }

                CodecResult ended = decoder.decodeFrame(ByteBuffer.allocate(5));
                assertEquals(CodecResult.Status.END_OF_INPUT, ended.status(), codec.format().name());
                assertEquals(totalOutputSize, decoder.outputBytes(), codec.format().name());
                assertEquals(compressed.size(), decoder.inputBytes(), codec.format().name());
                assertEquals(compressed.size(), decoder.sourceBytes(), codec.format().name());
            }
        }
    }

    /// Verifies allocating ByteBuffer one-shot operations across every bidirectional codec.
    @Test
    void roundTripsEveryCodecThroughAllocatingByteBuffers() throws IOException {
        byte[] input = (
                "allocating ByteBuffer codec contract 0123456789abcdef;"
        ).repeat(1_024).getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            ByteBuffer source = ByteBuffer.allocateDirect(input.length + 4);
            source.position(2);
            source.put(input);
            source.flip();
            source.position(2);
            ByteBuffer compressed = codec.compress(source);
            assertEquals(source.limit(), source.position(), codec.format().name());
            assertEquals(0, compressed.position(), codec.format().name());
            assertTrue(compressed.hasRemaining(), codec.format().name());

            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            ByteBuffer decoded = decoderCodec.withMaximumOutputSize(input.length).decompress(compressed);
            if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                assertTrue(compressed.position() <= compressed.limit(), codec.format().name());
            } else {
                assertEquals(compressed.limit(), compressed.position(), codec.format().name());
            }
            assertEquals(0, decoded.position(), codec.format().name());
            byte[] actual = new byte[decoded.remaining()];
            decoded.get(actual);
            assertArrayEquals(input, actual, codec.format().name());

            ByteBuffer overflowSource = codec.compress(ByteBuffer.wrap(input));
            assertThrows(
                    DecompressionLimitException.class,
                    () -> decoderCodec.withMaximumOutputSize(input.length - 1L).decompress(overflowSource),
                    codec.format().name()
            );

            ByteBuffer emptyCompressed = codec.compress(ByteBuffer.allocate(0));
            CompressionCodec<?> emptyDecoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, 0L);
            ByteBuffer emptyDecoded = emptyDecoderCodec.withMaximumOutputSize(0L).decompress(emptyCompressed);
            assertEquals(0, emptyDecoded.remaining(), codec.format().name());
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

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            if (!(codec instanceof CompressionCodec.Framed<?> framedCodec)
                    || CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                continue;
            }

            byte[] emptyEncoded = compressFrame(codec, new byte[0]);
            byte[] firstEncoded = compressFrame(codec, first);
            byte[] secondEncoded = compressFrame(codec, second);

            ByteBuffer emptySource = ByteBuffer.allocate(emptyEncoded.length + firstEncoded.length);
            emptySource.put(emptyEncoded).put(firstEncoded).flip();
            ByteBuffer emptyDecoded = ((CompressionCodec.Framed<?>)
                    framedCodec.withMaximumOutputSize(0L)).decompressFrame(emptySource);
            assertEquals(0, emptyDecoded.remaining(), codec.format().name());
            assertEquals(emptyEncoded.length, emptySource.position(), codec.format().name());

            ByteBuffer emptyFixedSource = ByteBuffer.allocate(emptyEncoded.length + firstEncoded.length);
            emptyFixedSource.put(emptyEncoded).put(firstEncoded).flip();
            framedCodec.decompressFrame(emptyFixedSource, ByteBuffer.allocate(0));
            assertEquals(emptyEncoded.length, emptyFixedSource.position(), codec.format().name());

            ByteBuffer source = ByteBuffer.allocate(2 + firstEncoded.length + secondEncoded.length + 3);
            source.position(2);
            source.put(firstEncoded);
            source.put(secondEncoded);
            source.flip();
            source.position(2);
            source.limit(2 + firstEncoded.length + secondEncoded.length);
            CompressionCodec.Framed<?> firstCodec = (CompressionCodec.Framed<?>)
                    framedCodec.withMaximumOutputSize(first.length);
            ByteBuffer firstDecoded = firstCodec.decompressFrame(source);
            assertArrayEquals(first, bufferBytes(firstDecoded), codec.format().name());
            assertEquals(2 + firstEncoded.length, source.position(), codec.format().name());

            ByteBuffer secondTarget = ByteBuffer.allocate(second.length);
            framedCodec.decompressFrame(source, secondTarget);
            secondTarget.flip();
            assertArrayEquals(second, bufferBytes(secondTarget), codec.format().name());
            assertEquals(source.limit(), source.position(), codec.format().name());

            ByteBuffer limitedSource = ByteBuffer.wrap(firstEncoded);
            assertThrows(
                    DecompressionLimitException.class,
                    () -> ((CompressionCodec.Framed<?>)
                            framedCodec.withMaximumOutputSize(first.length - 1L))
                            .decompressFrame(limitedSource),
                    codec.format().name()
            );

            ByteBuffer overflowSource = ByteBuffer.wrap(firstEncoded);
            assertThrows(
                    java.nio.BufferOverflowException.class,
                    () -> framedCodec.decompressFrame(
                            overflowSource,
                            ByteBuffer.allocate(first.length - 1)
                    ),
                    codec.format().name()
            );
        }
    }

    /// Verifies fixed-buffer operations across heap, direct, sliced, and read-only endpoint combinations.
    @Test
    void roundTripsHeapAndDirectFixedBuffersAcrossEveryCodec() throws IOException {
        byte[] input = ("fixed codec buffers " + "abcdef".repeat(256)).getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();

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
                    codec.format().name()
            );
            assertEquals(0, compressionSource.position(), codec.format().name());

            ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
            int compressedPosition = compressed.position();
            ByteBuffer readOnlyDecompressionTarget = ByteBuffer.allocate(input.length).asReadOnlyBuffer();
            assertThrows(
                    ReadOnlyBufferException.class,
                    () -> decompressFixed(codec, compressed, readOnlyDecompressionTarget, input.length),
                    codec.format().name()
            );
            assertEquals(compressedPosition, compressed.position(), codec.format().name());

            ByteBuffer sameCompressionBuffer = ByteBuffer.allocate(input.length + 8_192);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.compress(sameCompressionBuffer, sameCompressionBuffer),
                    codec.format().name()
            );
            ByteBuffer sameDecompressionBuffer = codec.compress(ByteBuffer.wrap(input));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decompressFixed(codec, sameDecompressionBuffer, sameDecompressionBuffer, input.length),
                    codec.format().name()
            );
        }
    }

    /// Verifies one fixed-buffer layout while preserving nonzero source and target range offsets.
    private static void assertFixedBufferRoundTrip(
            CompressionCodec<?> codec,
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
        assertEquals(source.limit(), source.position(), codec.format().name());

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
        assertEquals(decoded.limit(), decoded.position(), codec.format().name());

        ByteBuffer output = decoded.duplicate();
        output.position(decodedOffset);
        output.limit(decodedOffset + input.length);
        byte[] outputBytes = new byte[output.remaining()];
        output.get(outputBytes);
        assertArrayEquals(input, outputBytes, codec.format().name());
    }

    /// Decompresses through a codec carrying any externally required stream metadata.
    private static void decompressFixed(
            CompressionCodec<?> codec,
            ByteBuffer source,
            ByteBuffer target,
            long decodedSize
    ) throws IOException {
        CompressionCodec<?> decoderCodec =
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

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            assertEquals(
                    levelCodecs.contains(codec.format().name()),
                    codec instanceof CompressionCodec.LevelConfigurable<?>,
                    codec.format().name()
            );
            if (!(codec instanceof CompressionCodec.LevelConfigurable<?> levelCodec)) {
                continue;
            }

            CompressionCodec<?> configured =
                    levelCodec.withCompressionLevel(levelCodec.defaultCompressionLevel());
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel encoder = configured.newWritableByteChannel(
                    Channels.newChannel(compressedBytes),
                    EncodingOptions.DEFAULT,
                    ResourceOwnership.BORROWED
            )) {
                encoder.encode(ByteBuffer.wrap(input));
            }

            ByteBuffer decoded = ByteBuffer.allocate(input.length);
            try (DecompressingReadableByteChannel decoder = configured.newReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray()))
            )) {
                while (decoded.hasRemaining()) {
                    if (decoder.read(decoded) < 0) {
                        break;
                    }
                }
            }
            assertArrayEquals(input, decoded.array(), codec.format().name());
        }
    }

    /// Verifies capability configuration preserves a codec's concrete return type.
    @Test
    void preservesConcreteTypeAcrossCapabilityConfiguration() {
        BZip2Codec configured = withMinimumCompressionLevel(BZip2Codec.DEFAULT);

        assertEquals(BZip2Codec.MINIMUM_COMPRESSION_LEVEL, configured.compressionLevel());
    }

    /// Returns a codec configured with its minimum level.
    ///
    /// @param <C>   the codec's concrete self type
    /// @param codec the configurable codec
    /// @return a reconfigured codec with the same concrete type
    private static <C extends CompressionCodec<C>> C withMinimumCompressionLevel(
            CompressionCodec.LevelConfigurable<C> codec
    ) {
        return codec.withCompressionLevel(codec.minimumCompressionLevel());
    }

    /// Verifies dictionary configuration subinterfaces and preset-dictionary round trips.
    @Test
    void roundTripsPresetDictionariesForEveryDictionaryCodec() throws IOException {
        Set<String> dictionaryCodecs = Set.of("deflate", "lz4", "zlib", "zstd");
        byte[] dictionaryBytes = (
                "Arkivo shared preset dictionary: "
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] input = (
                "common-prefix/alpha/beta/gamma/0123456789;"
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).getBytes(StandardCharsets.UTF_8);
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            assertEquals(
                    dictionaryCodecs.contains(codec.format().name()),
                    codec instanceof CompressionCodec.DictionaryConfigurable<?, ?>,
                    codec.format().name()
            );
            CompressionCodec<?> configured;
            if (codec instanceof DeflateCodec deflateCodec) {
                configured = deflateCodec.withDictionary(RawCompressionDictionary.of(dictionaryBytes));
            } else if (codec instanceof ZlibCodec zlibCodec) {
                configured = zlibCodec.withDictionary(ZlibDictionary.of(dictionaryBytes));
            } else if (codec instanceof LZ4Codec lz4Codec) {
                configured = lz4Codec.withDictionary(LZ4Dictionary.rawContent(dictionaryBytes));
            } else if (codec instanceof ZstdCodec zstdCodec) {
                configured = zstdCodec.withDictionary(ZstdDictionary.rawContent(dictionaryBytes));
            } else {
                continue;
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
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.format().name());
        }
    }

    /// Verifies every codec accepts exact source-size metadata and size-aware codecs enforce it.
    @Test
    void acceptsExactSourceSizesAcrossAllCodecs() throws IOException {
        Set<String> sizeAwareCodecs = Set.of("lzma", "lzma-raw", "zstd");
        byte[] input = (
                "exact source size contract 0123456789abcdef;"
        ).repeat(512).getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        try (CompressionEncoder ignored =
                                     codec.newEncoder(EncodingOptions.ofSourceSize(
                                             CompressionCodec.UNKNOWN_SIZE - 1L
                                     ))) {
                            ignored.reset();
                        }
                    },
                    codec.format().name()
            );

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (CompressingWritableByteChannel encoder = codec.newWritableByteChannel(
                    Channels.newChannel(compressed),
                    EncodingOptions.ofSourceSize(input.length),
                    ResourceOwnership.BORROWED
            )) {
                writeAll(encoder, input);
                encoder.finish();
            }
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            decoderCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressed.toByteArray())),
                    Channels.newChannel(decoded)
            );
            assertArrayEquals(input, decoded.toByteArray(), codec.format().name());

            if (sizeAwareCodecs.contains(codec.format().name())) {
                assertThrows(
                        IOException.class,
                        () -> {
                            try (CompressingWritableByteChannel encoder = codec.newWritableByteChannel(
                                    Channels.newChannel(new ByteArrayOutputStream()),
                                    EncodingOptions.ofSourceSize(input.length - 1L),
                                    ResourceOwnership.BORROWED
                            )) {
                                writeAll(encoder, input);
                                encoder.finish();
                            }
                        },
                        codec.format().name()
                );
            }
        }
    }

    /// Verifies every decompressor enforces exact, exceeded, and invalid output limits consistently.
    @Test
    void enforcesMaximumOutputSizeAcrossAllCodecs() throws IOException {
        byte[] input = ("bounded decompression output " + "0123456789abcdef".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        long smallerLimit = input.length - 1L;

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);
            CompressionCodec<?> exactCodec = decoderCodec.withMaximumOutputSize(input.length);
            CompressionCodec<?> smallerCodec = decoderCodec.withMaximumOutputSize(smallerLimit);

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );

            ByteArrayOutputStream exactBytes = new ByteArrayOutputStream();
            CodecTransferResult exactResult = exactCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(exactBytes)
            );
            assertEquals(input.length, exactResult.outputBytes(), codec.format().name());
            assertArrayEquals(input, exactBytes.toByteArray(), codec.format().name());

            ReadableByteChannel limitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream limitedBytes = new ByteArrayOutputStream();
            WritableByteChannel limitedTarget = Channels.newChannel(limitedBytes);
            DecompressionLimitException exception = assertThrows(
                    DecompressionLimitException.class,
                    () -> smallerCodec.decompress(limitedSource, limitedTarget),
                    codec.format().name()
            );
            assertEquals(smallerLimit, exception.maximum(), codec.format().name());
            assertEquals(smallerLimit, limitedBytes.size(), codec.format().name());

            assertTrue(limitedSource.isOpen(), codec.format().name());
            assertTrue(limitedTarget.isOpen(), codec.format().name());

            ReadableByteChannel invalidSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decoderCodec.withMaximumOutputSize(-2L).newReadableByteChannel(
                            invalidSource,
                            ResourceOwnership.BORROWED
                    ),
                    codec.format().name()
            );
            assertTrue(invalidSource.isOpen(), codec.format().name());
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
            CompressionCodec<?> codec,
            byte[] compressed,
            int expectedSize
    ) throws IOException {
        ByteBuffer decoded = ByteBuffer.allocateDirect(expectedSize);
        try (DecompressingReadableByteChannel decoder = codec.newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(compressed))
        )) {
            while (decoded.hasRemaining()) {
                int read = decoder.read(decoded);
                assertTrue(read > 0, codec.format().name());
            }
            assertEquals(expectedSize, decoder.outputBytes(), codec.format().name());
        }
        decoded.flip();
        byte[] bytes = new byte[decoded.remaining()];
        decoded.get(bytes);
        return bytes;
    }

    /// Compresses one independent frame without closing the caller-owned target.
    private static byte[] compressFrame(CompressionCodec<?> codec, byte[] content) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(encoded)
        );
        return encoded.toByteArray();
    }

    /// Returns whether a new encoder exposes incremental flush support.
    private static boolean hasFlushableEncoder(CompressionCodec<?> codec) {
        return codec instanceof CompressionCodec.Flushable<?>;
    }

    /// Returns whether a new encoder can finish multiple independent frames.
    private static boolean hasFramedEncoder(CompressionCodec<?> codec) {
        return codec instanceof CompressionCodec.Framed<?>;
    }

    /// Returns whether a new decoder can traverse concatenated independent frames.
    private static boolean hasFramedDecoder(CompressionCodec<?> codec) {
        CompressionCodec<?> decoderCodec = CodecContractConfigurations.decoderCodec(codec, 0L);
        return decoderCodec instanceof CompressionCodec.Framed<?>;
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
                "compress",
                "deflate",
                "deflate64",
                "gzip",
                "lz4",
                "lz4-block",
                "lzip",
                "lzma",
                "lzma-raw",
                "lzma2",
                "xz",
                "zlib",
                "zstd"
        );
        byte[] input = ("bounded decoding window " + "abcdefghijklmnop".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            boolean expected = windowCodecs.contains(codec.format().name());
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, input.length);

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );
            CompressionCodec<?> unlimitedWindowCodec = decoderCodec.withMaximumWindowSize(Long.MAX_VALUE);
            CompressionCodec<?> zeroWindowCodec = decoderCodec.withMaximumWindowSize(0L);
            if (!expected) {
                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                zeroWindowCodec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                assertArrayEquals(input, decodedBytes.toByteArray(), codec.format().name());
                continue;
            }

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            unlimitedWindowCodec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
            );
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.format().name());

            ReadableByteChannel limitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream limitedBytes = new ByteArrayOutputStream();
            DecompressionWindowLimitException exception = assertThrows(
                    DecompressionWindowLimitException.class,
                    () -> zeroWindowCodec.decompress(
                            limitedSource,
                            Channels.newChannel(limitedBytes)
                    ),
                    codec.format().name()
            );
            assertEquals(0L, exception.maximumWindowSize(), codec.format().name());
            assertTrue(exception.requiredWindowSize() > 0L, codec.format().name());
            assertEquals(0, limitedBytes.size(), codec.format().name());
            assertTrue(limitedSource.isOpen(), codec.format().name());

            ReadableByteChannel memoryLimitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream memoryLimitedBytes = new ByteArrayOutputStream();
            DecompressionWindowLimitException memoryBoundException = assertThrows(
                    DecompressionWindowLimitException.class,
                    () -> decoderCodec.withMaximumMemorySize(0L).decompress(
                            memoryLimitedSource,
                            Channels.newChannel(memoryLimitedBytes)
                    ),
                    codec.format().name()
            );
            assertEquals(0L, memoryBoundException.maximumWindowSize(), codec.format().name());
            assertTrue(memoryBoundException.requiredWindowSize() > 0L, codec.format().name());
            assertEquals(0, memoryLimitedBytes.size(), codec.format().name());
            assertTrue(memoryLimitedSource.isOpen(), codec.format().name());

            ReadableByteChannel invalidSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decoderCodec.withMaximumWindowSize(-2L).newReadableByteChannel(
                            invalidSource,
                            ResourceOwnership.BORROWED
                    ),
                    codec.format().name()
            );
            assertTrue(invalidSource.isOpen(), codec.format().name());
        }
    }
}
