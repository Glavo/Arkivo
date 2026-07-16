// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies decoding with fixed Zstandard frames that do not depend on a native encoder.
@NotNullByDefault
public final class ZstdPureJavaDecoderTest {
    /// A single-segment raw frame containing abc.
    private static final byte[] RAW_FRAME = {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
            0x20, 0x03,
            0x19, 0x00, 0x00,
            0x61, 0x62, 0x63
    };

    /// A single-segment RLE frame containing five x bytes.
    private static final byte[] RLE_FRAME = {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
            0x20, 0x05,
            0x2b, 0x00, 0x00,
            0x78
    };

    /// A checksum-protected raw frame containing abc.
    private static final byte[] CHECKSUM_FRAME = {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
            0x24, 0x03,
            0x19, 0x00, 0x00,
            0x61, 0x62, 0x63,
            (byte) 0x99, 0x09, 0x77, (byte) 0xad
    };

    /// A frame with the largest encodable window descriptor and one raw byte.
    private static final byte[] LARGE_WINDOW_FRAME = {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
            0x00, (byte) 0xff,
            0x09, 0x00, 0x00,
            0x7a
    };

    /// A raw frame requiring dictionary identifier 127.
    private static final byte[] DICTIONARY_ID_FRAME = {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
            0x21, 0x7f, 0x03,
            0x19, 0x00, 0x00,
            0x61, 0x62, 0x63
    };

    /// A three-byte skippable frame.
    private static final byte[] SKIPPABLE_FRAME = {
            0x50, 0x2a, 0x4d, 0x18,
            0x03, 0x00, 0x00, 0x00,
            0x11, 0x22, 0x33
    };

    /// Decodes raw and RLE frames across a skippable frame.
    @Test
    public void decodesFixedConcatenatedFrames() throws IOException {
        byte[] encoded = concatenate(RAW_FRAME, SKIPPABLE_FRAME, RLE_FRAME);
        byte[] expected = "abcxxxxx".getBytes(StandardCharsets.US_ASCII);

        assertArrayEquals(expected, decompress(encoded));
    }

    /// Verifies XXH64 frame checksum acceptance and rejection.
    @Test
    public void verifiesFixedFrameChecksum() throws IOException {
        assertArrayEquals(
                "abc".getBytes(StandardCharsets.US_ASCII),
                decompress(CHECKSUM_FRAME)
        );

        byte[] corrupt = CHECKSUM_FRAME.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decompress(corrupt));

        ZstdCodec unverified = new ZstdCodec().withVerifyChecksums(false);
        assertArrayEquals(
                "abcabc".getBytes(StandardCharsets.US_ASCII),
                decompress(concatenate(corrupt, RAW_FRAME), unverified)
        );

