// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.lzma.internal.LZMA2ChannelDecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMA2ChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FinishableWrapperOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests LZMA codec behavior.
@NotNullByDefault
public final class LZMACodecTest {
    /// Verifies that LZMA compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        LZMACodec codec = new LZMACodec();
        byte[] input = "hello lzma".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec<?>);
        assertEquals(LZMACodec.NAME, codec.format().name());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the LZMA codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(LZMACodec.class, CompressionFormats.require(LZMACodec.NAME).defaultCodec().getClass());
    }

    /// Rejects unsigned LZMA-alone output sizes that cannot be represented by the public size model.
    @Test
    public void rejectsUnrepresentableStandaloneSize() {
        byte[] header = new byte[13];
        header[0] = 0x5d;
        header[1] = 0x00;
        header[2] = 0x10;
        header[12] = (byte) 0x80;

        IOException channelFailure = assertThrows(IOException.class, () -> {
            try (DecompressingReadableByteChannel decoder = new LZMACodec().openDecoder(
                    Channels.newChannel(new ByteArrayInputStream(header))
            )) {
                decoder.read(ByteBuffer.allocate(1));
            }
        });
        assertTrue(channelFailure.getMessage().contains("Unsupported LZMA uncompressed size"));

        IOException streamFailure = assertThrows(IOException.class, () -> {
            try (InputStream input = new LZMACodec().openDecoder(new ByteArrayInputStream(header))) {
                input.read();
            }
        });
        assertTrue(streamFailure.getMessage().contains("Unsupported LZMA uncompressed size"));
    }
    /// Verifies Arkivo's decoder against EOS-terminated LZMA-alone output from XZ for Java.
    @Test
    public void pureJavaDecoderReadsIndependentEndMarkedStream() throws IOException {
        byte[] content = patternedContent(320_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(6);
        options.setDictSize(1 << 20);
        options.setLcLp(3, 0);
        options.setPb(2);
        try (org.tukaani.xz.LZMAOutputStream output =
                     new org.tukaani.xz.LZMAOutputStream(compressed, options, -1L)) {
            output.write(content);
        }

        try (InputStream input = new LZMACodec().openDecoder(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }
    /// Verifies Arkivo's decoder against a known-size LZMA-alone stream without an end marker.
    @Test
    public void pureJavaDecoderReadsIndependentKnownSizeStream() throws IOException {
        byte[] content = patternedContent(90_123);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(4);
        options.setDictSize(1 << 18);
        options.setLcLp(2, 1);
        options.setPb(1);
        try (org.tukaani.xz.LZMAOutputStream output =
                     new org.tukaani.xz.LZMAOutputStream(compressed, options, content.length)) {
            output.write(content);
        }

        try (InputStream input = new LZMACodec().openDecoder(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }
    /// Verifies an exact-size stream may contain EOPM but cannot encode additional output past its header size.
    @Test
    public void validatesIndependentKnownSizeStreamTermination() throws IOException {
        byte[] content = patternedContent(1_013);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(4);
        options.setDictSize(1 << 16);
        try (org.tukaani.xz.LZMAOutputStream output =
                     new org.tukaani.xz.LZMAOutputStream(compressed, options, -1L)) {
            output.write(content);
        }

        byte[] knownSizeWithEndMarker = compressed.toByteArray();
        writeLittleEndian(knownSizeWithEndMarker, 5, content.length);
        try (InputStream input = new LZMACodec().openDecoder(
                new ByteArrayInputStream(knownSizeWithEndMarker)
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        byte[] undersized = knownSizeWithEndMarker.clone();
        writeLittleEndian(undersized, 5, content.length - 1L);
        assertThrows(
                IOException.class,
                () -> new LZMACodec().decompress(
                        Channels.newChannel(new ByteArrayInputStream(undersized)),
                        Channels.newChannel(new ByteArrayOutputStream())
                )
        );
        assertThrows(
                IOException.class,
                () -> {
                    try (InputStream input = new LZMACodec().openDecoder(
                            new ByteArrayInputStream(undersized)
                    )) {
                        input.readAllBytes();
                    }
                }
        );
    }
    /// Verifies Arkivo's raw decoder against ZIP-style end-marked LZMA output.
    @Test
    public void pureJavaDecoderReadsIndependentRawStream() throws IOException {
        byte[] content = patternedContent(180_777);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(5);
        options.setDictSize(1 << 19);
        options.setLcLp(1, 2);
        options.setPb(3);
        try (org.tukaani.xz.LZMAOutputStream output =
                     new org.tukaani.xz.LZMAOutputStream(compressed, options, true)) {
            output.write(content);
        }

        RawLZMACodec codec = new RawLZMACodec().withProperties(
                new LZMAProperties(1, 2, 3, 1 << 19)
        );
        assertArrayEquals(content, decompress(codec, compressed.toByteArray()));
    }
    /// Verifies Arkivo's EOS-terminated output through XZ for Java's independent decoder.
    @Test
    public void pureJavaEncoderWritesIndependentEndMarkedStream() throws IOException {
        byte[] content = patternedContent(350_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = new LZMACodec().openEncoder(compressed)) {
            for (byte value : content) {
                output.write(value);
            }
        }

        assertTrue(compressed.size() < content.length / 2);
        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }
    /// Verifies Arkivo's known-size output without an end marker through an independent decoder.
    @Test
    public void pureJavaEncoderWritesIndependentKnownSizeStream() throws IOException {
        byte[] content = patternedContent(131_321);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMACodec codec = new LZMACodec(
                new LZMAProperties(2, 1, 1, 1 << 18)
        );
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                content.length,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(content, 0, 17));
            encoder.write(ByteBuffer.wrap(content, 17, content.length - 17));
            encoder.finish();
        }

        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }
    /// Verifies Arkivo's LZMA2 decoder against independent compressed and uncompressed chunks.
    @Test
    public void pureJavaLZMA2DecoderReadsIndependentStream() throws IOException {
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

        LZMA2Codec codec = new LZMA2Codec().withDictionarySize(dictionarySize);
        assertArrayEquals(content, decompress(codec, compressed.toByteArray()));
    }
    /// Verifies Arkivo's LZMA2 compressed and stored chunks through XZ for Java's decoder.
    @Test
    public void pureJavaLZMA2EncoderWritesIndependentStream() throws IOException {
        byte[] content = mixedLzma2Content();
        int dictionarySize = 1 << 20;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        LZMA2Codec codec = new LZMA2Codec().withDictionarySize(dictionarySize);
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(content, 0, 31));
            int offset = 31;
            while (offset < content.length) {
                int count = Math.min(1 + offset % 127, content.length - offset);
                encoder.write(ByteBuffer.wrap(content, offset, count));
                offset += count;
            }
            encoder.finish();
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
    public void pureJavaEncoderSupportsEveryPropertyCombination() throws IOException {
        byte[] content = patternedContent(9_321);
        for (int literalPositionBits = 0; literalPositionBits <= 4; literalPositionBits++) {
            for (int literalContextBits = 0;
                 literalContextBits + literalPositionBits <= 4;
                 literalContextBits++) {
                for (int positionBits = 0; positionBits <= 4; positionBits++) {
                    LZMACodec codec = new LZMACodec(new LZMAProperties(
                            literalContextBits,
                            literalPositionBits,
                            positionBits,
                            1 << 14
                    ));
                    byte[] compressed = compress(codec, content);
                    try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                            new ByteArrayInputStream(compressed)
                    )) {
                        assertArrayEquals(content, input.readAllBytes());
                    }
                }
            }
        }
    }
    /// Verifies strict failures for truncation, invalid LZMA2 state, and declared-size mismatches.
    @Test
    public void pureJavaCodecsRejectMalformedOrMismatchedInput() throws IOException {
        byte[] content = patternedContent(4_096);
        LZMACodec lzmaCodec = new LZMACodec().withDictionarySize(1 << 14);
        byte[] compressed = compress(lzmaCodec, content);
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
        assertThrows(
                IOException.class,
                () -> decompress(new LZMACodec(), truncated)
        );

        LZMA2Codec lzma2Codec = new LZMA2Codec().withDictionarySize(1 << 14);
        assertThrows(
                IOException.class,
                () -> decompress(lzma2Codec, new byte[]{0x02, 0x00, 0x00, 0x00})
        );

        CompressingWritableByteChannel encoder = lzmaCodec.openEncoder(
                Channels.newChannel(new ByteArrayOutputStream()),
                content.length + 1L,
                ChannelOwnership.RETAIN
        );
        encoder.write(ByteBuffer.wrap(content));
        assertThrows(IOException.class, encoder::finish);
        encoder.close();
    }
    /// Verifies immutable LZMA properties, known-size headers, and pledged-size enforcement.
    @Test
    public void immutableConfigurationControlsHeaderAndPledgedSize() throws IOException {
        byte[] content = patternedContent(120_321);
        int dictionarySize = 1 << 17;
        int literalContextBits = 2;
        int literalPositionBits = 1;
        int positionBits = 3;
        LZMAProperties properties = new LZMAProperties(
                literalContextBits,
                literalPositionBits,
                positionBits,
                dictionarySize
        );
        LZMACodec codec = new LZMACodec(properties);
        assertEquals(properties, codec.properties());

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                content.length,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(content));
            encoder.finish();
        }
        byte[] encoded = compressed.toByteArray();
        assertEquals(properties.propertyByte(), Byte.toUnsignedInt(encoded[0]));
        assertEquals(dictionarySize, littleEndianInt(encoded, 1));
        assertEquals(content.length, littleEndianLong(encoded, 5));

        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(encoded)
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        ByteArrayOutputStream emptyCompressed = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(emptyCompressed),
                0L,
                ChannelOwnership.RETAIN
        )) {
            encoder.finish();
        }
        byte[] emptyEncoded = emptyCompressed.toByteArray();
        assertEquals(0L, littleEndianLong(emptyEncoded, 5));
        try (org.tukaani.xz.LZMAInputStream input = new org.tukaani.xz.LZMAInputStream(
                new ByteArrayInputStream(emptyEncoded)
        )) {
            assertArrayEquals(new byte[0], input.readAllBytes());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(3, 0, 2, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(5, 0, 2, dictionarySize)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(0, 5, 2, dictionarySize)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(3, 0, 5, dictionarySize)
        );
        assertThrows(IllegalArgumentException.class, () -> codec.newEncoder(-2L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMAProperties(4, 1, 2, dictionarySize)
        );

        assertThrows(
                IOException.class,
                () -> encodeWithPledgedSize(codec, content, content.length - 1L)
        );

        ByteArrayOutputStream incomplete = new ByteArrayOutputStream();
        CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(incomplete),
                content.length + 1L,
                ChannelOwnership.RETAIN
        );
        encoder.write(ByteBuffer.wrap(content));
        assertThrows(IOException.class, encoder::finish);
        encoder.close();
    }

    /// Verifies direct LZMA-alone channels, dictionary configuration, counters, and ownership.
    @Test
    public void directChannelsExposeDictionaryCountersAndOwnership() throws IOException {
        byte[] content = patternedContent(180_123);
        ByteBuffer source = ByteBuffer.allocateDirect(content.length).put(content).flip();
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
        LZMACodec codec = new LZMACodec().withDictionarySize(1 << 16);

        CompressingWritableByteChannel encoder = codec.openEncoder(
                compressedTarget,
                ChannelOwnership.RETAIN
        );
        assertEquals(content.length, encoder.write(source));
        encoder.finish();
        assertEquals(content.length, encoder.inputBytes());
        assertEquals(compressedBytes.size(), encoder.outputBytes());
        assertFalse(encoder.isOpen());
        assertTrue(compressedTarget.isOpen());
        byte[] encoded = compressedBytes.toByteArray();
        assertEquals(1 << 16, littleEndianInt(encoded, 1));

        ReadableByteChannel compressedSource = Channels.newChannel(new ByteArrayInputStream(encoded));
        DecompressingReadableByteChannel decoder = new LZMACodec().openDecoder(
                compressedSource,
                ChannelOwnership.CLOSE
        );
        ByteBuffer decoded = ByteBuffer.allocateDirect(content.length);
        while (decoded.hasRemaining()) {
            assertTrue(decoder.read(decoded) > 0);
        }
        assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
        assertEquals(content.length, decoder.outputBytes());
        decoder.close();
        assertFalse(compressedSource.isOpen());
        decoded.flip();
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(content, actual);

        assertThrows(
                IllegalArgumentException.class,
                () -> new LZMACodec().withDictionarySize(
                        LZMAProperties.MAXIMUM_DICTIONARY_SIZE + 1
                )
        );
    }

    /// Verifies that the decoder enforces the dictionary size declared by an LZMA-alone header.
    @Test
    public void decoderEnforcesDeclaredDictionarySize() throws IOException {
        int dictionarySize = 1 << 16;
        byte[] content = patternedContent(180_123);
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        LZMACodec codec = new LZMACodec().withDictionarySize(dictionarySize);
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressedBytes)
        );

        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                Channels.newChannel(decodedBytes),
                DecompressionLimits.ofMaximumWindowSize(dictionarySize)
        );
        assertArrayEquals(content, decodedBytes.toByteArray());

        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream()),
                        DecompressionLimits.ofMaximumWindowSize(dictionarySize - 1L)
                )
        );
        assertEquals(dictionarySize - 1L, exception.maximumWindowSize());
        assertEquals(dictionarySize, exception.requiredWindowSize());
    }

    /// Verifies reusable direct LZMA2 channel contexts and their progress counters.
    @Test
    public void directLzma2ContextsRoundTripLargeInput() throws IOException {
        byte[] content = mixedLzma2Content();
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(compressedBytes);
        LZMA2ChannelEncoder encoder = new LZMA2ChannelEncoder(
                target,
                ChannelOwnership.RETAIN,
                1 << 20
        );
        ByteBuffer source = ByteBuffer.allocateDirect(content.length).put(content).flip();
        assertEquals(content.length, encoder.write(source));
        encoder.finish();
        assertEquals(content.length, encoder.inputBytes());
        assertEquals(compressedBytes.size(), encoder.outputBytes());
        assertTrue(target.isOpen());

        ReadableByteChannel compressedSource = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        );
        LZMA2ChannelDecoder decoder = new LZMA2ChannelDecoder(
                compressedSource,
                ChannelOwnership.RETAIN,
                1 << 20
        );
        ByteBuffer decoded = ByteBuffer.allocateDirect(content.length);
        while (decoded.hasRemaining()) {
            assertTrue(decoder.read(decoded) > 0);
        }
        assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
        assertEquals(content.length, decoder.outputBytes());
        assertTrue(decoder.inputBytes() > 0L);
        decoder.close();
        assertTrue(compressedSource.isOpen());
        decoded.flip();
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(content, actual);
    }

    /// Reads one little-endian 32-bit value from a byte array.
    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    /// Reads one little-endian 64-bit value from a byte array.
    private static long littleEndianLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

    /// Writes one little-endian 64-bit value into a byte array.
    private static void writeLittleEndian(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    /// Compresses one array through an encoder carrying explicit source-size metadata.
    private static byte[] encodeWithPledgedSize(
            LZMACodec codec,
            byte[] content,
            long pledgedSourceSize
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                pledgedSourceSize,
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(content));
            encoder.finish();
        }
        return compressed.toByteArray();
    }

    /// Compresses bytes through one configured public codec.
    private static byte[] compress(
            CompressionCodec<?> codec,
            byte[] content
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed)
        );
        return compressed.toByteArray();
    }

    /// Decompresses bytes through one configured public codec.
    private static byte[] decompress(
            CompressionCodec<?> codec,
            byte[] compressed
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }
    /// Compresses and decompresses the given bytes.
    private static byte[] roundTrip(CompressionCodec<?> codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codec.openEncoder(compressed)) {
            output.write(input);
        }

        try (InputStream inputStream = codec.openDecoder(new ByteArrayInputStream(compressed.toByteArray()))) {
            return inputStream.readAllBytes();
        }
    }

    /// Returns deterministic data containing literals, short repetitions, and long-distance matches.
    private static byte[] patternedContent(int size) {
        byte[] content = new byte[size];
        byte[] phrase = "Arkivo pure Java LZMA interoperability block\n".getBytes(StandardCharsets.UTF_8);
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
