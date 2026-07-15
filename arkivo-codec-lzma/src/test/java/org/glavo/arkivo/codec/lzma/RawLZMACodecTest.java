// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.StandardCodecOptions;
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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies public raw LZMA and LZMA2 codecs against an independent implementation.
@NotNullByDefault
public final class RawLZMACodecTest {
    /// The dictionary size used by raw-stream interoperability tests.
    private static final long DICTIONARY_SIZE = 1L << 20;

    /// Verifies both raw codecs are discoverable only by their explicit names.
    @Test
    public void discoversRawProviders() {
        assertInstanceOf(
                RawLZMACodec.class,
                Objects.requireNonNull(CompressionCodecs.find(RawLZMACodec.NAME))
        );
        assertInstanceOf(
                RawLZMACodec.class,
                Objects.requireNonNull(CompressionCodecs.find("raw-lzma"))
        );
        assertInstanceOf(
                LZMA2Codec.class,
                Objects.requireNonNull(CompressionCodecs.find(LZMA2Codec.NAME))
        );
    }

    /// Verifies Arkivo's EOS-terminated raw LZMA output with XZ for Java.
    @Test
    public void independentDecoderReadsRawLZMA() throws IOException {
        byte[] content = content();
        CodecOptions options = modelOptions().build();
        byte[] compressed = compress(new RawLZMACodec(), content, options);

        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed),
                -1L,
                (byte) propertyByte(1, 2, 3),
                (int) DICTIONARY_SIZE
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
                decompress(
                        new RawLZMACodec(),
                        compressed.toByteArray(),
                        modelOptions().build()
                )
        );
    }

    /// Verifies exact-size raw LZMA decoding leaves following container bytes unread.
    @Test
    public void exactSizePreservesFollowingBytes() throws IOException {
        byte[] content = content();
        CodecOptions encodingOptions = modelOptions()
                .set(LZMAOptions.END_MARKER, false)
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) content.length)
                .build();
        byte[] compressed = compress(new RawLZMACodec(), content, encodingOptions);
        byte[] framed = java.util.Arrays.copyOf(compressed, compressed.length + 1);
        framed[framed.length - 1] = 0x6a;
        ByteArrayInputStream source = new ByteArrayInputStream(framed);
        CodecOptions decodingOptions = modelOptions()
                .set(LZMAOptions.DECODED_SIZE, (long) content.length)
                .build();

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (DecompressingReadableByteChannel decoder = new RawLZMACodec().openDecoder(
                Channels.newChannel(source),
                decodingOptions,
                ChannelOwnership.RETAIN
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
        CodecOptions options = dictionaryOptions();
        byte[] compressed = compress(new LZMA2Codec(), content, options);

        try (org.tukaani.xz.LZMA2InputStream input = new org.tukaani.xz.LZMA2InputStream(
                new ByteArrayInputStream(compressed),
                (int) DICTIONARY_SIZE
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
                decompress(new LZMA2Codec(), compressed.toByteArray(), dictionaryOptions())
        );
    }

    /// Verifies raw decoders reject absent model metadata and configured window limits.
    @Test
    public void validatesExternalModelMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RawLZMACodec().openDecoder(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        CodecOptions.EMPTY,
                        ChannelOwnership.RETAIN
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMA2Codec().openDecoder(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        CodecOptions.EMPTY,
                        ChannelOwnership.RETAIN
                )
        );
        CodecOptions limited = CodecOptions.builder()
                .set(LZMAOptions.DICTIONARY_SIZE, DICTIONARY_SIZE)
                .set(StandardCodecOptions.MAX_WINDOW_SIZE, DICTIONARY_SIZE - 1L)
                .build();
        assertThrows(
                IOException.class,
                () -> new LZMA2Codec().openDecoder(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        limited,
                        ChannelOwnership.RETAIN
                )
        );
    }

    /// Returns shared raw LZMA model options.
    private static CodecOptions.Builder modelOptions() {
        return CodecOptions.builder()
                .set(LZMAOptions.DICTIONARY_SIZE, DICTIONARY_SIZE)
                .set(LZMAOptions.LITERAL_CONTEXT_BITS, 1L)
                .set(LZMAOptions.LITERAL_POSITION_BITS, 2L)
                .set(LZMAOptions.POSITION_BITS, 3L);
    }

    /// Returns shared raw LZMA2 dictionary options.
    private static CodecOptions dictionaryOptions() {
        return CodecOptions.builder()
                .set(LZMAOptions.DICTIONARY_SIZE, DICTIONARY_SIZE)
                .build();
    }

    /// Compresses one byte array with a public codec.
    private static byte[] compress(
            org.glavo.arkivo.codec.CompressionCodec codec,
            byte[] content,
            CodecOptions options
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed),
                options
        );
        return compressed.toByteArray();
    }

    /// Decompresses one byte array with a public codec.
    private static byte[] decompress(
            org.glavo.arkivo.codec.CompressionCodec codec,
            byte[] compressed,
            CodecOptions options
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded),
                options
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
