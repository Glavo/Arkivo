// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.DecodeDirective;
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

        CodecOptions disabled = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.DISABLED)
                .build();
        assertArrayEquals(
                "abcabc".getBytes(StandardCharsets.US_ASCII),
                decompress(concatenate(corrupt, RAW_FRAME), disabled)
        );

        CodecOptions enabled = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        assertThrows(IOException.class, () -> decompress(corrupt, enabled));
    }

    /// Decodes explicitly selected magicless frames without confusing their boundaries.
    @Test
    public void decodesFixedMagiclessFrames() throws IOException {
        byte[] raw = java.util.Arrays.copyOfRange(RAW_FRAME, Integer.BYTES, RAW_FRAME.length);
        byte[] rle = java.util.Arrays.copyOfRange(RLE_FRAME, Integer.BYTES, RLE_FRAME.length);
        CodecOptions options = CodecOptions.builder()
                .set(ZstdCodec.FRAME_FORMAT, ZstdFrameFormat.MAGICLESS)
                .build();

        byte[] frames = concatenate(raw, rle);
        assertArrayEquals(
                "abcxxxxx".getBytes(StandardCharsets.US_ASCII),
                decompress(frames, options)
        );
        assertThrows(IOException.class, () -> decompress(raw));

        ByteBuffer output = ByteBuffer.allocate(8);
        try (CompressionDecoder decoder = new ZstdCodec().openDecoder(
                Channels.newChannel(new ByteArrayInputStream(frames)),
                options,
                ChannelOwnership.RETAIN
        )) {
            CodecResult first = decoder.decode(output, DecodeDirective.STOP_AT_FRAME);
            assertEquals(CodecStatus.FRAME_FINISHED, first.status());
            output.flip();
            byte[] firstContent = new byte[output.remaining()];
            output.get(firstContent);
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), firstContent);

            output.clear();
            CodecResult second = decoder.decode(output, DecodeDirective.STOP_AT_FRAME);
            assertEquals(CodecStatus.FRAME_FINISHED, second.status());
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

    /// Uses dictionary metadata identifiers for raw-content dictionaries.
    @Test
    public void validatesRawDictionaryIdentifierMetadata() throws IOException {
        CompressionDictionary dictionary = CompressionDictionary.of(
                "raw dictionary content".getBytes(StandardCharsets.US_ASCII),
                127L
        );
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ZstdCodec().decompress(
                Channels.newChannel(new ByteArrayInputStream(DICTIONARY_ID_FRAME)),
                Channels.newChannel(output),
                options
        );
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), output.toByteArray());

        CodecOptions wrongOptions = CodecOptions.builder()
                .set(
                        StandardCodecOptions.DICTIONARY,
                        CompressionDictionary.of(dictionary.bytes(), 126L)
                )
                .build();
        assertThrows(IOException.class, () -> new ZstdCodec().decompress(
                Channels.newChannel(new ByteArrayInputStream(DICTIONARY_ID_FRAME)),
                Channels.newChannel(new ByteArrayOutputStream()),
                wrongOptions
        ));
    }

    /// Reports a skippable frame boundary and preserves a prefetched standard frame.
    @Test
    public void preservesSkippableFrameBoundaryAndReadAhead() throws IOException {
        byte[] encoded = concatenate(SKIPPABLE_FRAME, RAW_FRAME);
        ByteBuffer output = ByteBuffer.allocate(16);
        try (CompressionDecoder decoder = new ZstdCodec().openDecoder(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                CodecOptions.EMPTY,
                ChannelOwnership.RETAIN
        )) {
            CodecResult skipped = decoder.decode(output, DecodeDirective.STOP_AT_FRAME);

            assertEquals(CodecStatus.FRAME_FINISHED, skipped.status());
            assertEquals(SKIPPABLE_FRAME.length, skipped.inputBytes());
            assertEquals(0L, skipped.outputBytes());
            assertEquals(SKIPPABLE_FRAME.length, decoder.inputBytes());
            assertEquals(encoded.length, decoder.sourceBytes());
            assertEquals(RAW_FRAME.length, decoder.unconsumedInput().remaining());
            assertEquals(0, output.position());

            CodecResult decoded = decoder.decode(output, DecodeDirective.STOP_AT_FRAME);
            assertEquals(CodecStatus.FRAME_FINISHED, decoded.status());
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
        try (CompressionDecoder decoder = new ZstdCodec().openDecoder(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                CodecOptions.EMPTY,
                ChannelOwnership.RETAIN
        )) {
            CodecResult result = decoder.decode(output, DecodeDirective.STOP_AT_FRAME);

            assertEquals(CodecStatus.FRAME_FINISHED, result.status());
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
        return decompress(encoded, CodecOptions.EMPTY);
    }

    /// Decompresses all bytes through the configured public channel API.
    private static byte[] decompress(byte[] encoded, CodecOptions options) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ZstdCodec().decompress(
                Channels.newChannel(new ByteArrayInputStream(encoded)),
                Channels.newChannel(output),
                options
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
