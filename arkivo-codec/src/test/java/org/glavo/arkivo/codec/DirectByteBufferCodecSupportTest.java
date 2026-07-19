// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies one-shot ByteBuffer operations directly drive mandatory buffer engines.
@NotNullByDefault
final class DirectByteBufferCodecSupportTest {
    /// Verifies allocating and fixed operations never open channel adapters.
    @Test
    void drivesBufferEnginesWithoutChannelAdapters() throws IOException {
        CompressionCodec.FlushableFramed<LengthPrefixedCodec> codec = new LengthPrefixedCodec();
        byte[] content = new byte[200];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }

        ByteBuffer source = ByteBuffer.allocateDirect(content.length + 4);
        source.position(2);
        source.put(content);
        source.flip();
        source.position(2);
        ByteBuffer compressed = codec.compress(source);
        assertEquals(source.limit(), source.position());
        assertEquals(content.length + 1, compressed.remaining());

        ByteBuffer decoded = codec.withMaximumOutputSize(content.length).decompress(compressed);
        assertEquals(compressed.limit(), compressed.position());
        assertArrayEquals(content, bufferBytes(decoded));

        ByteBuffer fixedSource = ByteBuffer.wrap(content);
        ByteBuffer fixedCompressed = ByteBuffer.allocate(content.length + 3);
        fixedCompressed.position(1);
        codec.compress(fixedSource, fixedCompressed);
        assertEquals(content.length, fixedSource.position());
        assertEquals(content.length + 2, fixedCompressed.position());

        ByteBuffer fixedEncoded = fixedCompressed.duplicate();
        fixedEncoded.position(1);
        fixedEncoded.limit(content.length + 2);
        ByteBuffer fixedDecoded = ByteBuffer.allocate(content.length + 4);
        fixedDecoded.position(2);
        fixedDecoded.limit(content.length + 2);
        codec.decompress(fixedEncoded, fixedDecoded);
        assertEquals(fixedEncoded.limit(), fixedEncoded.position());
        assertArrayEquals(content, bufferBytes(fixedDecoded, 2, content.length + 2));

