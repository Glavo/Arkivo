// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies every installed codec at shared buffer and transport fragmentation boundaries.
@NotNullByDefault
final class CodecBoundaryRoundTripTest {
    /// Representative lengths around common lanes, stripes, and internal buffer boundaries.
    private static final int @Unmodifiable [] BOUNDARY_LENGTHS = {
            0, 1, 15, 16, 17, 31, 32, 33, 255, 256, 257, 4095, 4096, 4097
    };

    /// Verifies direct and read-only one-shot buffers at critical lengths for every codec.
    @Test
    void roundTripsSharedBoundaryLengthsThroughBuffers() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            for (int length : BOUNDARY_LENGTHS) {
                byte[] content = content(length);
                ByteBuffer sourceStorage = ByteBuffer.allocateDirect(length + 6);
                sourceStorage.position(3).put(content).limit(length + 3).position(3);
                ByteBuffer source = sourceStorage.asReadOnlyBuffer();

                ByteBuffer compressed = codec.compress(source);
                assertEquals(source.limit(), source.position(), context(format, length));

                CompressionCodec<?> decoder = CodecContractConfigurations.decoderCodec(codec, length)
                        .withMaximumOutputSize(length);
                ByteBuffer compressedSource = compressed.asReadOnlyBuffer();
                ByteBuffer decoded = decoder.decompress(compressedSource);

                assertFalse(compressedSource.hasRemaining(), context(format, length));
                byte[] actual = new byte[decoded.remaining()];
                decoded.get(actual);
                assertArrayEquals(content, actual, context(format, length));
            }
        }
    }

    /// Verifies transfer loops tolerate partial reads and writes from both compressed and uncompressed endpoints.
    @Test
    void roundTripsFragmentedChannelEndpoints() throws IOException {
        byte[] content = content(8193);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            ChunkedReadableByteChannel uncompressedSource = new ChunkedReadableByteChannel(content, 3);
            ChunkedWritableByteChannel compressedTarget = new ChunkedWritableByteChannel(5);

            CodecTransferResult compression = codec.compress(uncompressedSource, compressedTarget);
            assertEquals(content.length, compression.inputBytes(), format.name());
            assertEquals(compressedTarget.size(), compression.outputBytes(), format.name());
            assertTrue(uncompressedSource.isOpen(), format.name());
            assertTrue(compressedTarget.isOpen(), format.name());

            CompressionCodec<?> decoder = CodecContractConfigurations.decoderCodec(codec, content.length)
                    .withMaximumOutputSize(content.length);
            ChunkedReadableByteChannel compressedSource = new ChunkedReadableByteChannel(
                    compressedTarget.toByteArray(),
                    2
            );
            ChunkedWritableByteChannel uncompressedTarget = new ChunkedWritableByteChannel(7);

            CodecTransferResult decompression = decoder.decompress(compressedSource, uncompressedTarget);
            assertEquals(content.length, decompression.outputBytes(), format.name());
            assertArrayEquals(content, uncompressedTarget.toByteArray(), format.name());
            assertTrue(compressedSource.isOpen(), format.name());
            assertTrue(uncompressedTarget.isOpen(), format.name());
        }
    }

    /// Returns deterministic content with both compressible runs and irregular bytes.
    private static byte @Unmodifiable [] content(int length) {
        byte[] content = new byte[length];
        Random random = new Random(0x4152_4b49_564fL + length);
        for (int index = 0; index < content.length; index++) {
            content[index] = index % 19 < 13 ? (byte) ('a' + index % 5) : (byte) random.nextInt();
        }
        return content;
    }

    /// Returns a concise assertion context for one format and input length.
    private static String context(CompressionFormat format, int length) {
        return format.name() + " length " + length;
    }

    /// Provides a source that exposes at most a configured number of bytes per read.
    @NotNullByDefault
    private static final class ChunkedReadableByteChannel implements ReadableByteChannel {
        /// The remaining private source content.
        private final @UnmodifiableView ByteBuffer content;

        /// The maximum bytes returned per read.
        private final int maximumChunkSize;

        /// Whether this source remains open.
        private boolean open = true;

        /// Creates a source over a private copy of the supplied bytes.
        private ChunkedReadableByteChannel(byte[] content, int maximumChunkSize) {
            this.content = ByteBuffer.wrap(content.clone()).asReadOnlyBuffer();
            this.maximumChunkSize = maximumChunkSize;
        }

        /// Transfers one nonempty bounded chunk or reports end of input.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }

            int count = Math.min(Math.min(target.remaining(), content.remaining()), maximumChunkSize);
            ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides a target that accepts at most a configured number of bytes per write.
    @NotNullByDefault
    private static final class ChunkedWritableByteChannel implements WritableByteChannel {
        /// The accumulated target content.
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        /// The maximum bytes accepted per write.
        private final int maximumChunkSize;

        /// Whether this target remains open.
        private boolean open = true;

        /// Creates an empty bounded-write target.
        private ChunkedWritableByteChannel(int maximumChunkSize) {
            this.maximumChunkSize = maximumChunkSize;
        }

        /// Accepts one nonempty bounded chunk from the source.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!source.hasRemaining()) {
                return 0;
            }

            int count = Math.min(source.remaining(), maximumChunkSize);
            byte[] chunk = new byte[count];
            source.get(chunk);
            content.writeBytes(chunk);
            return count;
        }

        /// Returns whether this target remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this target.
        @Override
        public void close() {
            open = false;
        }

        /// Returns a private copy of accumulated target bytes.
        private byte[] toByteArray() {
            return content.toByteArray();
        }

        /// Returns the number of accumulated target bytes.
        private int size() {
            return content.size();
        }
    }
}
