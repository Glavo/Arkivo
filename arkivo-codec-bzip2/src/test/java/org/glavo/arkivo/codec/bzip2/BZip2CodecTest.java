// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests BZip2 codec behavior.
@NotNullByDefault
public final class BZip2CodecTest {
    /// Verifies that BZip2 compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        BZip2Codec codec = new BZip2Codec();
        byte[] input = "hello bzip2".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(BZip2Codec.NAME, codec.format().name());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the BZip2 codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(BZip2Codec.class, Objects.requireNonNull(CompressionFormats.find(BZip2Codec.NAME)).defaultCodec().getClass());
        assertEquals(BZip2Codec.class, Objects.requireNonNull(CompressionFormats.find("bz2")).defaultCodec().getClass());
    }

    /// Verifies BZip2 metadata and signature matching.
    @Test
    public void metadata() {
        BZip2Codec codec = new BZip2Codec();
        assertEquals(java.util.List.of("bz2"), codec.format().aliases());
        assertEquals(java.util.List.of("bz2", "bzip2"), codec.format().fileExtensions());
        assertEquals(true, codec.format().matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h', '9', 0x31})));
        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h', '0'})));
        assertEquals(false, codec.format().matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h'})));
    }

    /// Verifies direct channel I/O, compression-level mapping, counters, and ownership.
    @Test
    public void directChannelsExposeLevelCountersAndOwnership() throws IOException {
        byte[] input = ("direct bzip2 channels " + "0123456789".repeat(256))
                .getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length).put(input).flip();
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
        BZip2Codec codec = new BZip2Codec().withCompressionLevel(1L);

        CompressingWritableByteChannel encoder = codec.openEncoder(
                compressedTarget,
                ChannelOwnership.RETAIN
        );
        assertEquals(input.length, encoder.write(source));
        encoder.finish();
        assertEquals(input.length, encoder.inputBytes());
        assertEquals(compressedBytes.size(), encoder.outputBytes());
        assertTrue(compressedTarget.isOpen());
        encoder.close();
        assertTrue(compressedTarget.isOpen());
        assertEquals('1', compressedBytes.toByteArray()[3]);

        WritableByteChannel ownedTarget = Channels.newChannel(new ByteArrayOutputStream());
        CompressingWritableByteChannel owningEncoder = new BZip2Codec().openEncoder(
                ownedTarget,
                ChannelOwnership.CLOSE
        );
        owningEncoder.finish();
        assertFalse(owningEncoder.isOpen());
        assertFalse(ownedTarget.isOpen());

        ReadableByteChannel compressedSource = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        );
        DecompressingReadableByteChannel decoder = new BZip2Codec().openDecoder(
                compressedSource,
                ChannelOwnership.CLOSE
        );
        ByteBuffer decoded = ByteBuffer.allocateDirect(input.length);
        while (decoded.hasRemaining()) {
            assertTrue(decoder.read(decoded) > 0);
        }
        assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
        assertEquals(compressedBytes.size(), decoder.inputBytes());
        assertEquals(input.length, decoder.outputBytes());
        decoder.close();
        assertFalse(compressedSource.isOpen());
        decoded.flip();
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(input, actual);
    }

    /// Rejects BZip2 block-size levels outside the format range.
    @Test
    public void rejectsInvalidCompressionLevels() {
        BZip2Codec codec = new BZip2Codec();
        assertEquals(1L, codec.minimumCompressionLevel());
        assertEquals(9L, codec.maximumCompressionLevel());
        assertEquals(9L, codec.defaultCompressionLevel());

        assertThrows(IllegalArgumentException.class, () -> codec.withCompressionLevel(10L));
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
