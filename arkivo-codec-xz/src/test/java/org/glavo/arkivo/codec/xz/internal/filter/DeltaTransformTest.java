// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal.filter;

import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.TransformingOutputStream;
import org.glavo.arkivo.codec.transform.TransformingReadableByteChannel;
import org.glavo.arkivo.codec.transform.TransformingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.DeltaOptions;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.X86Options;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Delta transforms against independent XZ filter streams.
@NotNullByDefault
public final class DeltaTransformTest {
    /// Verifies mixed stream/channel pipelines preserve filter order and flush incomplete BCJ tails correctly.
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 256})
    public void mixedBcjDeltaPipelineMatchesXZ(int distance) throws IOException {
        byte[] original = new byte[24593];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 37 + (i >>> 8));
        }
        for (int offset = 0; offset + 5 <= original.length; offset += 31) {
            original[offset] = (byte) 0xe8;
            ByteArrayAccess.writeIntLittleEndian(original, offset + 1, offset * 17);
        }
        byte[] expected = encodeWithXZ(encodeWithXZ(original, new X86Options()), new DeltaOptions(distance));
        for (int chunk : new int[]{1, 17, 8191, 8193}) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            TransformingWritableByteChannel delta = new TransformingWritableByteChannel(
                    Channels.newChannel(encoded), new DeltaTransform(ByteTransform.Direction.ENCODE, distance));
            try (TransformingOutputStream bcj = new TransformingOutputStream(
                    Channels.newOutputStream(delta), BCJTransforms.x86(ByteTransform.Direction.ENCODE, 0))) {
                for (int offset = 0; offset < original.length; offset += chunk) {
                    bcj.write(original, offset, Math.min(chunk, original.length - offset));
                    bcj.flush();
                }
                bcj.finish();
                bcj.finish();
            }
            assertArrayEquals(expected, encoded.toByteArray());

            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            TransformingInputStream deltaInput = new TransformingInputStream(
                    new ChunkedInputStream(expected, chunk), new DeltaTransform(ByteTransform.Direction.DECODE, distance));
            try (TransformingReadableByteChannel bcj = new TransformingReadableByteChannel(
                    Channels.newChannel(deltaInput), BCJTransforms.x86(ByteTransform.Direction.DECODE, 0), ResourceOwnership.OWNED)) {
                ByteBuffer target = ByteBuffer.allocateDirect(8197);
                byte[] bytes = new byte[8193];
                while (true) {
                    target.clear().position(2).mark().limit(8195);
                    int count = bcj.read(target);
                    assertEquals(8195, target.limit());
                    assertEquals(count < 0 ? 2 : 2 + count, target.position());
                    target.reset();
                    if (count < 0) {
                        break;
                    }
                    assertTrue(count > 0);
                    target.get(bytes, 0, count);
                    decoded.write(bytes, 0, count);
                }
                assertEquals(-1, bcj.read(ByteBuffer.allocate(1)));
            }
            assertArrayEquals(original, decoded.toByteArray());
        }
    }

    /// Verifies encoding and decoding at the minimum, ordinary, and maximum distances.
    @Test
    public void interoperabilityAcrossChunks() throws IOException {
        byte[] original = new byte[20_003];
        for (int index = 0; index < original.length; index++) {
            original[index] = (byte) (index * 37 + index / 11);
        }

        for (int distance : new int[]{1, 3, 256}) {
            byte[] expected = encodeWithXZ(original, distance);
            byte[] encoded = encodeNatively(original, distance);
            assertArrayEquals(expected, encoded);
            assertArrayEquals(original, decodeNatively(encoded, distance));
        }
    }

    /// Encodes bytes through the independent XZ Delta implementation.
    private static byte[] encodeWithXZ(byte[] original, int distance) throws IOException {
        return encodeWithXZ(original, new DeltaOptions(distance));
    }

    /// Encodes one complete transform stage using XZ for Java as an independent reference.
    private static byte[] encodeWithXZ(byte[] original, FilterOptions options) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        FinishableOutputStream output = options.getOutputStream(
                new FinishableWrapperOutputStream(target),
                ArrayCache.getDummyCache()
        );
        output.write(original);
        output.finish();
        return target.toByteArray();
    }

    /// Encodes bytes through the native transform using deliberately fragmented writes.
    private static byte[] encodeNatively(byte[] original, int distance) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (TransformingOutputStream output = new TransformingOutputStream(
                target,
                new DeltaTransform(ByteTransform.Direction.ENCODE, distance)
        )) {
            for (byte value : original) {
                output.write(value);
            }
        }
        return target.toByteArray();
    }

    /// Decodes bytes through a source that exposes at most three bytes per read.
    private static byte[] decodeNatively(byte[] encoded, int distance) throws IOException {
        try (TransformingInputStream input = new TransformingInputStream(
                new ChunkedInputStream(encoded, 3),
                new DeltaTransform(ByteTransform.Direction.DECODE, distance)
        )) {
            return input.readAllBytes();
        }
    }

    /// Limits each bulk source read to a fixed number of bytes.
    @NotNullByDefault
    private static final class ChunkedInputStream extends InputStream {
        /// The complete encoded source bytes.
        private final byte[] bytes;

        /// The maximum number of bytes returned by one bulk read.
        private final int maximumChunk;

        /// The next source byte position.
        private int position;

        /// Creates a fragmented source.
        private ChunkedInputStream(byte[] bytes, int maximumChunk) {
            this.bytes = bytes.clone();
            this.maximumChunk = maximumChunk;
        }

        /// Reads one source byte.
        @Override
        public int read() {
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads at most the configured chunk size.
        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, maximumChunk), bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
