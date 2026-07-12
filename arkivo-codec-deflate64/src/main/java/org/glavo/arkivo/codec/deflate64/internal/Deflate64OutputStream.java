// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Objects;

/// Adapts the channel-first Deflate64 encoder to stream-oriented format pipelines.
@NotNullByDefault
public final class Deflate64OutputStream extends OutputStream {
    /// The default Deflate64 compression level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The destination owned by this stream when it closes.
    private final OutputStream output;

    /// The channel-first encoder that produces the raw Deflate64 stream.
    private final Deflate64ChannelEncoder encoder;

    /// The reusable single-byte input buffer.
    private final byte[] singleByte = new byte[1];

    /// Whether this stream has closed its destination.
    private boolean closed;

    /// Creates a Deflate64 stream with the default compression level.
    public Deflate64OutputStream(OutputStream output) {
        this(output, DEFAULT_COMPRESSION_LEVEL);
    }

    /// Creates a Deflate64 stream with a compression level from 0 through 9.
    public Deflate64OutputStream(OutputStream output, int compressionLevel) {
        this.output = Objects.requireNonNull(output, "output");
        this.encoder = new Deflate64ChannelEncoder(
                Channels.newChannel(output),
                ChannelOwnership.RETAIN,
                compressionLevel
        );
    }

    /// Writes one uncompressed byte.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Writes uncompressed bytes to the channel-first encoder.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        encoder.write(ByteBuffer.wrap(bytes, offset, length));
    }

    /// Flushes a complete nonfinal Deflate64 block and the destination stream.
    @Override
    public void flush() throws IOException {
        encoder.flush();
        output.flush();
    }

    /// Finishes the Deflate64 frame without closing the destination stream.
    public void finish() throws IOException {
        encoder.finish();
        output.flush();
    }

    /// Finishes the Deflate64 frame and closes the destination stream.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            finish();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        closed = true;
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        rethrow(failure);
    }

    /// Rethrows a captured lifecycle failure with its original type.
    private static void rethrow(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }
}
