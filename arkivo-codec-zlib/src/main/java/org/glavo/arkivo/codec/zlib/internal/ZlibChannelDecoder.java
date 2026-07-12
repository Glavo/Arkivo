// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zlib.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Decodes zlib data directly between a source channel and ByteBuffers.
@NotNullByDefault
public final class ZlibChannelDecoder implements CompressionDecoder {
    /// The compressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Whether this context owns the source channel.
    private final ChannelOwnership ownership;

    /// The JDK zlib-wrapped inflate context.
    private final Inflater inflater = new Inflater(false);

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The number of compressed bytes read from the source.
    private long inputBytes;

    /// The number of uncompressed bytes produced.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a zlib decoder.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    public ZlibChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) {
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        inputBuffer.limit(0);
    }

    /// Reads uncompressed bytes into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }

        while (true) {
            if (inflater.finished()) {
                return -1;
            }
            int produced;
            try {
                produced = inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid zlib stream", exception);
            }
            if (produced > 0) {
                outputBytes += produced;
                return produced;
            }
            if (inflater.finished()) {
                return -1;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Zlib stream requires a preset dictionary");
            }
            if (!inflater.needsInput()) {
                throw new IOException("Zlib decoder made no progress");
            }
            if (!readCompressedInput()) {
                throw new EOFException("Unexpected end of zlib stream");
            }
        }
    }

    /// Returns the compressed byte count read from the source.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the produced uncompressed byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether the decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Releases the native context and closes an owned source channel.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }

        open = false;
        inflater.end();
        if (ownership == ChannelOwnership.CLOSE) {
            source.close();
        }
    }

    /// Reads another compressed chunk and supplies it to the inflater.
    private boolean readCompressedInput() throws IOException {
        inputBuffer.clear();
        int read = source.read(inputBuffer);
        if (read < 0) {
            return false;
        }
        if (read == 0) {
            throw new IOException("Zlib source channel made no progress");
        }

        inputBytes += read;
        inputBuffer.flip();
        inflater.setInput(inputBuffer);
        return true;
    }

    /// Requires the decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
