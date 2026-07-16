// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes a headerless LZMA range-coded stream with externally supplied properties.
@NotNullByDefault
public final class LZMARawChannelDecoder implements DecompressingReadableByteChannel {
    /// Tracks closure of the compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The byte-precise compressed source used to preserve following container data.
    private final LZMAChannelInput input;

    /// The raw LZMA decoder engine.
    private final LZMADecoderEngine decoder;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a raw LZMA decoder with explicit model and output-size parameters.
    public LZMARawChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            LZMAProperties properties,
            long expectedSize,
            long maximumWindowSize
    ) throws IOException {
        if (expectedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA decoded size must be nonnegative or -1");
        }
        Objects.requireNonNull(source, "source");
        sourceCloser = new OwnedChannelCloser(source, ownership);
        input = new LZMAChannelInput(source, 1);
        try {
            LZMAProperties validatedProperties = Objects.requireNonNull(properties, "properties");
            CompressionDecoderSupport.requireWindowSize(
                    maximumWindowSize,
                    validatedProperties.dictionarySize()
            );
            decoder = new LZMADecoderEngine(validatedProperties.dictionarySize());
            decoder.configure(validatedProperties.propertyByte());
            decoder.resetDictionary();
            decoder.startChunk(input, expectedSize, expectedSize < 0L);
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Reads uncompressed bytes into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        int start = target.position();
        while (target.hasRemaining()) {
            int value = decoder.readByte();
            if (value < 0) {
                break;
            }
            target.put((byte) value);
        }
        int count = target.position() - start;
        outputBytes += count;
        return count == 0 ? -1 : count;
    }

    /// Returns the number of logical compressed bytes consumed.
    @Override
    public long inputBytes() {
        return input.byteCount();
    }

    /// Returns the number of compressed bytes obtained from the source.
    @Override
    public long sourceBytes() {
        return input.sourceByteCount();
    }

    /// Returns a read-only view of compressed bytes not yet consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return input.unconsumedInput();
    }

    /// Returns the number of uncompressed bytes returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this decoder and an owned source channel.
    @Override
    public void close() throws IOException {
        open = false;
        sourceCloser.close();
    }

    /// Requires this decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
