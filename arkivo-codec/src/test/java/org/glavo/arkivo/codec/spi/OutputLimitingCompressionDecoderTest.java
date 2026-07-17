// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies output limiting around buffer decoders with internally staged output.
@NotNullByDefault
final class OutputLimitingCompressionDecoderTest {
    /// Verifies an exact limit can finish through an internal one-byte probe after delivering its prefix.
    @Test
    void acceptsExactLimitWhenDelegateRequestsFinalOutputCall() throws Exception {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[]{1, 2, 3}),
                3L
        );
        ByteBuffer target = ByteBuffer.allocate(8);
        target.limit(7);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(ByteBuffer.allocate(0), target));
        assertEquals(3, target.position());
        assertEquals(7, target.limit());
        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.allocate(0), target));
    }

    /// Verifies the hidden probe rejects the first byte beyond the configured limit after delivering its prefix.
    @Test
    void rejectsStagedExcessOutputAfterPrefix() throws Exception {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[]{1, 2, 3, 4}),
                3L
        );
        ByteBuffer target = ByteBuffer.allocate(8);

        assertEquals(
                CodecOutcome.NEEDS_OUTPUT,
                decoder.finish(ByteBuffer.allocate(0), target)
        );
        assertEquals(3, target.position());
        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), target)
        );
        assertEquals(3L, exception.maximum());
        assertThrows(
                DecompressionLimitException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), target)
        );
    }

    /// Supplies fixed output while deliberately requiring an empty final decode call.
    @NotNullByDefault
    private static final class StagedDecoder implements CompressionDecoder {
        /// The internally staged decoded bytes.
        private final ByteBuffer content;

        /// Whether the decoder has been closed.
        private boolean closed;

        /// Creates a decoder over fixed staged bytes.
        private StagedDecoder(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content"));
        }

        /// Copies staged bytes and requests output whenever the supplied target becomes full.
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
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            if (closed) {
                throw new IllegalStateException("Staged decoder is closed");
            }
            if (!content.hasRemaining()) {
                return CodecOutcome.FINISHED;
            }
            int copied = Math.min(content.remaining(), target.remaining());
            ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + copied);
            target.put(chunk);
            content.position(content.position() + copied);
            return target.hasRemaining() ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_OUTPUT;
        }

        /// Restores all staged bytes.
        @Override
        public void reset() {
            if (closed) {
                throw new IllegalStateException("Staged decoder is closed");
            }
            content.position(0);
        }

        /// Closes this decoder.
        @Override
        public void close() {
            closed = true;
        }
    }
}