        assertThrows(
                BufferOverflowException.class,
                () -> codec.compress(ByteBuffer.wrap(content), ByteBuffer.allocate(content.length))
        );
        assertThrows(
                BufferOverflowException.class,
                () -> codec.decompress(
                        ByteBuffer.wrap(bufferBytes(codec.compress(ByteBuffer.wrap(content)))),
                        ByteBuffer.allocate(content.length - 1)
                )
        );
        DecompressionLimitException limit = assertThrows(
                DecompressionLimitException.class,
                () -> codec.withMaximumOutputSize(content.length - 1L).decompress(
                        ByteBuffer.wrap(bufferBytes(codec.compress(ByteBuffer.wrap(content))))
                )
        );
        assertEquals(content.length - 1L, limit.maximum());
    }

    /// Verifies concatenated decoding and one-frame decoding use distinct direct-engine loops.
    @Test
    void preservesDirectFrameBoundaries() throws IOException {
        CompressionCodec.FlushableFramed<LengthPrefixedCodec> codec = new LengthPrefixedCodec();
        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{4, 5};
        byte[] firstEncoded = bufferBytes(codec.compress(ByteBuffer.wrap(first)));
        byte[] secondEncoded = bufferBytes(codec.compress(ByteBuffer.wrap(second)));

        ByteBuffer concatenated = ByteBuffer.allocate(firstEncoded.length + secondEncoded.length);
        concatenated.put(firstEncoded).put(secondEncoded).flip();
        ByteBuffer decoded = codec.withMaximumOutputSize(first.length + second.length).decompress(concatenated);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, bufferBytes(decoded));
        assertEquals(concatenated.limit(), concatenated.position());

        ByteBuffer framed = ByteBuffer.allocate(firstEncoded.length + secondEncoded.length);
        framed.put(firstEncoded).put(secondEncoded).flip();
        ByteBuffer firstDecoded = codec.withMaximumOutputSize(first.length).decompressFrame(framed);
        assertArrayEquals(first, bufferBytes(firstDecoded));
        assertEquals(firstEncoded.length, framed.position());

        ByteBuffer secondTarget = ByteBuffer.allocate(second.length);
        codec.decompressFrame(framed, secondTarget);
        secondTarget.flip();
        assertArrayEquals(second, bufferBytes(secondTarget));
        assertEquals(framed.limit(), framed.position());

        byte[] emptyEncoded = bufferBytes(codec.compress(ByteBuffer.allocate(0)));
        ByteBuffer emptyThenFirst = ByteBuffer.allocate(emptyEncoded.length + firstEncoded.length);
        emptyThenFirst.put(emptyEncoded).put(firstEncoded).flip();
        assertEquals(0, codec.withMaximumOutputSize(0L).decompressFrame(emptyThenFirst).remaining());
        assertEquals(emptyEncoded.length, emptyThenFirst.position());
    }

    /// Returns all remaining bytes without changing the supplied buffer.
    private static byte[] bufferBytes(ByteBuffer buffer) {
        return bufferBytes(buffer, buffer.position(), buffer.limit());
    }

    /// Returns bytes from one absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    /// Provides a length-prefixed identity format exclusively through buffer engines.
    @NotNullByDefault
    private static final class LengthPrefixedCodec
            implements CompressionCodec.FlushableFramed<LengthPrefixedCodec>, CompressionFormat {
        /// The maximum decoded output size.
        private final long maximumOutputSize;

        /// The maximum decoder history-window size.
        private final long maximumWindowSize;

        /// The maximum decoder working-memory size.
        private final long maximumMemorySize;

        /// Creates an unrestricted length-prefixed codec.
        private LengthPrefixedCodec() {
            this(UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a fully configured length-prefixed codec.
        private LengthPrefixedCodec(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            this.maximumOutputSize = maximumOutputSize;
            this.maximumWindowSize = maximumWindowSize;
            this.maximumMemorySize = maximumMemorySize;
        }

        /// Returns the maximum decoded output size.
        @Override
        public long maximumOutputSize() {
            return maximumOutputSize;
        }

        /// Returns the maximum decoder history-window size.
        @Override
        public long maximumWindowSize() {
            return maximumWindowSize;
        }

        /// Returns the maximum decoder working-memory size.
        @Override
        public long maximumMemorySize() {
            return maximumMemorySize;
        }

        /// Returns a codec with the requested decoded output limit.
        @Override
        public LengthPrefixedCodec withMaximumOutputSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumOutputSize");
            return value == maximumOutputSize
                    ? this
                    : new LengthPrefixedCodec(value, maximumWindowSize, maximumMemorySize);
        }

        /// Returns a codec with the requested decoder history-window limit.
        @Override
        public LengthPrefixedCodec withMaximumWindowSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumWindowSize");
            return value == maximumWindowSize
                    ? this
                    : new LengthPrefixedCodec(maximumOutputSize, value, maximumMemorySize);
        }

        /// Returns a codec with the requested decoder working-memory limit.
        @Override
        public LengthPrefixedCodec withMaximumMemorySize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumMemorySize");
            return value == maximumMemorySize
                    ? this
                    : new LengthPrefixedCodec(maximumOutputSize, maximumWindowSize, value);
        }

        /// Returns the test compression format name.
        @Override
        public String name() {
            return "length-prefixed";
        }

        /// Returns this test object as its format identity.
        @Override
        public CompressionFormat format() {
            return this;
        }

        /// Returns this test object as the default codec configuration.
        @Override
        public CompressionCodec<?> defaultCodec() {
            return this;
        }

        /// Creates a length-prefixed identity encoder.
        @Override
        public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
            return new LengthPrefixedEncoder();
        }

        /// Creates a length-prefixed identity decoder.
        @Override
        public CompressionDecoder.Framed newDecoder() {
            return CompressionDecoderSupport.limitEngineOutput(
                    new LengthPrefixedDecoder(),
                    maximumOutputSize
            );
        }

        /// Rejects accidental use of the channel encoding path.
        @Override
        public CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target,
                EncodingOptions options,
                ResourceOwnership ownership
        ) {
            throw new AssertionError("Channel encoder path must not be used");
        }

        /// Rejects accidental use of the channel decoding path.
        @Override
        public DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                ResourceOwnership ownership
        ) {
            throw new AssertionError("Channel decoder path must not be used");
        }
    }

    /// Encodes one length-prefixed identity frame.
    @NotNullByDefault
    private static final class LengthPrefixedEncoder implements CompressionEncoder.FlushableFramed {
        /// The source byte count captured from the first encode operation.
        private int sourceSize = -1;

        /// Whether the length prefix has been emitted.
        private boolean headerWritten;

        /// Whether terminal output has completed.
        private boolean finished;

        /// Starts another test frame after resetting boundary state.
        @Override
        public void startFrame(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
            if (sourceSize >= 0 || headerWritten || finished) {
                throw new IllegalStateException("A test frame is already active");
            }
        }

        /// Copies source bytes after emitting their one-byte length.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            if (finished) {
                throw new IllegalStateException("Encoder is finished");
            }
            if (sourceSize < 0) {
                sourceSize = source.remaining();
                if (sourceSize > 255) {
                    throw new IllegalArgumentException("Test frame is too large");
                }
            }
            if (!headerWritten) {
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                target.put((byte) sourceSize);
                headerWritten = true;
            }

            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Reports a completed flush because the identity payload has no pending boundary state.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            Objects.requireNonNull(target, "target");
            return CodecOutcome.FLUSHED;
        }

        /// Emits the empty-frame header when no source operation occurred and finishes the frame.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            Objects.requireNonNull(target, "target");
            if (finished) {
                return CodecOutcome.FINISHED;
            }
            if (!headerWritten) {
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                target.put((byte) 0);
                headerWritten = true;
                sourceSize = 0;
            }
            finished = true;
            return CodecOutcome.FINISHED;
        }

        /// Finishes one test frame and restores the encoder for the following frame.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            CodecOutcome outcome = finish(target);
            if (outcome == CodecOutcome.FINISHED) {
                reset();
                return CodecOutcome.BOUNDARY_REACHED;
            }
            return outcome;
        }

        /// Restores the encoder to its initial state.
        @Override
        public void reset() {
            sourceSize = -1;
            headerWritten = false;
            finished = false;
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Decodes one length-prefixed identity frame.
    @NotNullByDefault
    private static final class LengthPrefixedDecoder implements CompressionDecoder.Framed {
        /// The declared frame payload size, or a negative value before the header.
        private int expectedSize = -1;

        /// The number of payload bytes returned to the caller.
        private int decodedSize;

        /// Whether the frame has completed.
        private boolean finished;

        /// Copies exactly the payload size declared by the one-byte frame header.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
            return decodeInternal(source, target, false);
        }

        /// Finishes decoding after all source bytes have been supplied.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
            return decodeInternal(source, target, true);
        }

        /// Implements decoding with the selected source-completion state.
        private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            if (finished) {
                return CodecOutcome.FINISHED;
            }
            if (expectedSize < 0) {
                if (!source.hasRemaining()) {
                    if (endOfInput) {
                        throw new IOException("Truncated test frame header");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                expectedSize = Byte.toUnsignedInt(source.get());
            }
            if (decodedSize == expectedSize) {
                finished = true;
                return CodecOutcome.FINISHED;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (!source.hasRemaining()) {
                if (endOfInput) {
                    throw new IOException("Truncated test frame payload");
                }
                return CodecOutcome.NEEDS_INPUT;
            }

            int count = Math.min(
                    Math.min(expectedSize - decodedSize, source.remaining()),
                    target.remaining()
            );
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            decodedSize += count;
            if (decodedSize == expectedSize) {
                finished = true;
                return CodecOutcome.FINISHED;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (endOfInput) {
                throw new IOException("Truncated test frame payload");
            }
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Restores the decoder to its initial state.
        @Override
        public void reset() {
            expectedSize = -1;
            decodedSize = 0;
            finished = false;
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }
}
