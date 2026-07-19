// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public Deflate64 codec contract.
@NotNullByDefault
final class Deflate64CodecTest {
    /// Verifies immutable Deflate64 compression-level configuration.
    @Test
    void exposesCompressionLevelConfiguration() {
        Deflate64Codec codec = new Deflate64Codec();

        assertEquals(6L, codec.compressionLevel());
        assertEquals(9L, codec.withCompressionLevel(9L).compressionLevel());
        assertEquals(0L, codec.minimumCompressionLevel());
        assertEquals(9L, codec.maximumCompressionLevel());
        assertEquals(6L, codec.defaultCompressionLevel());
    }

    /// Verifies direct buffers, decoder counters, source ownership, and strict stored-block validation.
    @Test
    void decodesDirectChannelsAndHonorsOwnership() throws IOException {
        byte[] content = ("direct Deflate64 channel " + "0123456789".repeat(512))
                .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = storedBlock(content);
        Deflate64Codec codec = new Deflate64Codec();

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(compressed));
        DecompressingReadableByteChannel decoder = codec.newReadableByteChannel(
                source,
                ResourceOwnership.OWNED
        );
        ByteBuffer output = ByteBuffer.allocateDirect(content.length);
        while (output.hasRemaining()) {
            assertTrue(decoder.read(output) > 0);
        }
        assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
        assertEquals(compressed.length, decoder.inputBytes());
        assertEquals(content.length, decoder.outputBytes());
        decoder.close();
        assertFalse(source.isOpen());
        output.flip();
        byte[] actual = new byte[output.remaining()];
        output.get(actual);
        assertArrayEquals(content, actual);

        ReadableByteChannel retained = Channels.newChannel(new ByteArrayInputStream(compressed));
        DecompressingReadableByteChannel retainedDecoder = codec.newReadableByteChannel(
                retained,
                ResourceOwnership.BORROWED
        );
        retainedDecoder.close();
        assertTrue(retained.isOpen());

        byte[] corrupt = compressed.clone();
        corrupt[3] ^= 1;
        assertThrows(IOException.class, () -> {
            try (DecompressingReadableByteChannel invalid = codec.newReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(corrupt))
            )) {
                invalid.read(ByteBuffer.allocateDirect(content.length));
            }
        });
    }

    /// Creates one final raw stored block.
    private static byte[] storedBlock(byte[] content) {
        if (content.length > 0xffff) {
            throw new IllegalArgumentException("Stored block content is too large");
        }
        byte[] compressed = new byte[content.length + 5];
        compressed[0] = 0x01;
        compressed[1] = (byte) content.length;
        compressed[2] = (byte) (content.length >>> 8);
        int complement = content.length ^ 0xffff;
        compressed[3] = (byte) complement;
        compressed[4] = (byte) (complement >>> 8);
        System.arraycopy(content, 0, compressed, 5, content.length);
        return compressed;
    }
}
