// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.LZMA2Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies public raw LZMA and LZMA2 codecs against an independent implementation.
@NotNullByDefault
public final class RawLZMACodecTest {
    /// The dictionary size used by raw-stream interoperability tests.
    private static final int DICTIONARY_SIZE = 1 << 20;

    /// Shared raw LZMA model properties.
    private static final LZMAProperties PROPERTIES =
            new LZMAProperties(1, 2, 3, DICTIONARY_SIZE);

    /// Shared EOS-terminated raw LZMA configuration.
    private static final RawLZMACodec RAW_CODEC =
            new RawLZMACodec().withProperties(PROPERTIES);

    /// Shared raw LZMA2 configuration.
    private static final LZMA2Codec LZMA2_CODEC =
            new LZMA2Codec().withDictionarySize(DICTIONARY_SIZE);

    /// Verifies both raw codecs are discoverable only by their explicit names.
    @Test
    public void discoversRawProviders() {
        assertInstanceOf(
                RawLZMACodec.class,
                CompressionFormats.require(RawLZMAFormat.NAME).defaultCodec()
        );
        assertInstanceOf(
                RawLZMACodec.class,
                CompressionFormats.require("raw-lzma").defaultCodec()
        );
        assertInstanceOf(
                LZMA2Codec.class,
                CompressionFormats.require(LZMA2Format.NAME).defaultCodec()
        );
    }

    /// Verifies Arkivo's EOS-terminated raw LZMA output with XZ for Java.
    @Test
    public void independentDecoderReadsRawLZMA() throws IOException {
        byte[] content = content();
        byte[] compressed = compress(RAW_CODEC, content);

        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed),
                -1L,
                (byte) propertyByte(1, 2, 3),
                DICTIONARY_SIZE
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo decodes independently generated EOS-terminated raw LZMA.
    @Test
    public void readsIndependentRawLZMA() throws IOException {
        byte[] content = content();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options independentOptions = new LZMA2Options(5);
        independentOptions.setDictSize((int) DICTIONARY_SIZE);
        independentOptions.setLcLp(1, 2);
        independentOptions.setPb(3);
        try (org.tukaani.xz.LZMAOutputStream output =
                     new org.tukaani.xz.LZMAOutputStream(compressed, independentOptions, true)) {
            output.write(content);
        }

        assertArrayEquals(
                content,
                decompress(RAW_CODEC, compressed.toByteArray())
        );
    }

    /// Verifies exact-size raw LZMA decoding leaves following container bytes unread.
    @Test
    public void exactSizePreservesFollowingBytes() throws IOException {
        byte[] content = content();
        RawLZMACodec encodingCodec = RAW_CODEC.withEndMarker(false);
        byte[] compressed = compress(encodingCodec, content);
        byte[] framed = java.util.Arrays.copyOf(compressed, compressed.length + 1);
        framed[framed.length - 1] = 0x6a;
        ByteArrayInputStream source = new ByteArrayInputStream(framed);
        RawLZMACodec decodingCodec = RAW_CODEC.withDecodedSize(content.length);

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (DecompressingReadableByteChannel decoder = decodingCodec.newReadableByteChannel(
                Channels.newChannel(source),
                ResourceOwnership.BORROWED
        )) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (decoder.read(buffer) >= 0) {
                buffer.flip();
                decoded.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            assertEquals(compressed.length, decoder.inputBytes());
            assertEquals(framed.length, decoder.sourceBytes());
            ByteBuffer unconsumed = decoder.unconsumedInput();
            assertEquals(1, unconsumed.remaining());
            assertEquals(0x6a, Byte.toUnsignedInt(unconsumed.get()));
        }
        assertArrayEquals(content, decoded.toByteArray());
        assertEquals(-1, source.read());
    }

    /// Verifies Arkivo's raw LZMA2 output with XZ for Java.
    @Test
    public void independentDecoderReadsLZMA2() throws IOException {
        byte[] content = content();
        byte[] compressed = compress(LZMA2_CODEC, content);

        try (org.tukaani.xz.LZMA2InputStream input = new org.tukaani.xz.LZMA2InputStream(
                new ByteArrayInputStream(compressed),
                DICTIONARY_SIZE
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies Arkivo decodes independently generated raw LZMA2.
    @Test
    public void readsIndependentLZMA2() throws IOException {
        byte[] content = content();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options independentOptions = new LZMA2Options(5);
        independentOptions.setDictSize((int) DICTIONARY_SIZE);
        try (OutputStream output = independentOptions.getOutputStream(
                new FinishableWrapperOutputStream(compressed),
                ArrayCache.getDummyCache()
        )) {
            output.write(content);
        }

        assertArrayEquals(
                content,
                decompress(LZMA2_CODEC, compressed.toByteArray())
        );
    }

    /// Verifies model-property validation and operation-scoped window limits.
    @Test
    public void validatesExternalModelMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(4, 1, 2, DICTIONARY_SIZE)
        );
        assertThrows(
                DecompressionWindowLimitException.class,
                () -> LZMA2_CODEC.newReadableByteChannel(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        DecompressionLimits.ofMaximumWindowSize(DICTIONARY_SIZE - 1L),
                        ResourceOwnership.BORROWED
                )
        );
    }


    /// Compresses one byte array with a public codec.
    private static byte[] compress(
            CompressionCodec codec,
            byte[] content
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed)
        );
        return compressed.toByteArray();
    }

    /// Decompresses one byte array with a public codec.
    private static byte[] decompress(
            CompressionCodec codec,
            byte[] compressed
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Packs LZMA literal and position properties.
    private static int propertyByte(
            int literalContextBits,
            int literalPositionBits,
            int positionBits
    ) {
        return (positionBits * 5 + literalPositionBits) * 9 + literalContextBits;
    }

    /// Returns mixed repetitive and incompressible test content.
    private static byte[] content() {
        byte[] prefix = (
                "raw LZMA container codec interoperability;"
                        + "0123456789abcdef;"
        ).repeat(4096).getBytes(StandardCharsets.UTF_8);
        byte[] content = java.util.Arrays.copyOf(prefix, prefix.length + 8192);
        int state = 0x5eeda11;
        for (int index = prefix.length; index < content.length; index++) {
            state = state * 1103515245 + 12345;
            content[index] = (byte) (state >>> 16);
        }
        return content;
    }
}
