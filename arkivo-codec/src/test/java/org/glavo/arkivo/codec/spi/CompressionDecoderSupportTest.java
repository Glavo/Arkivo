// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies algorithm-independent decompression limit handling.
@NotNullByDefault
final class CompressionDecoderSupportTest {
    /// Verifies immutable limit factories, withers, and validation.
    @Test
    void validatesDecompressionLimits() {
        DecompressionLimits limits = new DecompressionLimits(3L, 4_096L);

        assertEquals(3L, limits.maximumOutputSize());
        assertEquals(4_096L, limits.maximumWindowSize());
        assertSame(limits, limits.withMaximumOutputSize(3L));
        assertSame(limits, limits.withMaximumWindowSize(4_096L));
        assertEquals(
                new DecompressionLimits(8L, 4_096L),
                limits.withMaximumOutputSize(8L)
        );
        assertEquals(
                new DecompressionLimits(3L, 8_192L),
                limits.withMaximumWindowSize(8_192L)
        );
        assertEquals(
                new DecompressionLimits(7L, DecompressionLimits.UNLIMITED_SIZE),
                DecompressionLimits.ofMaximumOutputSize(7L)
        );
        assertEquals(
                new DecompressionLimits(DecompressionLimits.UNLIMITED_SIZE, 7L),
                DecompressionLimits.ofMaximumWindowSize(7L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecompressionLimits(-2L, DecompressionLimits.UNLIMITED_SIZE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecompressionLimits(DecompressionLimits.UNLIMITED_SIZE, -2L)
        );
    }

    /// Verifies maximum-window enforcement through both public and SPI entry points.
    @Test
    void validatesMaximumWindowSize() throws IOException {
        DecompressionLimits.ofMaximumWindowSize(4_096L).requireWindowSize(4_096L);
        DecompressionLimits.UNLIMITED.requireWindowSize(Long.MAX_VALUE);
        CompressionDecoderSupport.requireWindowSize(4_096L, 4_096L);
        CompressionDecoderSupport.requireWindowSize(
                DecompressionLimits.UNLIMITED_SIZE,
                Long.MAX_VALUE
        );

        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> CompressionDecoderSupport.requireWindowSize(4_095L, 4_096L)
        );
        assertEquals(4_095L, exception.maximumWindowSize());
        assertEquals(4_096L, exception.requiredWindowSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> CompressionDecoderSupport.requireWindowSize(0L, -1L)
        );
    }

    /// Verifies absent limits preserve engine and channel identity.
    @Test
    void preservesDelegatesWithoutLimit() {
        CompressionDecoder engine = new NoOpDecoder();
        TestChannel decoder = new TestChannel(new byte[]{1});

        assertSame(
                engine,
                CompressionDecoderSupport.limitEngineOutput(
                        engine,
                        DecompressionLimits.UNLIMITED_SIZE
                )
        );
        assertSame(
                decoder,
                CompressionDecoderSupport.limitChannelOutput(
                        decoder,
                        DecompressionLimits.UNLIMITED_SIZE
                )
        );
    }

    /// Verifies exact-limit output reaches EOF and restores a caller buffer's original limit.
    @Test
    void acceptsExactLimitAndPreservesBufferLimit() throws IOException {
        TestChannel delegate = new TestChannel(new byte[]{1, 2, 3});
        DecompressingReadableByteChannel decoder =
                CompressionDecoderSupport.limitChannelOutput(delegate, 3L);
        ByteBuffer target = ByteBuffer.allocate(8);
        target.limit(7);

        assertEquals(3, decoder.read(target));
        assertEquals(7, target.limit());
        assertEquals(3L, decoder.outputBytes());
        assertEquals(-1, decoder.read(target));
        assertEquals(3L, decoder.inputBytes());

        decoder.close();
        assertEquals(false, delegate.isOpen());
    }

    /// Verifies zero permits empty output while rejecting the first decoded byte.
    @Test
    void handlesZeroLimit() throws IOException {
        DecompressingReadableByteChannel empty =
                CompressionDecoderSupport.limitChannelOutput(new TestChannel(new byte[0]), 0L);
        assertEquals(-1, empty.read(ByteBuffer.allocate(1)));

        DecompressingReadableByteChannel nonempty =
                CompressionDecoderSupport.limitChannelOutput(new TestChannel(new byte[]{1}), 0L);
        assertEquals(0, nonempty.read(ByteBuffer.allocate(0)));
        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> nonempty.read(ByteBuffer.allocate(1))
        );
        assertEquals(0L, exception.maximumOutputSize());
        assertEquals(0L, nonempty.outputBytes());
        assertEquals(1L, nonempty.inputBytes());
    }

    /// Verifies excess output is never returned and remains a stable terminal decoder failure.
    @Test
    void rejectsExcessOutput() throws IOException {
        TestChannel delegate = new TestChannel(new byte[]{1, 2, 3, 4});
        DecompressingReadableByteChannel decoder =
                CompressionDecoderSupport.limitChannelOutput(delegate, 3L);
        ByteBuffer target = ByteBuffer.allocate(8);

        assertEquals(3, decoder.read(target));
        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> decoder.read(target)
        );
        assertEquals(3L, exception.maximumOutputSize());
        assertEquals(3, target.position());
        assertEquals(3L, decoder.outputBytes());
        assertEquals(4L, decoder.inputBytes());
        assertThrows(DecompressionLimitException.class, () -> decoder.read(target));
    }

    /// Provides an inert transport-independent decoder.
    @NotNullByDefault
    private static final class NoOpDecoder implements CompressionDecoder {
        /// Immediately reports the end of an empty stream.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) {
            return endOfInput ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_INPUT;
        }

        /// Restores no mutable state.
        @Override
        public void reset() {
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Supplies identity-decoded bytes with explicit counters and lifecycle state.
    @NotNullByDefault
    private static final class TestChannel implements DecompressingReadableByteChannel {
        /// The decoded bytes returned by this decoder.
        private final ByteBuffer content;

        /// The number of bytes returned.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Creates a decoder over fixed content.
        private TestChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content"));
        }

        /// Copies decoded bytes to the target.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(target.remaining(), content.remaining());
            ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + count);
            target.put(chunk);
            content.position(content.position() + count);
            outputBytes += count;
            return count;
        }

        /// Returns the identity input byte count.
        @Override
        public long inputBytes() {
            return outputBytes;
        }

        /// Returns the identity output byte count.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether this decoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this decoder.
        @Override
        public void close() {
            open = false;
        }
    }
}
