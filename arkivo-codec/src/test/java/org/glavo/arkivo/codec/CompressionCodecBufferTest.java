// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the default ByteBuffer compression codec adapters.
@NotNullByDefault
final class CompressionCodecBufferTest {
    /// Verifies heap-buffer copying, bounds, and position updates.
    @Test
    void adaptsChannelOperationsToHeapBuffers() throws IOException {
        CompressionCodec codec = new IdentityCodec();
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        source.position(1);
        source.limit(4);
        ByteBuffer compressed = ByteBuffer.allocate(7);
        compressed.position(2);
        compressed.limit(5);

        codec.compress(source, compressed);

        assertEquals(4, source.position());
        assertEquals(5, compressed.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(compressed, 2, 5));

        ByteBuffer encoded = compressed.duplicate();
        encoded.position(2);
        encoded.limit(5);
        ByteBuffer decoded = ByteBuffer.allocate(6);
        decoded.position(1);
        decoded.limit(4);
        codec.decompress(encoded, decoded);

        assertEquals(5, encoded.position());
        assertEquals(4, decoded.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(decoded, 1, 4));
    }

    /// Verifies allocating one-shot operations, dynamic growth, and bounded decompression.
    @Test
    void allocatesOneShotBuffersWithStrictOutputLimits() throws IOException {
        CompressionCodec codec = new IdentityCodec();
        byte[] content = new byte[(1 << 20) + 12_345];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31 + index / 257);
        }

        ByteBuffer source = ByteBuffer.allocateDirect(content.length + 4);
        source.position(2);
        source.put(content);
        source.flip();
        source.position(2);
        ByteBuffer compressed = codec.compress(source);
        assertEquals(source.limit(), source.position());
        assertEquals(0, compressed.position());
        assertEquals(content.length, compressed.remaining());
        assertTrue(compressed.hasArray());
        assertArrayEquals(content, bufferBytes(compressed, 0, compressed.limit()));

        ByteBuffer decoded = codec.decompress(compressed, content.length);
        assertEquals(compressed.limit(), compressed.position());
        assertEquals(0, decoded.position());
        assertEquals(content.length, decoded.limit());
        assertArrayEquals(content, bufferBytes(decoded, 0, decoded.limit()));

        ByteBuffer exact = codec.decompress(ByteBuffer.wrap(new byte[]{1, 2, 3}), 3L);
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(exact, 0, exact.limit()));

        ByteBuffer exceededSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> codec.decompress(exceededSource, 3L)
        );
        assertEquals(3L, exception.maximum());
        assertEquals(4, exceededSource.position());

        assertEquals(0, codec.decompress(ByteBuffer.allocate(0), 0L).remaining());
        assertThrows(
                DecompressionLimitException.class,
                () -> codec.decompress(ByteBuffer.wrap(new byte[]{1}), 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decompress(ByteBuffer.allocate(0), -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decompress(ByteBuffer.allocate(0), (long) Integer.MAX_VALUE + 1L)
        );
    }
    /// Verifies one-shot operations preserve trailing input and failed decoder construction.
    @Test
    void restoresReadAheadSourcePositions() throws IOException {
        CompressionCodec codec = new ReadAheadCodec(3, false);

        ByteBuffer fixedSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 9});
        ByteBuffer fixedTarget = ByteBuffer.allocate(3);
        codec.decompress(fixedSource, fixedTarget);
        assertEquals(3, fixedSource.position());
        assertArrayEquals(new byte[]{1, 2, 3}, bufferBytes(fixedTarget, 0, fixedTarget.position()));

        ByteBuffer allocatingSource = ByteBuffer.wrap(new byte[]{4, 5, 6, 9});
        ByteBuffer allocated = codec.decompress(allocatingSource, 3L);
        assertEquals(3, allocatingSource.position());
        assertArrayEquals(new byte[]{4, 5, 6}, bufferBytes(allocated, 0, allocated.limit()));

        CompressionCodec failing = new ReadAheadCodec(0, true);
        ByteBuffer failingSource = ByteBuffer.wrap(new byte[]{7, 8, 9});
        assertThrows(
                IOException.class,
                () -> failing.decompress(failingSource, ByteBuffer.allocate(1))
        );
        assertEquals(0, failingSource.position());
    }

    /// Verifies deterministic failures for invalid targets and insufficient capacity.
    @Test
    void reportsInvalidOrInsufficientTargets() {
        CompressionCodec codec = new IdentityCodec();

        ByteBuffer same = ByteBuffer.allocate(4);
        assertThrows(IllegalArgumentException.class, () -> codec.compress(same, same));
        assertThrows(ReadOnlyBufferException.class,
                () -> codec.compress(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.allocate(1).asReadOnlyBuffer()));

        ByteBuffer compressionSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer compressionTarget = ByteBuffer.allocate(2);
        assertThrows(BufferOverflowException.class, () -> codec.compress(compressionSource, compressionTarget));
        assertEquals(2, compressionSource.position());
        assertEquals(2, compressionTarget.position());

        ByteBuffer decompressionSource = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer decompressionTarget = ByteBuffer.allocate(2);
        assertThrows(BufferOverflowException.class, () -> codec.decompress(decompressionSource, decompressionTarget));
        assertEquals(3, decompressionSource.position());
        assertEquals(2, decompressionTarget.position());
    }

    /// Returns bytes from the given absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Provides identity encoding and decoding of one logical source prefix.
    @NotNullByDefault
    private static final class ReadAheadCodec implements CompressionCodec, CompressionFormat {
        /// The logical decoded prefix length.
        private final int decodedSize;

        /// Whether decoder construction fails.
        private final boolean failOpen;

        /// Creates a configurable logical-prefix codec.
        private ReadAheadCodec(int decodedSize, boolean failOpen) {
            this.decodedSize = decodedSize;
            this.failOpen = failOpen;
        }

        /// Returns the test compression format name.
        @Override
        public String name() {
            return "read-ahead";
        }

        /// Returns this test object as its format identity.
        @Override
        public CompressionFormat format() {
            return this;
        }

        /// Returns this test object as the default codec configuration.
        @Override
        public CompressionCodec defaultCodec() {
            return this;
        }

        /// Creates a fresh identity encoder.
        @Override
        public CompressionEncoder newEncoder() {
            return new IdentityEncoder();
        }

        /// Creates a prefix decoder or fails before consuming caller input.
        @Override
        public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
            if (failOpen) {
                throw new IOException("Read-ahead decoder construction failed");
            }
            return CompressionDecoderSupport.limitEngineOutput(
                    new PrefixDecoder(decodedSize),
                    limits.maximumOutputSize()
            );
        }
    }

    /// Implements an identity transformation through buffer engines.
    @NotNullByDefault
    private static final class IdentityCodec implements CompressionCodec, CompressionFormat {
        /// Creates an identity codec.
        private IdentityCodec() {
        }

        /// Returns the identity compression format name.
        @Override
        public String name() {
            return "identity";
        }

        /// Returns this test object as its format identity.
        @Override
        public CompressionFormat format() {
            return this;
        }

        /// Returns this test object as the default codec configuration.
        @Override
        public CompressionCodec defaultCodec() {
            return this;
        }

        /// Creates a fresh identity encoder.
        @Override
        public CompressionEncoder newEncoder() {
            return new IdentityEncoder();
        }

        /// Creates a fresh identity decoder with the requested output limit.
        @Override
        public CompressionDecoder newDecoder(DecompressionLimits limits) {
            return CompressionDecoderSupport.limitEngineOutput(
                    new IdentityDecoder(),
                    limits.maximumOutputSize()
            );
        }
    }

    /// Copies source bytes directly into compressed output.
    @NotNullByDefault
    private static final class IdentityEncoder implements CompressionEncoder {
        /// Copies as many bytes as the target can accept.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Reports terminal completion because identity coding has no trailer.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            return CodecOutcome.FINISHED;
        }

        /// Restores no mutable coding state.
        @Override
        public void reset() {
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Copies all identity bytes directly into decoded output.
    @NotNullByDefault
    private static final class IdentityDecoder implements CompressionDecoder {
        /// Copies available bytes and finishes when physical input has ended.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            return decodeInternal(source, target, false);
        }

        /// Finishes decoding after all source bytes have been supplied.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            return decodeInternal(source, target, true);
        }

        /// Implements decoding with the selected source-completion state.
        private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            if (source.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            return endOfInput ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_INPUT;
        }

        /// Restores no mutable coding state.
        @Override
        public void reset() {
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Decodes exactly one logical prefix and preserves any following bytes.
    @NotNullByDefault
    private static final class PrefixDecoder implements CompressionDecoder {
        /// Number of logical payload bytes still required.
        private final int decodedSize;

        /// Number of logical payload bytes still required in the current run.
        private int remaining;

        /// Creates a prefix decoder.
        private PrefixDecoder(int decodedSize) {
            if (decodedSize < 0) {
                throw new IllegalArgumentException("decodedSize must not be negative");
            }
            this.decodedSize = decodedSize;
            remaining = decodedSize;
        }

        /// Copies logical payload bytes without consuming the following suffix.
        @Override
        public CodecOutcome decode(
                ByteBuffer source,
                ByteBuffer target
        ) throws IOException {
            return decodeInternal(source, target, false);
        }

        /// Finishes the logical prefix after all source bytes have been supplied.
        @Override
        public CodecOutcome finish(
                ByteBuffer source,
                ByteBuffer target
        ) throws IOException {
            return decodeInternal(source, target, true);
        }

        /// Copies logical payload bytes with the selected source-completion state.
        private CodecOutcome decodeInternal(
                ByteBuffer source,
                ByteBuffer target,
                boolean endOfInput
        ) throws IOException {
            int count = Math.min(
                    remaining,
                    Math.min(source.remaining(), target.remaining())
            );
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            remaining -= count;
            if (remaining == 0) {
                return CodecOutcome.FINISHED;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (endOfInput) {
                throw new IOException("Truncated logical prefix");
            }
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Restores the logical prefix length.
        @Override
        public void reset() {
            remaining = decodedSize;
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }
}
