// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests channel-based byte-transform composition and lifecycle behavior.
@NotNullByDefault
public final class TransformingByteChannelsTest {
    /// Verifies direct buffers, deferred tails, and endpoint ownership.
    @Test
    public void roundTripsDeferredTailAndHonorsOwnership() throws IOException {
        byte[] input = new byte[20_003];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 37 + index / 11);
        }

        ByteArrayOutputStream transformedBytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(transformedBytes);
        TransformingWritableByteChannel encoder = new TransformingWritableByteChannel(
                target,
                new DeferredXorTransform(),
                ChannelOwnership.RETAIN
        );
        ByteBuffer source = ByteBuffer.allocateDirect(input.length).put(input).flip();
        while (source.hasRemaining()) {
            encoder.write(source);
        }
        encoder.close();
        assertTrue(target.isOpen());
        assertFalse(Arrays.equals(input, transformedBytes.toByteArray()));

        ReadableByteChannel transformedSource = Channels.newChannel(
                new ByteArrayInputStream(transformedBytes.toByteArray())
        );
        TransformingReadableByteChannel decoder = new TransformingReadableByteChannel(
                transformedSource,
                new DeferredXorTransform(),
                ChannelOwnership.CLOSE
        );
        ByteBuffer decoded = ByteBuffer.allocateDirect(input.length);
        while (decoded.hasRemaining()) {
            decoder.read(decoded);
        }
        decoder.close();
        assertFalse(transformedSource.isOpen());
        decoded.flip();
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(input, actual);
    }

    /// Holds one trailing byte for lookahead and XORs every complete prefix byte.
    @NotNullByDefault
    private static final class DeferredXorTransform implements ByteTransform {
        /// Transforms every byte except the final pending byte.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int transformed = Math.max(0, length - 1);
            for (int index = 0; index < transformed; index++) {
                buffer[offset + index] ^= 0x5a;
            }
            return transformed;
        }
    }
}
