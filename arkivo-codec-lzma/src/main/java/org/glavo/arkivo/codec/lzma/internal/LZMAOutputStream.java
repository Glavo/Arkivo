// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Writes LZMA-alone streams or raw LZMA range-coded streams.
@NotNullByDefault
public final class LZMAOutputStream extends OutputStream {
    /// The sentinel indicating that no exact uncompressed size is declared.
    public static final long UNKNOWN_UNCOMPRESSED_SIZE = -1L;

    /// The compressed destination owned by this stream.
    private final OutputStream output;

    /// The channel-backed compressed-byte target.
    private final LZMAChannelOutput channelOutput;

    /// The raw LZMA encoder engine.
    private final LZMAEncoderEngine encoder;

    /// The expected uncompressed size, or `-1` when not declared.
    private final long expectedSize;

    /// Whether to write the reserved LZMA end marker.
    private final boolean endMarker;

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates an EOS-terminated LZMA-alone stream with default properties.
    public LZMAOutputStream(OutputStream output, int dictionarySize) throws IOException {
        this(output, LZMAProperties.defaults(dictionarySize));
    }

    /// Creates an EOS-terminated LZMA-alone stream with supplied properties.
    public LZMAOutputStream(OutputStream output, LZMAProperties properties) throws IOException {
        this(output, properties, UNKNOWN_UNCOMPRESSED_SIZE);
    }

    /// Creates a known-size LZMA-alone stream without an end marker.
    public LZMAOutputStream(
            OutputStream output,
            LZMAProperties properties,
            long expectedSize
    ) throws IOException {
        this(output, properties, expectedSize, false, true);
    }

    /// Creates a raw LZMA stream and optionally writes an end marker.
    public LZMAOutputStream(
            OutputStream output,
            LZMAProperties properties,
            boolean endMarker
    ) throws IOException {
        this(output, properties, UNKNOWN_UNCOMPRESSED_SIZE, endMarker, false);
    }

    /// Creates one configured LZMA stream and writes its optional standalone header.
    private LZMAOutputStream(
            OutputStream output,
            LZMAProperties properties,
            long expectedSize,
            boolean endMarker,
            boolean standalone
    ) throws IOException {
        if (expectedSize < UNKNOWN_UNCOMPRESSED_SIZE) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        if (standalone && expectedSize == UNKNOWN_UNCOMPRESSED_SIZE) {
            endMarker = true;
        }
        this.output = Objects.requireNonNull(output, "output");
        channelOutput = new LZMAChannelOutput(Channels.newChannel(output));
        this.expectedSize = expectedSize;
        this.endMarker = endMarker;
        if (standalone) {
            writeHeader(channelOutput, properties, expectedSize);
        }
        encoder = new LZMAEncoderEngine(channelOutput, properties);
    }

    /// Writes one uncompressed byte.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Writes uncompressed bytes to the streaming encoder.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (expectedSize >= 0L && length > expectedSize - encoder.inputSize()) {
            throw new IOException("LZMA input exceeds the declared uncompressed size");
        }
        encoder.write(bytes, offset, length);
    }

    /// Flushes compressed bytes already emitted by the range coder.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        channelOutput.flush();
        output.flush();
    }

    /// Finalizes the LZMA stream and closes the destination.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            if (expectedSize >= 0L && encoder.inputSize() != expectedSize) {
                throw new IOException("LZMA input does not match the declared uncompressed size");
            }
            encoder.finish(endMarker);
            channelOutput.flush();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            output.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Writes the 13-byte LZMA-alone header.
    private static void writeHeader(
            LZMAChannelOutput output,
            LZMAProperties properties,
            long expectedSize
    ) throws IOException {
        output.write(properties.propertyByte());
        writeLittleEndian(output, properties.dictionarySize(), Integer.BYTES);
        writeLittleEndian(output, expectedSize, Long.BYTES);
    }

    /// Writes a fixed-width little-endian integer.
    private static void writeLittleEndian(LZMAChannelOutput output, long value, int length) throws IOException {
        for (int index = 0; index < length; index++) {
            output.write((int) (value >>> (index * 8)) & 0xff);
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
