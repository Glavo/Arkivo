// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies algorithm-independent decompression limit handling.
@NotNullByDefault
final class CompressionDecoderSupportTest {
    /// Verifies immutable limit factories, withers, and validation.
    @Test
    void validatesDecodingOptions() {
        DecodingOptions limits = new DecodingOptions(3L, 4_096L, 8_192L);

        assertEquals(3L, limits.maximumOutputSize());
        assertEquals(4_096L, limits.maximumWindowSize());
        assertEquals(8_192L, limits.maximumMemorySize());
        assertSame(limits, limits.withMaximumOutputSize(3L));
        assertSame(limits, limits.withMaximumWindowSize(4_096L));
        assertSame(limits, limits.withMaximumMemorySize(8_192L));
        assertEquals(
                new DecodingOptions(8L, 4_096L, 8_192L),
                limits.withMaximumOutputSize(8L)
        );
        assertEquals(
                new DecodingOptions(3L, 8_192L, 8_192L),
                limits.withMaximumWindowSize(8_192L)
        );
        assertEquals(
                new DecodingOptions(3L, 4_096L, 16_384L),
                limits.withMaximumMemorySize(16_384L)
        );
        assertEquals(
                new DecodingOptions(
                        7L,
                        DecodingOptions.UNLIMITED_SIZE,
                        DecodingOptions.UNLIMITED_SIZE
                ),
                DecodingOptions.ofMaximumOutputSize(7L)
        );
        assertEquals(
                new DecodingOptions(
                        DecodingOptions.UNLIMITED_SIZE,
                        7L,
                        DecodingOptions.UNLIMITED_SIZE
                ),
                DecodingOptions.ofMaximumWindowSize(7L)
        );
        assertEquals(
                new DecodingOptions(
                        DecodingOptions.UNLIMITED_SIZE,
                        DecodingOptions.UNLIMITED_SIZE,
                        7L
                ),
                DecodingOptions.ofMaximumMemorySize(7L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodingOptions(
                        -2L,
                        DecodingOptions.UNLIMITED_SIZE,
                        DecodingOptions.UNLIMITED_SIZE
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodingOptions(
                        DecodingOptions.UNLIMITED_SIZE,
                        -2L,
                        DecodingOptions.UNLIMITED_SIZE
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodingOptions(
                        DecodingOptions.UNLIMITED_SIZE,
                        DecodingOptions.UNLIMITED_SIZE,
                        -2L
                )
        );
    }

    /// Verifies maximum-window enforcement through both public and SPI entry points.
    @Test
    void validatesMaximumWindowSize() throws IOException {
        DecodingOptions.ofMaximumWindowSize(4_096L).requireWindowSize(4_096L);
        DecodingOptions.ofMaximumMemorySize(4_096L).requireWindowSize(4_096L);
        DecodingOptions.DEFAULT.requireWindowSize(Long.MAX_VALUE);
        CompressionDecoderSupport.requireWindowSize(4_096L, 4_096L);
        CompressionDecoderSupport.requireWindowSize(
                DecodingOptions.UNLIMITED_SIZE,
                Long.MAX_VALUE
        );

        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> CompressionDecoderSupport.requireWindowSize(4_095L, 4_096L)
        );
        assertEquals(4_095L, exception.maximumWindowSize());
        assertEquals(4_096L, exception.requiredWindowSize());
        assertEquals(
                4_095L,
                new DecodingOptions(
                        DecodingOptions.UNLIMITED_SIZE,
                        8_192L,
                        4_095L
                )
                        .effectiveMaximumWindowSize()
        );
        DecompressionWindowLimitException memoryBoundException = assertThrows(
                DecompressionWindowLimitException.class,
                () -> DecodingOptions.ofMaximumMemorySize(4_095L).requireWindowSize(4_096L)
        );
        assertEquals(4_095L, memoryBoundException.maximumWindowSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> CompressionDecoderSupport.requireWindowSize(0L, -1L)
        );
    }

    /// Verifies decoder working-memory enforcement and structured diagnostics.
    @Test
    void validatesMaximumMemorySize() throws IOException {
        DecodingOptions.ofMaximumMemorySize(4_096L).requireMemorySize(4_096L);
        DecodingOptions.DEFAULT.requireMemorySize(Long.MAX_VALUE);

        DecompressionMemoryLimitException exception = assertThrows(
                DecompressionMemoryLimitException.class,
                () -> DecodingOptions.ofMaximumMemorySize(4_095L).requireMemorySize(4_096L)
        );
        assertEquals(4_095L, exception.maximumMemorySize());
        assertEquals(4_096L, exception.requiredMemorySize());
        assertEquals(DecompressionLimitException.Kind.MEMORY_SIZE, exception.kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> DecodingOptions.DEFAULT.requireMemorySize(-1L)
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
                        DecodingOptions.UNLIMITED_SIZE
                )
        );
        assertSame(
                decoder,
                CompressionDecoderSupport.limitChannelOutput(
                        decoder,
                        DecodingOptions.UNLIMITED_SIZE
                )
        );
    }

    /// Verifies output limiters advertise interruption only when their delegates do.
    @Test
    void preservesInterruptibleChannelCapability() {
        DecompressingReadableByteChannel plain =
                CompressionDecoderSupport.limitChannelOutput(new TestChannel(new byte[]{1}), 1L);
        assertFalse(plain instanceof InterruptibleChannel);

        DecompressingReadableByteChannel interruptible =
                CompressionDecoderSupport.limitChannelOutput(new InterruptibleTestChannel(new byte[]{1}), 1L);
        assertInstanceOf(InterruptibleChannel.class, interruptible);

        DecompressingReadableByteChannel framed =
                CompressionDecoderSupport.limitChannelOutput(new FramedTestChannel(new byte[]{1}), 1L);
        assertInstanceOf(DecompressingReadableByteChannel.Framed.class, framed);
        assertFalse(framed instanceof InterruptibleChannel);

        DecompressingReadableByteChannel.Framed interruptibleFramed =
                CompressionDecoderSupport.limitChannelOutput(
                        new InterruptibleFramedTestChannel(new byte[]{1}),
                        1L
                );
        assertInstanceOf(InterruptibleChannel.class, interruptibleFramed);
    }

    /// Verifies output counters retain bytes produced before an interrupted delegate operation fails.
    @Test
    void recordsPartialOutputWhenInterruptibleDelegateFails() {
        DecompressingReadableByteChannel decoder = CompressionDecoderSupport.limitChannelOutput(
                new PartiallyFailingFramedChannel(),
                2L
        );
        ByteBuffer target = ByteBuffer.allocate(2);
        try {
            assertThrows(ClosedByInterruptException.class, () -> decoder.read(target));
            assertEquals(1, target.position());
            assertEquals(1L, decoder.outputBytes());
        } finally {
            assertTrue(Thread.interrupted());
        }

        DecompressingReadableByteChannel.Framed framedDecoder = CompressionDecoderSupport.limitChannelOutput(
                new PartiallyFailingFramedChannel(),
                2L
        );
        ByteBuffer framedTarget = ByteBuffer.allocate(2);
        try {
            assertThrows(ClosedByInterruptException.class, () -> framedDecoder.decodeFrame(framedTarget));
            assertEquals(1, framedTarget.position());
            assertEquals(1L, framedDecoder.outputBytes());
        } finally {
            assertTrue(Thread.interrupted());
        }
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
        assertEquals(0L, exception.maximum());
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
        assertEquals(3L, exception.maximum());
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
    private static class TestChannel implements DecompressingReadableByteChannel {
        /// The decoded bytes returned by this decoder.
        private final ByteBuffer content;

        /// The number of bytes returned.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Creates a decoder over fixed content.
        protected TestChannel(byte[] content) {
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

    /// Supplies fixed decoded content while advertising interruption support.
    @NotNullByDefault
    private static final class InterruptibleTestChannel
            extends TestChannel
            implements InterruptibleChannel {
        /// Creates an interruptible decoder over fixed content.
        private InterruptibleTestChannel(byte[] content) {
            super(content);
        }
    }

    /// Supplies fixed decoded content with frame-boundary control.
    @NotNullByDefault
    private static class FramedTestChannel
            extends TestChannel
            implements DecompressingReadableByteChannel.Framed {
        /// Creates a framed decoder over fixed content.
        protected FramedTestChannel(byte[] content) {
            super(content);
        }

        /// Decodes fixed content through the current synthetic frame.
        @Override
        public CodecResult decodeFrame(ByteBuffer target) throws IOException {
            return decode(target);
        }
    }

    /// Supplies fixed decoded content with frame-boundary and interruption support.
    @NotNullByDefault
    private static final class InterruptibleFramedTestChannel
            extends FramedTestChannel
            implements InterruptibleChannel {
        /// Creates an interruptible framed decoder over fixed content.
        private InterruptibleFramedTestChannel(byte[] content) {
            super(content);
        }
    }

    /// Produces one byte before terminating each operation through thread interruption.
    @NotNullByDefault
    private static final class PartiallyFailingFramedChannel
            implements DecompressingReadableByteChannel.Framed, InterruptibleChannel {
        /// The number of bytes produced before interruption.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Produces one byte, closes this decoder, and reports interruption.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            target.put((byte) 1);
            outputBytes++;
            open = false;
            Thread.currentThread().interrupt();
            throw new ClosedByInterruptException();
        }

        /// Produces one byte through the ordinary decoding path before reporting interruption.
        @Override
        public CodecResult decodeFrame(ByteBuffer target) throws IOException {
            return decode(target);
        }

        /// Returns the synthetic compressed input count.
        @Override
        public long inputBytes() {
            return outputBytes;
        }

        /// Returns the produced byte count.
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
