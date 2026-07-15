// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.EncodeDirective;
import org.glavo.arkivo.codec.StandardCodecOptions;
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
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests gzip codec behavior.
@NotNullByDefault
public final class GzipCodecTest {
    /// Verifies that gzip compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        GzipCodec codec = new GzipCodec();
        byte[] input = "hello gzip".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(GzipCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the gzip codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(GzipCodec.class, Objects.requireNonNull(CompressionCodecs.find(GzipCodec.NAME)).getClass());
    }

    /// Verifies gzip metadata and signature matching.
    @Test
    public void metadata() {
        GzipCodec codec = new GzipCodec();
        assertEquals(java.util.List.of("gz", "gzip"), codec.fileExtensions());
        assertEquals(true, codec.matches(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x8b, 0x08})));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{0x1f})));
    }

    /// Verifies large gzip members interoperate in both directions with the JDK implementation.
    @Test
    public void jdkStreamCompatibility() throws IOException {
        byte[] input = ("Arkivo gzip compatibility: " + "0123456789".repeat(1024))
                .getBytes(StandardCharsets.UTF_8);
        GzipCodec codec = new GzipCodec();

        ByteArrayOutputStream encodedByCodec = new ByteArrayOutputStream();
        try (OutputStream output = codec.compressTo(encodedByCodec)) {
            output.write(input);
        }
        try (InputStream inputStream = new GZIPInputStream(
                new ByteArrayInputStream(encodedByCodec.toByteArray())
        )) {
            assertArrayEquals(input, inputStream.readAllBytes());
        }

        byte[] encodedByJdk = gzip(input);
        try (InputStream inputStream = codec.decompressFrom(new ByteArrayInputStream(encodedByJdk))) {
            assertArrayEquals(input, inputStream.readAllBytes());
        }
    }

    /// Verifies concatenated gzip members are decoded as one logical stream.
    @Test
    public void concatenatedMembers() throws IOException {
        byte[] first = "first member".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second member".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.writeBytes(gzip(first));
        concatenated.writeBytes(gzip(second));

        try (InputStream input = new GzipCodec().decompressFrom(
                new ByteArrayInputStream(concatenated.toByteArray())
        )) {
            assertArrayEquals(
                    "first membersecond member".getBytes(StandardCharsets.UTF_8),
                    input.readAllBytes()
            );
        }
    }

    /// Verifies one encoder emits multiple members while preserving its configured strategy.
    @Test
    public void multiMemberEncoder() throws IOException {
        byte[] first = "first Arkivo gzip member".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second Arkivo gzip member".getBytes(StandardCharsets.UTF_8);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_STRATEGY, CompressionStrategy.HUFFMAN_ONLY)
                .build();
        GzipCodec codec = new GzipCodec();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(compressed),
                options,
                ChannelOwnership.RETAIN
        );
        encoder.encode(ByteBuffer.wrap(first), EncodeDirective.END_FRAME);
        int firstMemberSize = compressed.size();
        assertTrue(encoder.isOpen());
        encoder.flush();
        assertEquals(firstMemberSize, compressed.size());

        encoder.encode(ByteBuffer.wrap(second), EncodeDirective.END_FRAME);
        int completeSize = compressed.size();
        assertTrue(completeSize > firstMemberSize);
        encoder.finish();
        assertFalse(encoder.isOpen());
        assertEquals(completeSize, compressed.size());

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(first);
        expected.writeBytes(second);
        try (GZIPInputStream input = new GZIPInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(expected.toByteArray(), input.readAllBytes());
        }
    }

    /// Verifies optional header fields and the header checksum are parsed and validated.
    @Test
    public void optionalHeaderFields() throws IOException {
        byte[] content = "optional gzip header".getBytes(StandardCharsets.UTF_8);
        byte[] member = gzipWithOptionalHeader(content);

        try (InputStream input = new GzipCodec().decompressFrom(new ByteArrayInputStream(member))) {
            assertArrayEquals(content, input.readAllBytes());
        }

        member[12] ^= 1;
        assertThrows(IOException.class, () -> {
            try (InputStream input = new GzipCodec().decompressFrom(new ByteArrayInputStream(member))) {
                input.readAllBytes();
            }
        });
    }

    /// Verifies corrupted member checksum and size trailers are rejected.
    @Test
    public void corruptedTrailer() throws IOException {
        byte[] member = gzip("checked content".getBytes(StandardCharsets.UTF_8));
        member[member.length - 8] ^= 1;

        assertThrows(IOException.class, () -> {
            try (InputStream input = new GzipCodec().decompressFrom(new ByteArrayInputStream(member))) {
                input.readAllBytes();
            }
        });
    }

    /// Encodes one gzip member through the JDK implementation.
    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(content);
        }
        return output.toByteArray();
    }

    /// Replaces the fixed JDK header with optional fields and a valid header checksum.
    private static byte[] gzipWithOptionalHeader(byte[] content) throws IOException {
        byte[] member = gzip(content);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x1f);
        header.write(0x8b);
        header.write(8);
        header.write(0x1e);
        header.write(member, 4, 6);
        header.write(3);
        header.write(0);
        header.write(new byte[]{1, 2, 3});
        header.writeBytes("name\0".getBytes(StandardCharsets.ISO_8859_1));
        header.writeBytes("comment\0".getBytes(StandardCharsets.ISO_8859_1));

        CRC32 checksum = new CRC32();
        checksum.update(header.toByteArray());
        int headerChecksum = (int) checksum.getValue() & 0xffff;
        header.write(headerChecksum);
        header.write(headerChecksum >>> 8);
        header.write(member, 10, member.length - 10);
        return header.toByteArray();
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
}
