// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies retryable owned-endpoint closure across every official compression context.
@NotNullByDefault
final class CodecCloseRetryContractTest {
    /// The input used to exercise nonempty stream finalization.
    private static final byte[] CONTENT = (
            "retryable codec endpoint closure 0123456789abcdef;"
    ).repeat(64).getBytes(StandardCharsets.UTF_8);

    /// Verifies encoder closure retries only the endpoint and does not duplicate stream finalization.
    @Test
    void retriesEncoderTargetClosure() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {

            FailingCloseWritableChannel target = new FailingCloseWritableChannel();
            CompressingWritableByteChannel encoder = codec.openEncoder(
                    target,
                    ChannelOwnership.CLOSE
            );
            encoder.write(ByteBuffer.wrap(CONTENT));

            IOException failure = assertThrows(IOException.class, encoder::finish, codec.name());
            assertEquals("close failed", failure.getMessage(), codec.name());
            assertFalse(encoder.isOpen(), codec.name());
            assertEquals(1, target.closeCount(), codec.name());
            int finalizedSize = target.size();

            encoder.close();
            encoder.close();
            assertEquals(2, target.closeCount(), codec.name());
            assertEquals(finalizedSize, target.size(), codec.name());
            assertArrayEquals(CONTENT, decompress(codec, target.bytes()), codec.name());
        }
    }

    /// Verifies decoder closure releases codec state once and retries the owned source.
    @Test
    void retriesDecoderSourceClosure() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {

            FailingCloseReadableChannel source = new FailingCloseReadableChannel(compress(codec, CONTENT));
            CompressionCodec decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, CONTENT.length);
            DecompressingReadableByteChannel decoder = decoderCodec.openDecoder(
                    source,
                    ChannelOwnership.CLOSE
            );

            IOException failure = assertThrows(IOException.class, decoder::close, codec.name());
            assertEquals("close failed", failure.getMessage(), codec.name());
            assertFalse(decoder.isOpen(), codec.name());
            assertEquals(1, source.closeCount(), codec.name());

            decoder.close();
            decoder.close();
            assertEquals(2, source.closeCount(), codec.name());
            assertFalse(source.isOpen(), codec.name());
        }
    }

    /// Verifies context setup and first-finalization failures honor endpoint ownership.
    @Test
    void closesOwnedEndpointsAfterSetupFailures() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            {
                WriteFailingWritableChannel target = new WriteFailingWritableChannel();
                @Nullable CompressingWritableByteChannel encoder = null;
                try {
                    encoder = codec.openEncoder(target, ChannelOwnership.CLOSE);
                    assertThrows(IOException.class, encoder::finish, codec.name());
                } catch (IOException exception) {
                    assertEquals("write failed", exception.getMessage(), codec.name());
                } finally {
                    if (encoder != null) {
                        encoder.close();
                    }
                }
                assertTrue(target.writeCount() > 0, codec.name());
                assertFalse(target.isOpen(), codec.name());
                assertEquals(1, target.closeCount(), codec.name());
            }

            {
                ReadFailingReadableChannel source = new ReadFailingReadableChannel();
                @Nullable DecompressingReadableByteChannel decoder = null;
                try {
                    decoder = decoderCodec(codec, 0L).openDecoder(source, ChannelOwnership.CLOSE);
                } catch (IOException exception) {
                    assertEquals("read failed", exception.getMessage(), codec.name());
                } finally {
                    if (decoder != null) {
                        decoder.close();
                    }
                }
                assertFalse(source.isOpen(), codec.name());
                assertEquals(1, source.closeCount(), codec.name());
            }
        }
    }

    /// Verifies setup and finalization failures retain caller-owned endpoints.
    @Test
    void retainsEndpointsAfterSetupFailures() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            {
                WriteFailingWritableChannel target = new WriteFailingWritableChannel();
                @Nullable CompressingWritableByteChannel encoder = null;
                try {
                    encoder = codec.openEncoder(target, ChannelOwnership.RETAIN);
                    assertThrows(IOException.class, encoder::finish, codec.name());
                } catch (IOException exception) {
                    assertEquals("write failed", exception.getMessage(), codec.name());
                } finally {
                    if (encoder != null) {
                        encoder.close();
                    }
                }
                assertTrue(target.writeCount() > 0, codec.name());
                assertTrue(target.isOpen(), codec.name());
                assertEquals(0, target.closeCount(), codec.name());
            }

            {
                ReadFailingReadableChannel source = new ReadFailingReadableChannel();
                @Nullable DecompressingReadableByteChannel decoder = null;
                try {
                    decoder = decoderCodec(codec, 0L).openDecoder(source, ChannelOwnership.RETAIN);
                } catch (IOException exception) {
                    assertEquals("read failed", exception.getMessage(), codec.name());
                } finally {
                    if (decoder != null) {
                        decoder.close();
                    }
                }
                assertTrue(source.isOpen(), codec.name());
                assertEquals(0, source.closeCount(), codec.name());
            }
        }
    }

    /// Returns a decoder configuration carrying required external stream metadata.
    private static CompressionCodec decoderCodec(CompressionCodec codec, long decodedSize) {
        return CodecContractConfigurations.decoderCodec(codec, decodedSize);
    }

    /// Verifies OutputStream convenience adapters retry caller-stream closure without re-finalizing frames.
    @Test
    void retriesConvenienceOutputStreamClosure() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {

            FailingCloseOutputStream target = new FailingCloseOutputStream();
            OutputStream output = codec.compressTo(target);
            output.write(CONTENT);
            IOException failure = assertThrows(IOException.class, output::close, codec.name());
            assertEquals("close failed", failure.getMessage(), codec.name());
            int finalizedSize = target.size();

            output.close();
            output.close();
            assertEquals(2, target.closeCount(), codec.name());
            assertEquals(finalizedSize, target.size(), codec.name());
            assertArrayEquals(CONTENT, decompress(codec, target.bytes()), codec.name());
        }
    }

    /// Verifies InputStream convenience adapters retry caller-stream closure after decoder release.
    @Test
    void retriesConvenienceInputStreamClosure() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {

            FailingCloseInputStream source = new FailingCloseInputStream(compress(codec, CONTENT));
            InputStream input = decoderCodec(codec, CONTENT.length).decompressFrom(source);
            assertArrayEquals(CONTENT, input.readAllBytes(), codec.name());
            IOException failure = assertThrows(IOException.class, input::close, codec.name());
            assertEquals("close failed", failure.getMessage(), codec.name());

            input.close();
            input.close();
            assertEquals(2, source.closeCount(), codec.name());
        }
    }

    /// Compresses bytes without transferring endpoint ownership.
    private static byte[] compress(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Decompresses bytes without transferring endpoint ownership.
    private static byte[] decompress(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        decoderCodec(codec, CONTENT.length).decompress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Rejects every compressed write and tracks closure.
    @NotNullByDefault
    private static final class WriteFailingWritableChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Number of write attempts.
        private int writeCount;

        /// Number of close attempts.
        private int closeCount;

        /// Rejects every write.
        @Override
        public int write(ByteBuffer source) throws IOException {
            writeCount++;
            throw new IOException("write failed");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            closeCount++;
            open = false;
        }

        /// Returns the number of write attempts.
        private int writeCount() {
            return writeCount;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Rejects every compressed read and tracks closure.
    @NotNullByDefault
    private static final class ReadFailingReadableChannel implements ReadableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Number of close attempts.
        private int closeCount;

        /// Rejects every read.
        @Override
        public int read(ByteBuffer target) throws IOException {
            throw new IOException("read failed");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            closeCount++;
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Collects compressed bytes and fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends ByteArrayOutputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns the collected compressed bytes.
        private byte[] bytes() {
            return toByteArray();
        }
    }

    /// Reads compressed bytes and fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends ByteArrayInputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Creates a source over compressed bytes.
        private FailingCloseInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements a writable channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Collected compressed bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Writable delegate over the collected bytes.
        private final WritableByteChannel delegate = Channels.newChannel(bytes);

        /// Number of close attempts.
        private int closeCount;

        /// Writes compressed bytes to the delegate.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and closes the delegate on the next attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns the number of collected bytes.
        private int size() {
            return bytes.size();
        }

        /// Returns the collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /// Implements a readable channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Readable compressed-data delegate.
        private final ReadableByteChannel delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel over compressed bytes.
        private FailingCloseReadableChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads compressed bytes from the delegate.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and closes the delegate on the next attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
