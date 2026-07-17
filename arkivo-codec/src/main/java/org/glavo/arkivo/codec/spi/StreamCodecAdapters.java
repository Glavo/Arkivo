// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts stream-based codec implementations to Arkivo's channel context SPI.
@NotNullByDefault
public final class StreamCodecAdapters {
    /// Creates no instances.
    private StreamCodecAdapters() {
    }

    /// Creates an encoding channel around a stream-based codec implementation.
    public static CompressingWritableByteChannel newWritableByteChannel(
            WritableByteChannel target,
            ResourceOwnership ownership,
            OutputStreamFactory factory
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");

        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        OutputStream channelOutput = StreamChannelAdapters.outputStream(target);
        CountingOutputStream countingOutput = new CountingOutputStream(
                new RetainedOutputStream(channelOutput)
        );
        try {
            OutputStream codecOutput = factory.open(countingOutput);
            return new StreamCompressingWritableByteChannel(
                    codecOutput,
                    countingOutput,
                    targetCloser
            );
        } catch (IOException | RuntimeException | Error failure) {
            targetCloser.closeAfter(failure);
            throw failure;
        }
    }

    /// Creates a decoding channel around a stream-based codec implementation.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ResourceOwnership ownership,
            InputStreamFactory factory
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(factory, "factory");

