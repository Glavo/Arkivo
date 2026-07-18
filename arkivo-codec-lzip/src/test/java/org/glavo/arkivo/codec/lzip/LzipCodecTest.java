// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.lzip.internal.LzipSupport;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests lzip format metadata, member framing, limits, integrity, and independent interoperability.
@NotNullByDefault
public final class LzipCodecTest {
    /// A small exactly representable dictionary used by fast unit tests.
    private static final int TEST_DICTIONARY_SIZE = 64 * 1024;

    /// Verifies format discovery, signatures, extensions, and immutable dictionary configuration.
    @Test
    public void metadataAndConfiguration() {
        LzipCodec codec = new LzipCodec();
        assertSame(LzipFormat.instance(), codec.format());
        assertEquals("lzip", codec.format().name());
        assertEquals(Arrays.asList("lz", "tlz"), codec.format().fileExtensions());
        assertEquals(LzipCodec.DEFAULT_DICTIONARY_SIZE, codec.dictionarySize());
        assertSame(codec, codec.withDictionarySize(codec.dictionarySize()));
        assertEquals(TEST_DICTIONARY_SIZE, codec.withDictionarySize(TEST_DICTIONARY_SIZE).dictionarySize());
        assertThrows(IllegalArgumentException.class, () -> codec.withDictionarySize(5_000));

        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{0, 'L', 'Z', 'I', 'P', 1});
        prefix.position(1);
        assertTrue(codec.format().matches(prefix));
        assertEquals(1, prefix.position());
        assertFalse(codec.format().matches(ByteBuffer.wrap(new byte[]{'L', 'Z', 'I'})));
        assertInstanceOf(
                LzipCodec.class,
                CompressionFormats.require(LzipFormat.NAME).defaultCodec()
        );
    }

    /// Verifies pure Java lzip compression and decompression for patterned and empty members.
    @Test
    public void roundTrip() throws IOException {
        LzipCodec codec = new LzipCodec(TEST_DICTIONARY_SIZE);
        for (byte[] content : new byte[][]{new byte[0], patternedContent(180_321)}) {
            ByteBuffer compressed = codec.compress(ByteBuffer.wrap(content));
            assertTrue(compressed.remaining() >= LzipSupport.HEADER_SIZE + LzipSupport.TRAILER_SIZE);
            ByteBuffer decoded = codec.decompress(compressed, content.length);
            byte[] result = new byte[decoded.remaining()];
            decoded.get(result);
            assertArrayEquals(content, result);
            assertFalse(compressed.hasRemaining());
        }
    }

    /// Verifies Arkivo decodes a lzip member whose raw LZMA payload was produced independently.
    @Test
    public void readsIndependentMember() throws IOException {
        byte[] content = patternedContent(130_777);
        byte[] encoded = independentMember(content, TEST_DICTIONARY_SIZE);
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteBuffer decoded = new LzipCodec().decompress(source, content.length);
        byte[] result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(content, result);
        assertFalse(source.hasRemaining());
    }

    /// Verifies an independent raw LZMA reader and direct trailer parsing accept Arkivo output.
    @Test
    public void writesIndependentMember() throws IOException {
        byte[] content = patternedContent(210_123);
        byte[] encoded = compress(new LzipCodec(TEST_DICTIONARY_SIZE), content);
        assertArrayEquals(LzipSupport.MAGIC, Arrays.copyOf(encoded, LzipSupport.MAGIC.length));
        assertEquals(LzipSupport.VERSION, Byte.toUnsignedInt(encoded[4]));
        assertEquals(
                TEST_DICTIONARY_SIZE,
                LzipSupport.decodeDictionarySize(Byte.toUnsignedInt(encoded[5]))
        );

        int trailerOffset = encoded.length - LzipSupport.TRAILER_SIZE;
        byte[] rawPayload = Arrays.copyOfRange(encoded, LzipSupport.HEADER_SIZE, trailerOffset);
        try (LZMAInputStream input = new LZMAInputStream(
                new ByteArrayInputStream(rawPayload),
                -1L,
                (byte) 0x5d,
                TEST_DICTIONARY_SIZE
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        CRC32 checksum = new CRC32();
        checksum.update(content);
        assertEquals((int) checksum.getValue(), ByteArrayAccess.readIntLittleEndian(encoded, trailerOffset));
        assertEquals(content.length, ByteArrayAccess.readLongLittleEndian(encoded, trailerOffset + 4));
        assertEquals(encoded.length, ByteArrayAccess.readLongLittleEndian(encoded, trailerOffset + 12));
    }

    /// Verifies member boundaries, concatenated decoding, and the absence of a terminal empty member.
    @Test
    public void concatenatedMembers() throws IOException {
        byte[] first = "first lzip member".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] second = patternedContent(91_337);
        LzipCodec codec = new LzipCodec(TEST_DICTIONARY_SIZE);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressingWritableByteChannel.Framed output = codec.newWritableByteChannel(
                Channels.newChannel(encoded),
                EncodingOptions.DEFAULT,
                ResourceOwnership.BORROWED
        )) {
            output.write(ByteBuffer.wrap(first));
            output.finishFrame();
            output.finishFrame();
            output.write(ByteBuffer.wrap(second));
            output.finish();
        }

        byte[] stream = encoded.toByteArray();
        assertEquals(2, countSignatures(stream));
        try (InputStream input = codec.newInputStream(new ByteArrayInputStream(stream))) {
            assertArrayEquals(concatenate(first, second), input.readAllBytes());
        }

        ByteBuffer source = ByteBuffer.wrap(stream);
        ByteBuffer firstTarget = ByteBuffer.allocate(first.length);
        codec.decompressFrame(source, firstTarget);
        assertArrayEquals(first, firstTarget.array());
        assertTrue(source.hasRemaining());
        assertEquals('L', source.get(source.position()));
        assertEquals('Z', source.get(source.position() + 1));

        ByteBuffer secondTarget = ByteBuffer.allocate(second.length);
        codec.decompressFrame(source, secondTarget);
        assertArrayEquals(second, secondTarget.array());
        assertFalse(source.hasRemaining());
    }

    /// Verifies frame-aware channels report exact member boundaries.
    @Test
    public void frameChannelBoundaries() throws IOException {
        byte[] first = independentMember("one".getBytes(StandardCharsets.UTF_8), TEST_DICTIONARY_SIZE);
        byte[] second = independentMember("two".getBytes(StandardCharsets.UTF_8), TEST_DICTIONARY_SIZE);
        byte[] stream = concatenate(first, second);

        try (DecompressingReadableByteChannel.Framed input = new LzipCodec().newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(stream)),
                DecodingOptions.DEFAULT,
                ResourceOwnership.BORROWED
        )) {
            ByteBuffer target = ByteBuffer.allocate(16);
            CodecResult firstResult = input.decodeFrame(target);
            assertEquals(CodecResult.Status.FRAME_FINISHED, firstResult.status());
            target.flip();
            assertEquals("one", StandardCharsets.UTF_8.decode(target).toString());

            target.clear();
            CodecResult secondResult = input.decodeFrame(target);
            assertEquals(CodecResult.Status.FRAME_FINISHED, secondResult.status());
            target.flip();
            assertEquals("two", StandardCharsets.UTF_8.decode(target).toString());

            target.clear();
            assertEquals(CodecResult.Status.END_OF_INPUT, input.decodeFrame(target).status());
        }
    }

    /// Verifies metadata, CRC, size, truncation, and dictionary-window failures are strict checked errors.
    @Test
    public void rejectsMalformedMembers() throws IOException {
        byte[] content = patternedContent(12_345);
        byte[] encoded = independentMember(content, TEST_DICTIONARY_SIZE);

        assertMalformed(changed(encoded, 0, 1), IOException.class);
        assertMalformed(changed(encoded, 4, 1), IOException.class);
        assertMalformed(changed(encoded, 5, 16), IOException.class);
        assertMalformed(changed(encoded, encoded.length - 20, 1), IOException.class);
        assertMalformed(changed(encoded, encoded.length - 16, 1), IOException.class);
        assertMalformed(changed(encoded, encoded.length - 8, 1), IOException.class);
        assertMalformed(Arrays.copyOf(encoded, encoded.length - 1), EOFException.class);

        assertThrows(
                DecompressionWindowLimitException.class,
                () -> new LzipCodec().decompress(
                        ByteBuffer.wrap(encoded),
                        ByteBuffer.allocate(content.length),
                        DecodingOptions.ofMaximumWindowSize(TEST_DICTIONARY_SIZE / 2L)
                )
        );
        assertThrows(
                DecompressionOutputLimitException.class,
                () -> new LzipCodec().decompress(
                        ByteBuffer.wrap(encoded),
                        DecodingOptions.ofMaximumOutputSize(content.length - 1L)
                )
        );
    }

    /// Creates an independently encoded lzip member around an XZ for Java raw LZMA stream.
    private static byte[] independentMember(byte[] content, int dictionarySize) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(6);
        options.setDictSize(dictionarySize);
        options.setLcLp(3, 0);
        options.setPb(2);
        try (LZMAOutputStream output = new LZMAOutputStream(payload, options, true)) {
            output.write(content);
        }

        byte[] rawPayload = payload.toByteArray();
        byte[] result = new byte[
                LzipSupport.HEADER_SIZE + rawPayload.length + LzipSupport.TRAILER_SIZE
                ];
        System.arraycopy(LzipSupport.MAGIC, 0, result, 0, LzipSupport.MAGIC.length);
        result[4] = (byte) LzipSupport.VERSION;
        result[5] = (byte) LzipSupport.encodeDictionarySize(dictionarySize);
        System.arraycopy(rawPayload, 0, result, LzipSupport.HEADER_SIZE, rawPayload.length);

        int trailerOffset = LzipSupport.HEADER_SIZE + rawPayload.length;
        CRC32 checksum = new CRC32();
        checksum.update(content);
        ByteArrayAccess.writeIntLittleEndian(result, trailerOffset, (int) checksum.getValue());
        ByteArrayAccess.writeLongLittleEndian(result, trailerOffset + 4, content.length);
        ByteArrayAccess.writeLongLittleEndian(result, trailerOffset + 12, result.length);
        return result;
    }

    /// Compresses complete content with the supplied lzip codec.
    private static byte[] compress(LzipCodec codec, byte[] content) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var output = codec.newOutputStream(result)) {
            output.write(content);
        }
        return result.toByteArray();
    }

    /// Asserts malformed input fails with the requested checked exception type.
    private static void assertMalformed(
            byte[] encoded,
            Class<? extends IOException> exceptionType
    ) {
        assertThrows(
                exceptionType,
                () -> new LzipCodec().decompress(
                        ByteBuffer.wrap(encoded),
                        ByteBuffer.allocate(1 << 16)
                )
        );
    }

    /// Returns a copy whose selected byte is XORed with the requested mask.
    private static byte[] changed(byte[] source, int index, int mask) {
        byte[] result = source.clone();
        result[index] ^= (byte) mask;
        return result;
    }

    /// Counts exact lzip signatures in a generated stream.
    private static int countSignatures(byte[] source) {
        int count = 0;
        for (int offset = 0; offset <= source.length - LzipSupport.MAGIC.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < LzipSupport.MAGIC.length; index++) {
                matches &= source[offset + index] == LzipSupport.MAGIC[index];
            }
            if (matches) {
                count++;
            }
        }
        return count;
    }

    /// Generates deterministic mixed repetitive and pseudo-random content.
    private static byte[] patternedContent(int size) {
        byte[] result = new byte[size];
        Random random = new Random(0x1a2b3c4dL + size);
        for (int index = 0; index < result.length; index++) {
            result[index] = index % 97 < 79
                    ? (byte) ('a' + index % 7)
                    : (byte) random.nextInt();
        }
        return result;
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

}
