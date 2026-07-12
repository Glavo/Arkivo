// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.delta;

import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.TransformingOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.DeltaOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Tests Delta transforms against independent XZ filter streams.
@NotNullByDefault
public final class DeltaTransformTest {
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
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        FinishableOutputStream output = new DeltaOptions(distance).getOutputStream(
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
                new DeltaTransform(true, distance)
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
                new DeltaTransform(false, distance)
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
