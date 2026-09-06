// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests channel-based byte-transform composition and lifecycle behavior.
@NotNullByDefault
public final class TransformingByteChannelsTest {
    /// XORs complete four-byte blocks and leaves an incomplete block for the next invocation.
    private static final ByteTransform BLOCK_XOR = (buffer, offset, length) -> {
        int committed = length & ~3;
        for (int index = 0; index < committed; index++) {
            buffer[offset + index] ^= 0x5a;
        }
        return committed;
    };

    /// Verifies block alignment and buffer compaction against independently computed bytes, not just a round trip.
    @ParameterizedTest
    @CsvSource({
            "0, 1", "1, 1", "3, 1", "4, 3", "5, 2", "8191, 7", "8192, 8191", "8193, 8192",
            "8194, 3", "16383, 4097", "16384, 8192", "16385, 8193"
    })
    public void blockTransformsPreserveFragmentedBufferWindows(int length, int fragmentSize) throws IOException {
        byte[] plain = new byte[length];
        for (int index = 0; index < length; index++) {
            plain[index] = (byte) (index * 37 + (index >>> 8));
        }
        byte[] expected = plain.clone();
        for (int index = 0; index < (length & ~3); index++) {
            expected[index] ^= 0x5a;
        }

        for (boolean direct : new boolean[]{false, true}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (WritableByteChannel target = Channels.newChannel(bytes);
                 TransformingWritableByteChannel encoder = new TransformingWritableByteChannel(target, BLOCK_XOR)) {
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(fragmentSize + 6)
                        : ByteBuffer.allocate(fragmentSize + 6);
                for (int offset = 0; offset < length;) {
                    int count = Math.min(fragmentSize, length - offset);
                    storage.clear().position(3).put(plain, offset, count).limit(count + 3).position(1);
                    ByteBuffer source = storage.slice().position(2).asReadOnlyBuffer().mark();
                    assertEquals(count, encoder.write(source));
                    assertEquals(count + 2, source.position());
                    assertEquals(count + 2, source.limit());
                    assertEquals(2, source.reset().position());
                    assertEquals(1, storage.position());
                    assertEquals(count + 3, storage.limit());
                    // The encoder must own its pending tail before this storage is reused.
                    storage.clear();
                    while (storage.hasRemaining()) {
                        storage.put((byte) 0);
                    }
                    offset += count;
                }
                assertEquals(length & ~3, bytes.size());
                encoder.finish();
                encoder.finish();
                assertArrayEquals(expected, bytes.toByteArray());
            }

            try (ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(plain));
                 TransformingReadableByteChannel decoder = new TransformingReadableByteChannel(source, BLOCK_XOR)) {
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                ByteBuffer target = direct ? ByteBuffer.allocateDirect(fragmentSize + 6)
                        : ByteBuffer.allocate(fragmentSize + 6);
                while (true) {
                    target.clear().put(0, (byte) 0x55).put(target.capacity() - 1, (byte) 0x66);
                    target.position(3).limit(fragmentSize + 3).mark();
                    int read = decoder.read(target);
                    assertEquals(3 + Math.max(0, read), target.position());
                    assertEquals(fragmentSize + 3, target.limit());
                    assertEquals(3, target.reset().position());
                    assertEquals(0x55, target.get(0));
                    assertEquals(0x66, target.duplicate().clear().get(target.capacity() - 1));
                    if (read < 0) {
                        break;
                    }
                    assertTrue(read > 0);
                    byte[] fragment = new byte[read];
                    target.get(fragment);
                    actual.writeBytes(fragment);
                }
                assertArrayEquals(expected, actual.toByteArray());
                assertEquals(-1, decoder.read(ByteBuffer.allocate(1)));
                assertEquals(0, decoder.read(ByteBuffer.allocate(0)));
            }
        }
    }

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
                ResourceOwnership.BORROWED
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
                ResourceOwnership.OWNED
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
