// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests raw deflate codec behavior.
@NotNullByDefault
public final class DeflateCodecTest {
    /// Verifies that raw deflate compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        DeflateCodec codec = new DeflateCodec();
        byte[] input = "hello deflate".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(DeflateFormat.NAME, codec.format().name());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the raw deflate codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(DeflateCodec.class, CompressionFormats.require(DeflateFormat.NAME).defaultCodec().getClass());
    }

    /// Verifies large channel operations interoperate with the JDK raw deflate streams.
    @Test
    public void jdkStreamCompatibility() throws IOException {
        byte[] input = ("Arkivo raw deflate compatibility: " + "0123456789".repeat(1024))
                .getBytes(StandardCharsets.UTF_8);
        DeflateCodec codec = new DeflateCodec();

        ByteArrayOutputStream encodedByCodec = new ByteArrayOutputStream();
        try (OutputStream output = codec.newOutputStream(encodedByCodec)) {
            output.write(input);
        }
        try (InputStream inputStream = new InflaterInputStream(
                new ByteArrayInputStream(encodedByCodec.toByteArray()),
                new Inflater(true)
        )) {
            assertArrayEquals(input, inputStream.readAllBytes());
        }

        ByteArrayOutputStream encodedByJdk = new ByteArrayOutputStream();
        try (OutputStream output = new DeflaterOutputStream(
                encodedByJdk,
                new Deflater(Deflater.DEFAULT_COMPRESSION, true)
        )) {
            output.write(input);
        }
        try (InputStream inputStream = codec.newInputStream(
                new ByteArrayInputStream(encodedByJdk.toByteArray())
        )) {
            assertArrayEquals(input, inputStream.readAllBytes());
        }

        ByteArrayOutputStream encodedByChannelTransfer = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(encodedByChannelTransfer)
        );
        try (InputStream inputStream = new InflaterInputStream(
                new ByteArrayInputStream(encodedByChannelTransfer.toByteArray()),
                new Inflater(true)
        )) {
            assertArrayEquals(input, inputStream.readAllBytes());
        }

        ByteArrayOutputStream decodedByChannelTransfer = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(encodedByJdk.toByteArray())),
                Channels.newChannel(decodedByChannelTransfer)
        );
        assertArrayEquals(input, decodedByChannelTransfer.toByteArray());
    }

    /// Verifies raw Deflate preset dictionaries across Arkivo and JDK channel implementations.
    @Test
    public void presetDictionaryInteroperability() throws IOException {
        byte[] dictionaryBytes = (
                "Arkivo raw Deflate preset dictionary common phrase 0123456789;"
        ).repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOfRange(dictionaryBytes, dictionaryBytes.length - 512, dictionaryBytes.length);
        RawCompressionDictionary dictionary = RawCompressionDictionary.of(dictionaryBytes);
        DeflateCodec codec = new DeflateCodec().withDictionary(dictionary);

        assertEquals(dictionary, codec.dictionary());
        ByteArrayOutputStream compressedByCodec = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressedByCodec)
        );

        Inflater inflater = new Inflater(true);
        inflater.setDictionary(dictionaryBytes);
        try (InputStream decoded = new InflaterInputStream(
                new ByteArrayInputStream(compressedByCodec.toByteArray()),
                inflater
        )) {
            assertArrayEquals(input, decoded.readAllBytes());
        }

        ByteArrayOutputStream compressedByJdk = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setDictionary(dictionaryBytes);
        try (OutputStream output = new DeflaterOutputStream(compressedByJdk, deflater)) {
            output.write(input);
        }

        ByteArrayOutputStream decodedByCodec = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                Channels.newChannel(decodedByCodec)
        );
        assertArrayEquals(input, decodedByCodec.toByteArray());

        assertThrows(
                IOException.class,
                () -> new DeflateCodec().decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedByJdk.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream())
                )
        );
    }

    /// Compresses and decompresses the given bytes.
    private static byte[] roundTrip(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codec.newOutputStream(compressed)) {
            output.write(input);
        }

        try (InputStream inputStream = codec.newInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            return inputStream.readAllBytes();
        }
    }
}
