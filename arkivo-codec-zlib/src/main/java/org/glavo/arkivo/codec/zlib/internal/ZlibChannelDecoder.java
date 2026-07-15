// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zlib.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
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

/// Decodes zlib data directly between a source channel and ByteBuffers.
@NotNullByDefault
public final class ZlibChannelDecoder implements DecompressingReadableByteChannel {
    /// The compressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The JDK zlib-wrapped inflate context.
    private final Inflater inflater;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The maximum permitted zlib window size, or the unknown sentinel.
    private final long maximumWindowSize;

    /// The configured preset dictionary, or null.
    private final @Nullable CompressionDictionary dictionary;

    /// Whether the zlib window declaration has been validated.
    private boolean windowValidated;

    /// The number of compressed bytes logically consumed by the inflater.
    private long inputBytes;

    /// The number of compressed bytes obtained from the source.
    private long sourceBytes;

    /// The number of uncompressed bytes produced.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a zlib decoder.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    public ZlibChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) {
        this(source, ownership, CompressionCodec.UNKNOWN_SIZE, null);
    }

    /// Creates a zlib decoder with an optional maximum declared window size.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    /// @param maximumWindowSize maximum permitted window size, or the unknown sentinel
    public ZlibChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            long maximumWindowSize
    ) {
        this(source, ownership, maximumWindowSize, null);
    }

    /// Creates a zlib decoder with optional window and preset-dictionary configuration.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    /// @param maximumWindowSize maximum permitted window size, or the unknown sentinel
    /// @param dictionary preset dictionary, or null
    public ZlibChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            long maximumWindowSize,
            @Nullable CompressionDictionary dictionary
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.inflater = new Inflater(false);
        this.maximumWindowSize = maximumWindowSize;
        this.dictionary = dictionary;
        windowValidated = maximumWindowSize < 0L;
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
                throw new IOException("Invalid zlib stream", exception);
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
                applyDictionary();
                continue;
            }
            if (!inflater.needsInput()) {
                throw new IOException("Zlib decoder made no progress");
            }
            if (!readCompressedInput()) {
                throw new EOFException("Unexpected end of zlib stream");
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
        while (true) {
            int read = source.read(inputBuffer);
            if (read < 0) {
                inputBuffer.flip();
                if (!inputBuffer.hasRemaining()) {
                    return false;
                }
                windowValidated = true;
                inflater.setInput(inputBuffer);
                return true;
            }
            if (read == 0) {
                throw new IOException("Zlib source channel made no progress");
            }

            sourceBytes += read;
            inputBuffer.flip();
            if (windowValidated || inputBuffer.remaining() >= Short.BYTES) {
                validateWindowSize();
                inflater.setInput(inputBuffer);
                return true;
            }
            inputBuffer.compact();
        }
    }

    /// Validates the window size encoded by a complete two-byte zlib header.
    private void validateWindowSize() throws IOException {
        if (windowValidated) {
            return;
        }
        int position = inputBuffer.position();
        int compressionMethodAndInfo = Byte.toUnsignedInt(inputBuffer.get(position));
        int flags = Byte.toUnsignedInt(inputBuffer.get(position + 1));
        int header = (compressionMethodAndInfo << 8) | flags;
        int compressionMethod = compressionMethodAndInfo & 0x0f;
        int windowBits = (compressionMethodAndInfo >>> 4) + 8;
        if (compressionMethod == 8 && windowBits <= 15 && header % 31 == 0) {
            StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, 1L << windowBits);
        }
        windowValidated = true;
    }

    /// Applies and validates the configured dictionary requested by the current zlib stream.
    private void applyDictionary() throws IOException {
        long requiredIdentifier = Integer.toUnsignedLong(inflater.getAdler());
        if (dictionary == null) {
            throw new IOException("Zlib stream requires preset dictionary " + requiredIdentifier);
        }
        if (dictionary.id() != CompressionDictionary.UNKNOWN_ID
                && dictionary.id() != requiredIdentifier) {
            throw new IOException("Configured zlib dictionary identifier does not match " + requiredIdentifier);
        }
        try {
            inflater.setDictionary(dictionary.bytes());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Configured zlib dictionary does not match " + requiredIdentifier, exception);
        }
    }

    /// Requires the decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
