// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies algorithm-independent standard codec option handling.
@NotNullByDefault
final class StandardCodecOptionSupportTest {
    /// Verifies the standard compression strategy defaults and explicit values.
    @Test
    void resolvesCompressionStrategy() {
        assertEquals(
                CompressionStrategy.DEFAULT,
                StandardCodecOptionSupport.compressionStrategy(CodecOptions.EMPTY)
        );
        for (CompressionStrategy strategy : CompressionStrategy.values()) {
            assertEquals(
                    strategy,
                    StandardCodecOptionSupport.compressionStrategy(CodecOptions.builder()
                            .set(StandardCodecOptions.COMPRESSION_STRATEGY, strategy)
                            .build())
            );
        }
    }

    /// Verifies public strategies map to the corresponding JDK Deflater values.
    @Test
    void mapsDeflateStrategies() {
        assertEquals(
                Deflater.DEFAULT_STRATEGY,
                DeflateStrategySupport.toJdkValue(CompressionStrategy.DEFAULT)
        );
        assertEquals(
                Deflater.FILTERED,
                DeflateStrategySupport.toJdkValue(CompressionStrategy.FILTERED)
        );
        assertEquals(
                Deflater.HUFFMAN_ONLY,
                DeflateStrategySupport.toJdkValue(CompressionStrategy.HUFFMAN_ONLY)
        );
        assertThrows(NullPointerException.class, () -> DeflateStrategySupport.toJdkValue(null));
    }

    /// Verifies absent, explicit, and invalid pledged source sizes.
    @Test
    void resolvesPledgedSourceSize() {
        assertEquals(
                CompressionCodec.UNKNOWN_SIZE,
                StandardCodecOptionSupport.pledgedSourceSize(CodecOptions.EMPTY)
        );
        assertEquals(
                0L,
                StandardCodecOptionSupport.pledgedSourceSize(CodecOptions.builder()
                        .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, 0L)
                        .build())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StandardCodecOptionSupport.pledgedSourceSize(CodecOptions.builder()
                        .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, -1L)
                        .build())
        );
    }
    /// Verifies absent and invalid maximum output sizes are resolved before decoder creation.
    @Test
    void resolvesMaximumOutputSize() {
        assertEquals(
                CompressionCodec.UNKNOWN_SIZE,
                StandardCodecOptionSupport.maximumOutputSize(CodecOptions.EMPTY)
        );
        assertEquals(
                0L,
                StandardCodecOptionSupport.maximumOutputSize(CodecOptions.builder()
                        .set(StandardCodecOptions.MAX_OUTPUT_SIZE, 0L)
                        .build())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StandardCodecOptionSupport.maximumOutputSize(CodecOptions.builder()
                        .set(StandardCodecOptions.MAX_OUTPUT_SIZE, -1L)
                        .build())
        );
    }

    /// Verifies maximum window resolution and required-window enforcement.
    @Test
    void validatesMaximumWindowSize() throws IOException {
        assertEquals(
                CompressionCodec.UNKNOWN_SIZE,
                StandardCodecOptionSupport.maximumWindowSize(CodecOptions.EMPTY)
        );
        assertEquals(
                0L,
                StandardCodecOptionSupport.maximumWindowSize(CodecOptions.builder()
                        .set(StandardCodecOptions.MAX_WINDOW_SIZE, 0L)
                        .build())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StandardCodecOptionSupport.maximumWindowSize(CodecOptions.builder()
                        .set(StandardCodecOptions.MAX_WINDOW_SIZE, -1L)
                        .build())
        );

        StandardCodecOptionSupport.requireWindowSize(4_096L, 4_096L);
        StandardCodecOptionSupport.requireWindowSize(CompressionCodec.UNKNOWN_SIZE, Long.MAX_VALUE);
        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> StandardCodecOptionSupport.requireWindowSize(4_095L, 4_096L)
        );
        assertEquals(4_095L, exception.maximumWindowSize());
        assertEquals(4_096L, exception.requiredWindowSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> StandardCodecOptionSupport.requireWindowSize(0L, -1L)
        );
    }
    /// Verifies an absent limit preserves the original decoder identity.
    @Test
    void preservesDecoderWithoutLimit() {
        TestDecoder decoder = new TestDecoder(new byte[]{1});

        assertSame(decoder, StandardCodecOptionSupport.limitOutput(decoder, CompressionCodec.UNKNOWN_SIZE));
    }

    /// Verifies exact-limit output reaches EOF and restores a caller buffer's original limit.
    @Test
    void acceptsExactLimitAndPreservesBufferLimit() throws IOException {
        TestDecoder delegate = new TestDecoder(new byte[]{1, 2, 3});
        DecompressingReadableByteChannel decoder = StandardCodecOptionSupport.limitOutput(delegate, 3L);
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
        DecompressingReadableByteChannel empty = StandardCodecOptionSupport.limitOutput(new TestDecoder(new byte[0]), 0L);
        assertEquals(-1, empty.read(ByteBuffer.allocate(1)));

        DecompressingReadableByteChannel nonempty = StandardCodecOptionSupport.limitOutput(new TestDecoder(new byte[]{1}), 0L);
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
        TestDecoder delegate = new TestDecoder(new byte[]{1, 2, 3, 4});
        DecompressingReadableByteChannel decoder = StandardCodecOptionSupport.limitOutput(delegate, 3L);
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

    /// Supplies identity-decoded bytes with explicit counters and lifecycle state.
    @NotNullByDefault
    private static final class TestDecoder implements DecompressingReadableByteChannel {
        /// The decoded bytes returned by this decoder.
        private final ByteBuffer content;

        /// The number of bytes returned.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Creates a decoder over fixed content.
        private TestDecoder(byte[] content) {
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