        OwnedChannelCloser sourceCloser = new OwnedChannelCloser(source, ownership);
        InputStream channelInput = StreamChannelAdapters.inputStream(source);
        CountingInputStream countingInput = new CountingInputStream(
                new RetainedInputStream(channelInput)
        );
        try {
            InputStream codecInput = factory.open(countingInput);
            return new StreamDecompressingReadableByteChannel(
                    codecInput,
                    countingInput,
                    sourceCloser
            );
        } catch (IOException | RuntimeException | Error failure) {
            sourceCloser.closeAfter(failure);
            throw failure;
        }
    }

    /// Creates a stream encoder over a channel-backed output stream.
    @FunctionalInterface
    @NotNullByDefault
    public interface OutputStreamFactory {
        /// Opens the codec output stream.
        OutputStream open(OutputStream target) throws IOException;
    }

    /// Creates a stream decoder over a channel-backed input stream.
    @FunctionalInterface
    @NotNullByDefault
    public interface InputStreamFactory {
        /// Opens the codec input stream.
        InputStream open(InputStream source) throws IOException;
    }

    /// Implements an encoder context over a codec output stream.
    @NotNullByDefault
    private static final class StreamCompressingWritableByteChannel implements CompressingWritableByteChannel {
        /// The codec output stream.
        private final OutputStream output;

        /// The compressed-output counter.
        private final CountingOutputStream counter;

        /// Tracks closure of the owned compressed-data target.
        private final OwnedChannelCloser targetCloser;

        /// The number of accepted uncompressed bytes.
        private long inputBytes;

        /// Whether the encoder remains open.
        private boolean open = true;

        /// Creates an encoder context.
        private StreamCompressingWritableByteChannel(
                OutputStream output,
                CountingOutputStream counter,
                OwnedChannelCloser targetCloser
        ) {
            this.output = Objects.requireNonNull(output, "output");
            this.counter = Objects.requireNonNull(counter, "counter");
            this.targetCloser = Objects.requireNonNull(targetCloser, "targetCloser");
        }

        /// Writes uncompressed bytes into the codec stream.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            if (!source.hasRemaining()) {
                return 0;
            }

            int written = source.remaining();
            if (source.hasArray()) {
                output.write(
                        source.array(),
                        source.arrayOffset() + source.position(),
                        written
                );
                source.position(source.limit());
            } else {
                byte[] buffer = new byte[Math.min(written, 8192)];
                while (source.hasRemaining()) {
                    int chunkSize = Math.min(source.remaining(), buffer.length);
                    ByteBuffer chunk = source.duplicate();
                    chunk.limit(chunk.position() + chunkSize);
                    chunk.get(buffer, 0, chunkSize);
                    output.write(buffer, 0, chunkSize);
                    source.position(source.position() + chunkSize);
                }
            }
            inputBytes += written;
            return written;
        }

        /// Flushes codec and transport buffers.
        public void flush() throws IOException {
            ensureOpen();
            output.flush();
        }

        /// Finishes the encoded frame and releases codec resources.
        @Override
        public void finish() throws IOException {
            @Nullable Throwable failure = null;
            if (open) {
                open = false;
                try {
                    output.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            targetCloser.closeAfter(failure);
        }

        /// Returns the accepted uncompressed byte count.
        @Override
        public long inputBytes() {
            return inputBytes;
        }

        /// Returns the emitted compressed byte count.
        @Override
        public long outputBytes() {
            return counter.count();
        }

        /// Returns whether the encoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Finishes the encoder.
        @Override
        public void close() throws IOException {
            finish();
        }

        /// Requires the encoder to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Implements a decoder context over a codec input stream.
    @NotNullByDefault
    private static final class StreamDecompressingReadableByteChannel implements DecompressingReadableByteChannel {
        /// The readable channel view of the codec input stream.
        private final ReadableByteChannel channel;

        /// The compressed-input counter.
        private final CountingInputStream counter;

        /// Tracks closure of the owned compressed-data source.
        private final OwnedChannelCloser sourceCloser;

        /// The number of decoded bytes returned to callers.
        private long outputBytes;

        /// Whether this decoder remains open.
        private boolean open = true;

        /// Creates a decoder context.
        private StreamDecompressingReadableByteChannel(
                InputStream input,
                CountingInputStream counter,
                OwnedChannelCloser sourceCloser
        ) {
            this.channel = StreamChannelAdapters.readableChannel(Objects.requireNonNull(input, "input"));
            this.counter = Objects.requireNonNull(counter, "counter");
            this.sourceCloser = Objects.requireNonNull(sourceCloser, "sourceCloser");
        }

        /// Reads decoded bytes from the codec stream.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            int read = channel.read(target);
            if (read > 0) {
                outputBytes += read;
            }
            return read;
        }

        /// Returns the consumed compressed byte count.
        @Override
        public long inputBytes() {
            return counter.count();
        }

        /// Returns the returned uncompressed byte count.
        @Override
        public long outputBytes() {
            return outputBytes;
        }

        /// Returns whether the decoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the decoder and releases codec resources.
        @Override
        public void close() throws IOException {
            @Nullable Throwable failure = null;
            if (open) {
                open = false;
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            sourceCloser.closeAfter(failure);
        }
    }

    /// Counts bytes read from a channel-backed input stream.
    @NotNullByDefault
    private static final class CountingInputStream extends FilterInputStream {
        /// The number of compressed bytes read.
        private long count;

        /// Creates a counting input stream.
        private CountingInputStream(InputStream input) {
            super(input);
        }

        /// Reads and counts one byte.
        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        /// Reads and counts a byte range.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        /// Returns the number of bytes read.
        private long count() {
            return count;
        }
    }

    /// Counts bytes written to a channel-backed output stream.
    @NotNullByDefault
    private static final class CountingOutputStream extends FilterOutputStream {
        /// The number of compressed bytes written.
        private long count;

        /// Creates a counting output stream.
        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        /// Writes and counts one byte.
        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        /// Writes and counts a byte range.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }

        /// Returns the number of bytes written.
        private long count() {
            return count;
        }
    }

    /// Prevents a codec output stream from closing a retained backing channel.
    @NotNullByDefault
    private static final class RetainedOutputStream extends FilterOutputStream {
        /// Creates a retained output stream.
        private RetainedOutputStream(OutputStream output) {
            super(output);
        }

        /// Flushes output without closing the backing stream.
        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /// Prevents a codec input stream from closing a retained backing channel.
    @NotNullByDefault
    private static final class RetainedInputStream extends FilterInputStream {
        /// Creates a retained input stream.
        private RetainedInputStream(InputStream input) {
            super(input);
        }

        /// Leaves the backing input stream open.
        @Override
        public void close() {
        }
    }
}
