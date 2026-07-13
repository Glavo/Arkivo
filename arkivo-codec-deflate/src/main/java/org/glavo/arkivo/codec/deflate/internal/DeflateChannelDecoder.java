// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Decodes raw deflate data directly between a source channel and ByteBuffers.
@NotNullByDefault
public final class DeflateChannelDecoder implements CompressionDecoder {
    /// The compressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The JDK raw inflate context.
    private final Inflater inflater;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The number of compressed bytes logically consumed by the inflater.
    private long inputBytes;

    /// The number of compressed bytes obtained from the source.
    private long sourceBytes;

    /// The number of uncompressed bytes produced.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a raw deflate decoder.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    public DeflateChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) {
        this(source, ownership, null);
    }

    /// Creates a raw deflate decoder with an optional preset dictionary.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    /// @param dictionary preset dictionary, or null
    public DeflateChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            @Nullable CompressionDictionary dictionary
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        Inflater created = new Inflater(true);
        try {
            if (dictionary != null) {
                created.setDictionary(dictionary.bytes());
            }
        } catch (RuntimeException | Error exception) {
            created.end();
            throw exception;
        }
        this.inflater = created;
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
            int inputPosition = inputBuffer.position();
            try {
                produced = inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid raw deflate stream", exception);
            } finally {
                inputBytes += inputBuffer.position() - inputPosition;
            }
            if (produced > 0) {
                outputBytes += produced;
                return produced;
            }
            if (inflater.finished()) {
                return -1;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Raw deflate stream requires a preset dictionary");
            }
            if (!inflater.needsInput()) {
                throw new IOException("Raw deflate decoder made no progress");
            }
            if (!readCompressedInput()) {
                throw new EOFException("Unexpected end of raw deflate stream");
            }
        }
    }

    /// Returns the compressed byte count logically consumed by the inflater.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the compressed byte count obtained from the source.
    @Override
    public long sourceBytes() {
        return sourceBytes;
    }

    /// Returns a read-only view of compressed bytes not yet consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return inputBuffer.asReadOnlyBuffer();
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
        if (open) {
            open = false;
            inflater.end();
        }
        sourceCloser.close();
    }

    /// Reads another compressed chunk and supplies it to the inflater.
    private boolean readCompressedInput() throws IOException {
        inputBuffer.clear();
        int read = source.read(inputBuffer);
        if (read < 0) {
            return false;
        }
        if (read == 0) {
            throw new IOException("Raw deflate source channel made no progress");
        }

        sourceBytes += read;
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
