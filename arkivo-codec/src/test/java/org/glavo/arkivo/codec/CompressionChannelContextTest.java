// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.CompressingWritableByteChannel.Directive;

import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies channel context progress, ownership, transfer, and typed configuration contracts.
@NotNullByDefault
final class CompressionChannelContextTest {
    /// The identity codec used to isolate generic channel behavior.
    private static final CompressionCodec<?> CODEC = new IdentityCodec();

    /// Verifies explicit encoder directives and retained target ownership.
    @Test
    void encodesIncrementallyAndRetainsTargetByDefault() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(bytes);
        CompressingWritableByteChannel encoder = CODEC.openEncoder(target);

        CodecResult flushed = encoder.encode(ByteBuffer.wrap(new byte[]{1, 2, 3}), Directive.FLUSH);
        CodecResult finished = encoder.encode(ByteBuffer.wrap(new byte[]{4}), Directive.END_FRAME);

        assertEquals(new CodecResult(3, 3, CodecResult.Status.FLUSHED), flushed);
        assertEquals(new CodecResult(1, 1, CodecResult.Status.FRAME_FINISHED), finished);
        assertEquals(4, encoder.inputBytes());
        assertEquals(4, encoder.outputBytes());
        assertTrue(encoder.isOpen());
        encoder.finish();
        assertFalse(encoder.isOpen());
        assertTrue(target.isOpen());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes.toByteArray());
    }

    /// Verifies decoder progress and retained source ownership.
    @Test
    void decodesIncrementallyAndRetainsSourceByDefault() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{5, 6, 7}));
        ByteBuffer output = ByteBuffer.allocate(3);
        DecompressingReadableByteChannel decoder = CODEC.openDecoder(source);

        CodecResult decoded = decoder.decode(output);
        CodecResult ended = decoder.decode(ByteBuffer.allocate(1));
        decoder.close();

        assertEquals(new CodecResult(3, 3, CodecResult.Status.ACTIVE), decoded);
        assertEquals(new CodecResult(0, 0, CodecResult.Status.END_OF_INPUT), ended);
        assertTrue(source.isOpen());
        assertArrayEquals(new byte[]{5, 6, 7}, output.array());
    }

    /// Verifies explicit channel ownership closes backing channels.
    @Test
    void closesOwnedBackingChannels() throws IOException {
        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());
        try (CompressingWritableByteChannel encoder = CODEC.openEncoder(
                target,
                ChannelOwnership.CLOSE
        )) {
            encoder.encode(ByteBuffer.wrap(new byte[]{1}), Directive.END_FRAME);
        }
        assertFalse(target.isOpen());

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        try (DecompressingReadableByteChannel decoder = CODEC.openDecoder(
                source,
                ChannelOwnership.CLOSE
        )) {
            decoder.decode(ByteBuffer.allocate(1));
        }
        assertFalse(source.isOpen());
    }

    /// Verifies stream adapters retry owned endpoint closure without repeating codec closure.
    @Test
    void retriesOwnedStreamAdapterEndpoints() throws IOException {
        FailingCloseWritableChannel target = new FailingCloseWritableChannel();
        CompressingWritableByteChannel encoder = CODEC.openEncoder(
                target,
                ChannelOwnership.CLOSE
        );
        encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, encoder::finish);
        assertFalse(encoder.isOpen());
        assertEquals(1, target.closeCount());
        encoder.close();
        encoder.close();
        assertEquals(2, target.closeCount());
        assertArrayEquals(new byte[]{1, 2, 3}, target.bytes());

        FailingCloseReadableChannel source = new FailingCloseReadableChannel(new byte[]{4, 5, 6});
        DecompressingReadableByteChannel decoder = CODEC.openDecoder(
                source,
                ChannelOwnership.CLOSE
        );
        assertThrows(IOException.class, decoder::close);
        assertFalse(decoder.isOpen());
        assertEquals(1, source.closeCount());
        decoder.close();
        decoder.close();
        assertEquals(2, source.closeCount());
    }

    /// Verifies blocking channel transfers report counts and retain both endpoints.
    @Test
    void transfersBetweenChannelsWithoutTakingOwnership() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{8, 9, 10}));
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(compressedBytes);

        CodecTransferResult compressed = CODEC.compress(source, target);

        assertEquals(new CodecTransferResult(3, 3), compressed);
        assertTrue(source.isOpen());
        assertTrue(target.isOpen());

        ReadableByteChannel encoded = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        );
        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        WritableByteChannel decoded = Channels.newChannel(decodedBytes);
        CodecTransferResult decompressed = CODEC.decompress(encoded, decoded);

        assertEquals(new CodecTransferResult(3, 3), decompressed);
        assertTrue(encoded.isOpen());
        assertTrue(decoded.isOpen());
        assertArrayEquals(new byte[]{8, 9, 10}, decodedBytes.toByteArray());
    }

    /// Verifies unsupported configuration is absent from the codec type instead of rejected at runtime.
    @Test
    void exposesConfigurationOnlyThroughTypedSubinterfaces() {
        assertFalse(CODEC instanceof CompressionCodec.LevelConfigurable<?>);
        assertFalse(CODEC instanceof CompressionCodec.StrategyConfigurable<?>);
        assertFalse(CODEC instanceof CompressionCodec.DictionaryConfigurable<?>);
        assertFalse(CODEC instanceof CompressionCodec.PledgedSourceSizeEncoderFactory<?>);
    }

    /// Implements a writable byte-array channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Collected bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Writable delegate.
        private final WritableByteChannel delegate = Channels.newChannel(bytes);

        /// Number of close attempts.
        private int closeCount;

        /// Writes bytes to the delegate.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and then closes the delegate.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }

        /// Returns collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /// Implements a readable byte-array channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Readable delegate.
        private final ReadableByteChannel delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel over bytes.
        private FailingCloseReadableChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads bytes from the delegate.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and then closes the delegate.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements transport-independent identity coding for generic channel tests.
    @NotNullByDefault
    private static final class IdentityCodec implements CompressionCodec<IdentityCodec>, CompressionFormat {
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
        public CompressionCodec<?> defaultCodec() {
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

    /// Copies source bytes directly while exposing flush and frame boundaries.
    @NotNullByDefault
    private static final class IdentityEncoder implements CompressionEncoder.FlushableFramed {
        /// Copies as many source bytes as the target can accept.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Reports a decodable boundary because identity coding has no pending output.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            return CodecOutcome.FLUSHED;
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

    /// Copies compressed identity bytes directly to the decoded target.
    @NotNullByDefault
    private static final class IdentityDecoder implements CompressionDecoder {
        /// Copies available source bytes and finishes at physical input end.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) {
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
}