        ZstdCodec verified = unverified.withVerifyChecksums(true);
        assertThrows(IOException.class, () -> decompress(corrupt, verified));
    }

    /// Decodes explicitly selected magicless frames without confusing their boundaries.
    @Test
    public void decodesFixedMagiclessFrames() throws IOException {
        byte[] raw = java.util.Arrays.copyOfRange(RAW_FRAME, Integer.BYTES, RAW_FRAME.length);
        byte[] rle = java.util.Arrays.copyOfRange(RLE_FRAME, Integer.BYTES, RLE_FRAME.length);
        ZstdCodec codec = new ZstdCodec().withFrameFormat(ZstdFrameFormat.MAGICLESS);

        byte[] frames = concatenate(raw, rle);
        assertArrayEquals(
                "abcxxxxx".getBytes(StandardCharsets.US_ASCII),
                decompress(frames, codec)
        );
        assertThrows(IOException.class, () -> decompress(raw));

        ByteBuffer output = ByteBuffer.allocate(8);
        try (DecompressingReadableByteChannel.Framed decoder = codec.newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(frames)),
                ChannelOwnership.RETAIN
        )) {
            CodecResult first = decoder.decodeFrame(output);
            assertEquals(CodecResult.Status.FRAME_FINISHED, first.status());
            output.flip();
            byte[] firstContent = new byte[output.remaining()];
            output.get(firstContent);
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), firstContent);

            output.clear();
            CodecResult second = decoder.decodeFrame(output);
            assertEquals(CodecResult.Status.FRAME_FINISHED, second.status());
            output.flip();
            byte[] secondContent = new byte[output.remaining()];
            output.get(secondContent);
            assertArrayEquals("xxxxx".getBytes(StandardCharsets.US_ASCII), secondContent);
        }
    }

    /// Supports the full frame-window descriptor range without eagerly allocating the declared window.
    @Test
    public void decodesLargestWindowDescriptorLazily() throws IOException {
        assertArrayEquals(new byte[]{0x7a}, decompress(LARGE_WINDOW_FRAME));
    }

    /// Distinguishes raw dictionary content from a frame's formatted dictionary request.
    @Test
    public void exposesTypedDictionaryRequest() throws IOException {
        ZstdDictionary rawDictionary = ZstdDictionary.rawContent(
                "raw dictionary content".getBytes(StandardCharsets.US_ASCII)
        );
        ByteBuffer source = ByteBuffer.wrap(DICTIONARY_ID_FRAME);
        try (CompressionDecoder.DictionaryAware<ZstdDictionary, ZstdDictionaryRequest> decoder =
                     new ZstdCodec().newDecoder()) {
            CodecOutcome outcome = decoder.decode(source, ByteBuffer.allocate(8), true);

            assertEquals(CodecOutcome.NEEDS_DICTIONARY, outcome);
            ZstdDictionaryRequest request = decoder.dictionaryRequest();
            assertEquals(127L, request.dictionaryId());
            assertEquals(false, request.matches(rawDictionary));
            assertThrows(IOException.class, () -> decoder.provideDictionary(rawDictionary));
            assertEquals(request, decoder.dictionaryRequest());
        }
    }

    /// Reports a skippable frame boundary and preserves a prefetched standard frame.
    @Test
    public void preservesSkippableFrameBoundaryAndReadAhead() throws IOException {
        byte[] encoded = concatenate(SKIPPABLE_FRAME, RAW_FRAME);
        ByteBuffer output = ByteBuffer.allocate(16);
        try (DecompressingReadableByteChannel.Framed decoder = new ZstdCodec().newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                ChannelOwnership.RETAIN
        )) {
            CodecResult skipped = decoder.decodeFrame(output);

            assertEquals(CodecResult.Status.FRAME_FINISHED, skipped.status());
            assertEquals(SKIPPABLE_FRAME.length, skipped.inputBytes());
            assertEquals(0L, skipped.outputBytes());
            assertEquals(SKIPPABLE_FRAME.length, decoder.inputBytes());
            assertEquals(encoded.length, decoder.sourceBytes());
            assertEquals(RAW_FRAME.length, decoder.unconsumedInput().remaining());
            assertEquals(0, output.position());

            CodecResult decoded = decoder.decodeFrame(output);
            assertEquals(CodecResult.Status.FRAME_FINISHED, decoded.status());
            assertEquals(RAW_FRAME.length, decoded.inputBytes());
            assertEquals(3L, decoded.outputBytes());
            output.flip();
            byte[] content = new byte[output.remaining()];
            output.get(content);
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), content);
        }
    }

    /// Stops at a verified frame without logically consuming prefetched bytes from the next frame.
    @Test
    public void preservesFrameBoundaryAndReadAhead() throws IOException {
        byte[] encoded = concatenate(RAW_FRAME, RLE_FRAME);
        ByteBuffer output = ByteBuffer.allocate(16);
        try (DecompressingReadableByteChannel.Framed decoder = new ZstdCodec().newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                ChannelOwnership.RETAIN
        )) {
            CodecResult result = decoder.decodeFrame(output);

            assertEquals(CodecResult.Status.FRAME_FINISHED, result.status());
            assertEquals(RAW_FRAME.length, decoder.inputBytes());
            assertEquals(encoded.length, decoder.sourceBytes());
            assertEquals(RLE_FRAME.length, decoder.unconsumedInput().remaining());
            output.flip();
            byte[] decoded = new byte[output.remaining()];
            output.get(decoded);
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), decoded);
        }
    }

    /// Decompresses all bytes through the public channel API.
    private static byte[] decompress(byte[] encoded) throws IOException {
        return decompress(encoded, new ZstdCodec());
    }

    /// Decompresses all bytes through the configured public channel API.
    private static byte[] decompress(byte[] encoded, ZstdCodec codec) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Concatenates byte arrays.
    private static byte[] concatenate(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) {
            size = Math.addExact(size, array.length);
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
