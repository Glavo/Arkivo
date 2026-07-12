// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.EncodeDirective;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the first-class channel contract across all installed codec providers.
@NotNullByDefault
final class CodecChannelContractTest {
    /// Verifies channel transfer, counters, endpoint ownership, and capability consistency.
    @Test
    void roundTripsEveryBidirectionalCodecThroughChannels() throws IOException {
        byte[] input = ("Arkivo channel codec contract: " + "0123456789".repeat(64))
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertEquals(
                    codec.capabilities().supports(CompressionFeature.COMPRESSION),
                    codec.canCompress(),
                    codec.name()
            );
            assertEquals(
                    codec.capabilities().supports(CompressionFeature.DECOMPRESSION),
                    codec.canDecompress(),
                    codec.name()
            );
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }

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
            CodecTransferResult decompression = codec.decompress(compressedSource, decodedTarget);

            assertEquals(input.length, decompression.outputBytes(), codec.name());
            assertTrue(compressedSource.isOpen(), codec.name());
            assertTrue(decodedTarget.isOpen(), codec.name());
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
        }
    }

    /// Verifies advertised flush support through explicit incremental directives.
    @Test
    void flushesIncrementalFramesForEveryAdvertisingCodec() throws IOException {
        byte[] first = "first flushed segment".getBytes(StandardCharsets.UTF_8);
        byte[] second = " and final segment".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.capabilities().supports(CompressionFeature.FLUSH)
                    || !codec.canCompress()
                    || !codec.canDecompress()) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(compressedBytes);
            CompressionEncoder encoder = codec.openEncoder(target);
            CodecResult flushed = encoder.encode(ByteBuffer.wrap(first), EncodeDirective.FLUSH);
            CodecResult finished = encoder.encode(ByteBuffer.wrap(second), EncodeDirective.END_FRAME);

            assertEquals(CodecStatus.FLUSHED, flushed.status(), codec.name());
            assertEquals(CodecStatus.FRAME_FINISHED, finished.status(), codec.name());
            assertTrue(target.isOpen(), codec.name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes)
            );
            assertArrayEquals(
                    "first flushed segment and final segment".getBytes(StandardCharsets.UTF_8),
                    decodedBytes.toByteArray(),
                    codec.name()
            );
        }
    }

    /// Verifies direct-buffer one-shot operations for every codec advertising a specialized path.
    @Test
    void roundTripsDirectBuffersForEveryAdvertisingCodec() throws IOException {
        byte[] input = ("direct codec buffers " + "abcdef".repeat(256)).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.capabilities().supports(CompressionFeature.DIRECT_BYTE_BUFFER)
                    || !codec.canCompressBuffers()
                    || !codec.canDecompressBuffers()) {
                continue;
            }

            ByteBuffer source = ByteBuffer.allocateDirect(input.length);
            source.put(input).flip();
            ByteBuffer compressed = ByteBuffer.allocateDirect(input.length * 2 + 512);
            codec.compress(source, compressed);
            compressed.flip();

            ByteBuffer decoded = ByteBuffer.allocateDirect(input.length);
            codec.decompress(compressed, decoded);
            decoded.flip();
            byte[] output = new byte[decoded.remaining()];
            decoded.get(output);
            assertArrayEquals(input, output, codec.name());
        }
    }

    /// Verifies every advertised compression-level option can open and complete an encoder.
    @Test
    void acceptsAdvertisedCompressionLevels() throws IOException {
        byte[] input = "configured compression level".getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.capabilities().compressionOptions().contains(StandardCodecOptions.COMPRESSION_LEVEL)) {
                continue;
            }

            CodecOptions options = CodecOptions.builder()
                    .set(StandardCodecOptions.COMPRESSION_LEVEL, codec.defaultCompressionLevel())
                    .build();
            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            try (CompressionEncoder encoder = codec.openEncoder(
                    Channels.newChannel(compressedBytes),
                    options,
                    ChannelOwnership.RETAIN
            )) {
                encoder.encode(ByteBuffer.wrap(input), EncodeDirective.END_FRAME);
            }

            ByteBuffer decoded = ByteBuffer.allocate(input.length);
            try (CompressionDecoder decoder = codec.openDecoder(
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

    /// Verifies dictionary capabilities and configured dictionary round trips across installed codecs.
    @Test
    void roundTripsPresetDictionariesForEveryAdvertisingCodec() throws IOException {
        byte[] dictionaryBytes = (
                "Arkivo shared preset dictionary: "
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] input = (
                "common-prefix/alpha/beta/gamma/0123456789;"
                        + "common-prefix/alpha/beta/gamma/0123456789;"
        ).getBytes(StandardCharsets.UTF_8);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, CompressionDictionary.of(dictionaryBytes))
                .build();

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            boolean advertised = codec.capabilities().supports(CompressionFeature.DICTIONARY);
            assertEquals(
                    advertised,
                    codec.capabilities().compressionOptions().contains(StandardCodecOptions.DICTIONARY),
                    codec.name()
            );
            assertEquals(
                    advertised,
                    codec.capabilities().decompressionOptions().contains(StandardCodecOptions.DICTIONARY),
                    codec.name()
            );
            if (!advertised) {
                if (codec.canCompress()) {
                    assertThrows(
                            UnsupportedOperationException.class,
                            () -> codec.openEncoder(
                                    Channels.newChannel(new ByteArrayOutputStream()),
                                    options,
                                    ChannelOwnership.RETAIN
                            ),
                            codec.name()
                    );
                }
                if (codec.canDecompress()) {
                    assertThrows(
                            UnsupportedOperationException.class,
                            () -> codec.openDecoder(
                                    Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                                    options,
                                    ChannelOwnership.RETAIN
                            ),
                            codec.name()
                    );
                }
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes),
                    options
            );
            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes),
                    options
            );
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
        }
    }

    /// Verifies every decompressor enforces exact, exceeded, and invalid output limits consistently.
    @Test
    void enforcesMaximumOutputSizeAcrossAllCodecs() throws IOException {
        byte[] input = ("bounded decompression output " + "0123456789abcdef".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        CodecOptions exactOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, (long) input.length)
                .build();
        long smallerLimit = input.length - 1L;
        CodecOptions smallerOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, smallerLimit)
                .build();
        CodecOptions invalidOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, -1L)
                .build();

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }
            assertTrue(
                    codec.capabilities().decompressionOptions().contains(StandardCodecOptions.MAX_OUTPUT_SIZE),
                    codec.name()
            );

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );

            ByteArrayOutputStream exactBytes = new ByteArrayOutputStream();
            CodecTransferResult exactResult = codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(exactBytes),
                    exactOptions
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
                    () -> codec.decompress(limitedSource, limitedTarget, smallerOptions),
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
                    () -> codec.openDecoder(invalidSource, invalidOptions, ChannelOwnership.RETAIN),
                    codec.name()
            );
            assertTrue(invalidSource.isOpen(), codec.name());
        }
    }

    /// Verifies applicable codecs enforce fixed and declared decoding-window limits before producing output.
    @Test
    void enforcesMaximumWindowSizeAcrossApplicableCodecs() throws IOException {
        Set<String> windowCodecs = Set.of("deflate", "deflate64", "gzip", "lzma", "xz", "zlib", "zstd");
        byte[] input = ("bounded decoding window " + "abcdefghijklmnop".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        CodecOptions unlimitedOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_WINDOW_SIZE, Long.MAX_VALUE)
                .build();
        CodecOptions zeroOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_WINDOW_SIZE, 0L)
                .build();
        CodecOptions invalidOptions = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_WINDOW_SIZE, -1L)
                .build();

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }
            boolean expected = windowCodecs.contains(codec.name());
            assertEquals(
                    expected,
                    codec.capabilities().decompressionOptions().contains(StandardCodecOptions.MAX_WINDOW_SIZE),
                    codec.name()
            );

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            codec.compress(
                    Channels.newChannel(new ByteArrayInputStream(input)),
                    Channels.newChannel(compressedBytes)
            );
            if (!expected) {
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> codec.openDecoder(
                                Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                                unlimitedOptions,
                                ChannelOwnership.RETAIN
                        ),
                        codec.name()
                );
                continue;
            }

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            codec.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                    Channels.newChannel(decodedBytes),
                    unlimitedOptions
            );
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());

            ReadableByteChannel limitedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            ByteArrayOutputStream limitedBytes = new ByteArrayOutputStream();
            DecompressionWindowLimitException exception = assertThrows(
                    DecompressionWindowLimitException.class,
                    () -> codec.decompress(
                            limitedSource,
                            Channels.newChannel(limitedBytes),
                            zeroOptions
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
                    () -> codec.openDecoder(invalidSource, invalidOptions, ChannelOwnership.RETAIN),
                    codec.name()
            );
            assertTrue(invalidSource.isOpen(), codec.name());
        }
    }
}
