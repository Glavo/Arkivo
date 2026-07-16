// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests zlib codec behavior.
@NotNullByDefault
public final class ZlibCodecTest {
    /// Verifies that zlib compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        ZlibCodec codec = new ZlibCodec();
        byte[] input = "hello zlib".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(ZlibCodec.NAME, codec.format().name());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the zlib codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(ZlibCodec.class, Objects.requireNonNull(CompressionFormats.find(ZlibCodec.NAME)).defaultCodec().getClass());
    }

    /// Verifies zlib header matching.
    @Test
    public void metadata() {
        ZlibCodec codec = new ZlibCodec();
        assertEquals(true, codec.format().matches(ByteBuffer.wrap(new byte[]{0x78, (byte) 0x9c})));
        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{0x78, 0x00})));
        assertEquals(0L, codec.minimumCompressionLevel());
        assertEquals(9L, codec.maximumCompressionLevel());
        assertEquals(6L, codec.defaultCompressionLevel());
    }

    /// Verifies that the decoder derives its limit from the zlib header's declared window size.
    @Test
    public void decoderEnforcesDeclaredWindowSize() throws IOException {
        byte[] content = "small zlib window".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        ZlibCodec codec = new ZlibCodec();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressedBytes)
        );
        byte[] compressed = withMinimumWindowHeader(compressedBytes.toByteArray());

        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decodedBytes),
                DecompressionLimits.ofMaximumWindowSize(256L)
        );
        assertArrayEquals(content, decodedBytes.toByteArray());

        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressed)),
                        Channels.newChannel(new ByteArrayOutputStream()),
                        DecompressionLimits.ofMaximumWindowSize(255L)
                )
        );
        assertEquals(255L, exception.maximumWindowSize());
        assertEquals(256L, exception.requiredWindowSize());

        IOException malformed = assertThrows(
                IOException.class,
                () -> codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(new byte[]{0x78, 0x00})),
                        Channels.newChannel(new ByteArrayOutputStream()),
                        DecompressionLimits.ofMaximumWindowSize(0L)
                )
        );
        assertFalse(malformed instanceof DecompressionWindowLimitException);
    }

    /// Verifies zlib preset dictionary negotiation, identifiers, and JDK interoperability.
    @Test
    public void presetDictionaryInteroperability() throws IOException, DataFormatException {
        byte[] dictionaryBytes = (
                "Arkivo zlib preset dictionary common phrase 0123456789;"
        ).repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOfRange(dictionaryBytes, dictionaryBytes.length - 512, dictionaryBytes.length);
        Adler32 adler32 = new Adler32();
        adler32.update(dictionaryBytes);
        CompressionDictionary dictionary = CompressionDictionary.of(dictionaryBytes, adler32.getValue());
        ZlibCodec codec = new ZlibCodec().withDictionary(dictionary);

        assertEquals(dictionary, codec.dictionary());
        ByteArrayOutputStream compressedByCodec = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressedByCodec)
        );
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressedByCodec.toByteArray());
            ByteArrayOutputStream decodedByJdk = new ByteArrayOutputStream();
            byte[] chunk = new byte[73];
            while (!inflater.finished()) {
                int produced = inflater.inflate(chunk);
                if (produced > 0) {
                    decodedByJdk.write(chunk, 0, produced);
                } else if (inflater.needsDictionary()) {
                    inflater.setDictionary(dictionaryBytes);
                } else {
                    assertEquals(false, inflater.needsInput());
                    throw new AssertionError("JDK zlib inflater made no progress");
                }
            }
            assertArrayEquals(input, decodedByJdk.toByteArray());
        } finally {
            inflater.end();
        }

        ByteArrayOutputStream decodedByCodec = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressedByCodec.toByteArray())),
                Channels.newChannel(decodedByCodec)
        );
        assertArrayEquals(input, decodedByCodec.toByteArray());

        ByteArrayOutputStream compressedByJdk = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);
        deflater.setDictionary(dictionaryBytes);
        try (OutputStream output = new DeflaterOutputStream(compressedByJdk, deflater)) {
            output.write(input);
        }
        ByteArrayOutputStream decodedJdkStream = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                Channels.newChannel(decodedJdkStream)
        );
        assertArrayEquals(input, decodedJdkStream.toByteArray());

        assertThrows(
                IOException.class,
                () -> new ZlibCodec().decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream())
                )
        );
        ZlibCodec wrongBytesCodec = new ZlibCodec().withDictionary(
                CompressionDictionary.of(new byte[dictionaryBytes.length])
        );
        assertThrows(
                IOException.class,
                () -> wrongBytesCodec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream())
                )
        );

        CompressionDictionary wrongIdentifier =
                CompressionDictionary.of(dictionaryBytes, adler32.getValue() + 1L);
        ZlibCodec wrongIdentifierCodec = new ZlibCodec().withDictionary(wrongIdentifier);
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongIdentifierCodec.openEncoder(
                        Channels.newChannel(new ByteArrayOutputStream()),
                        ChannelOwnership.RETAIN
                )
        );
        assertThrows(
                IOException.class,
                () -> wrongIdentifierCodec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream())
                )
        );
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

    /// Returns a copy whose valid zlib header declares the minimum 256-byte window.
    private static byte[] withMinimumWindowHeader(byte[] compressed) {
        byte[] adjusted = compressed.clone();
        int compressionMethodAndFlags = 0x08;
        int flags = Byte.toUnsignedInt(adjusted[1]) & 0xe0;
        for (int check = 0; check < 32; check++) {
            int candidate = flags | check;
            if (((compressionMethodAndFlags << 8) | candidate) % 31 == 0) {
                adjusted[0] = (byte) compressionMethodAndFlags;
                adjusted[1] = (byte) candidate;
                return adjusted;
            }
        }
        throw new AssertionError("Unable to construct a valid zlib header");
    }
}
