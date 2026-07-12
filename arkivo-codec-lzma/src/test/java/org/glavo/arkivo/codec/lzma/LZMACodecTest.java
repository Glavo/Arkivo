// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.lzma.internal.LzmaInputStream;
import org.glavo.arkivo.codec.lzma.internal.Lzma2InputStream;
import org.glavo.arkivo.codec.lzma.internal.Lzma2OutputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaOutputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FinishableWrapperOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests LZMA codec behavior.
@NotNullByDefault
public final class LZMACodecTest {
    /// Verifies that LZMA compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        LZMACodec codec = new LZMACodec();
        byte[] input = "hello lzma".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(LZMACodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the LZMA codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(LZMACodec.class, Objects.requireNonNull(CompressionCodecs.find(LZMACodec.NAME)).getClass());
    }

    /// Verifies Arkivo's decoder against EOS-terminated LZMA-alone output from XZ for Java.
    @Test
    public void nativeDecoderReadsIndependentEndMarkedStream() throws IOException {
        byte[] content = patternedContent(320_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(6);
        options.setDictSize(1 << 20);
        options.setLcLp(3, 0);
        options.setPb(2);
        try (LZMAOutputStream output = new LZMAOutputStream(compressed, options, -1L)) {
            output.write(content);
        }

        try (LzmaInputStream input = new LzmaInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's decoder against a known-size LZMA-alone stream without an end marker.
    @Test
    public void nativeDecoderReadsIndependentKnownSizeStream() throws IOException {
        byte[] content = patternedContent(90_123);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(4);
        options.setDictSize(1 << 18);
        options.setLcLp(2, 1);
        options.setPb(1);
        try (LZMAOutputStream output = new LZMAOutputStream(compressed, options, content.length)) {
            output.write(content);
        }

        try (LzmaInputStream input = new LzmaInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's raw decoder against ZIP-style end-marked LZMA output.
    @Test
    public void nativeDecoderReadsIndependentRawStream() throws IOException {
        byte[] content = patternedContent(180_777);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(5);
        options.setDictSize(1 << 19);
        options.setLcLp(1, 2);
        options.setPb(3);
        try (LZMAOutputStream output = new LZMAOutputStream(compressed, options, true)) {
            output.write(content);
        }

        try (LzmaInputStream input = new LzmaInputStream(
                new ByteArrayInputStream(compressed.toByteArray()),
                -1L,
                (3 * 5 + 2) * 9 + 1,
                1 << 19
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's EOS-terminated output through XZ for Java's independent decoder.
    @Test
    public void nativeEncoderWritesIndependentEndMarkedStream() throws IOException {
        byte[] content = patternedContent(350_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (LzmaOutputStream output = new LzmaOutputStream(compressed, 1 << 20)) {
            for (byte value : content) {
                output.write(value);
            }
        }

        assertEquals(true, compressed.size() < content.length / 2);
        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's known-size output without an end marker through an independent decoder.
    @Test
    public void nativeEncoderWritesIndependentKnownSizeStream() throws IOException {
        byte[] content = patternedContent(131_321);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LzmaProperties properties = new LzmaProperties(2, 1, 1, 1 << 18);
        try (LzmaOutputStream output = new LzmaOutputStream(compressed, properties, content.length)) {
            output.write(content, 0, 17);
            output.write(content, 17, content.length - 17);
        }

        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's LZMA2 decoder against independent compressed and uncompressed chunks.
    @Test
    public void nativeLzma2DecoderReadsIndependentStream() throws IOException {
        byte[] content = mixedLzma2Content();
        int dictionarySize = 1 << 20;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(5);
        options.setDictSize(dictionarySize);
        try (OutputStream output = options.getOutputStream(
                new FinishableWrapperOutputStream(compressed),
                ArrayCache.getDummyCache()
        )) {
            output.write(content);
        }

        try (Lzma2InputStream input = new Lzma2InputStream(
                new ByteArrayInputStream(compressed.toByteArray()),
                dictionarySize
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo's LZMA2 compressed and stored chunks through XZ for Java's decoder.
    @Test
    public void nativeLzma2EncoderWritesIndependentStream() throws IOException {
        byte[] content = mixedLzma2Content();
        int dictionarySize = 1 << 20;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (Lzma2OutputStream output = new Lzma2OutputStream(compressed, dictionarySize)) {
            output.write(content, 0, 31);
            for (int index = 31; index < content.length; index++) {
                output.write(content[index]);
            }
        }

        try (org.tukaani.xz.LZMA2InputStream input = new org.tukaani.xz.LZMA2InputStream(
                new ByteArrayInputStream(compressed.toByteArray()),
                dictionarySize
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies all supported literal and position property combinations through an independent decoder.
    @Test
    public void nativeEncoderSupportsEveryPropertyCombination() throws IOException {
        byte[] content = patternedContent(9_321);
        for (int literalPositionBits = 0; literalPositionBits <= 4; literalPositionBits++) {
            for (int literalContextBits = 0;
                 literalContextBits + literalPositionBits <= 4;
                 literalContextBits++) {
                for (int positionBits = 0; positionBits <= 4; positionBits++) {
                    LzmaProperties properties = new LzmaProperties(
                            literalContextBits,
                            literalPositionBits,
                            positionBits,
                            1 << 14
                    );
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (LzmaOutputStream output = new LzmaOutputStream(compressed, properties)) {
                        output.write(content);
                    }
                    try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                            new ByteArrayInputStream(compressed.toByteArray())
                    )) {
                        assertArrayEquals(content, input.readAllBytes());
                    }
                }
            }
        }
    }

    /// Verifies strict failures for truncation, invalid LZMA2 state, and declared-size mismatches.
    @Test
    public void nativeStreamsRejectMalformedOrMismatchedInput() throws IOException {
        byte[] content = patternedContent(4_096);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (LzmaOutputStream output = new LzmaOutputStream(compressed, 1 << 14)) {
            output.write(content);
        }
        byte[] truncated = Arrays.copyOf(compressed.toByteArray(), compressed.size() - 1);
        assertThrows(IOException.class, () -> {
            try (LzmaInputStream input = new LzmaInputStream(new ByteArrayInputStream(truncated))) {
                input.readAllBytes();
            }
        });

        assertThrows(IOException.class, () -> {
            try (Lzma2InputStream input = new Lzma2InputStream(
                    new ByteArrayInputStream(new byte[]{0x02, 0x00, 0x00, 0x00}),
                    1 << 14
            )) {
                input.readAllBytes();
            }
        });

        ByteArrayOutputStream mismatched = new ByteArrayOutputStream();
        LzmaOutputStream output = new LzmaOutputStream(
                mismatched,
                LzmaProperties.defaults(1 << 14),
                content.length + 1L
        );
        output.write(content);
        assertThrows(IOException.class, output::close);
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

    /// Returns deterministic data containing literals, short repetitions, and long-distance matches.
    private static byte[] patternedContent(int size) {
        byte[] content = new byte[size];
        byte[] phrase = "Arkivo native LZMA interoperability block\n".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < content.length; index++) {
            content[index] = index % 4093 < phrase.length
                    ? phrase[index % phrase.length]
                    : (byte) (index * 31 + index / 257);
        }
        if (content.length > 65_536) {
            System.arraycopy(content, 1_024, content, content.length - 32_768, 32_768);
        }
        return Arrays.copyOf(content, content.length);
    }

    /// Returns multiple compressible blocks followed by deterministic incompressible data.
    private static byte[] mixedLzma2Content() {
        byte[] content = new byte[4 * 65_536 + 12_345];
        byte[] patterned = patternedContent(2 * 65_536);
        System.arraycopy(patterned, 0, content, 0, patterned.length);
        new Random(0x41524b49564fL).nextBytes(content);
        System.arraycopy(patterned, 0, content, 0, patterned.length);
        return content;
    }
}
