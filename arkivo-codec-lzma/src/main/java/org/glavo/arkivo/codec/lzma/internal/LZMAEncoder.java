// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally encodes an LZMA-alone stream over the shared raw buffer engine.
@NotNullByDefault
public final class LZMAEncoder implements CompressionEncoder {
    /// Number of bytes in an LZMA-alone header.
    private static final int HEADER_SIZE = 13;

    /// Immutable LZMA-alone header bytes.
    private final byte @Unmodifiable [] headerBytes;

    /// Shared transport-independent raw stream encoder.
    private final LZMARawEncoder rawEncoder;

    /// Pending header view reset for each encoding.
    private @UnmodifiableView ByteBuffer header;

    /// Whether this wrapper has closed.
    private boolean closed;

    /// Creates an LZMA-alone encoder with complete properties and optional exact input size.
    ///
    /// @param properties  LZMA model and dictionary properties
    /// @param pledgedSize exact input size, or `CompressionCodec.UNKNOWN_SIZE`
    public LZMAEncoder(LZMAProperties properties, long pledgedSize) {
        Objects.requireNonNull(properties, "properties");
        if (pledgedSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        headerBytes = createHeader(properties, pledgedSize);
        header = ByteBuffer.wrap(headerBytes).asReadOnlyBuffer();
        rawEncoder = new LZMARawEncoder(properties, pledgedSize, pledgedSize < 0L);
    }

    /// Emits the header and encodes source bytes through the shared raw engine.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        copyHeader(target);
        if (header.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        return rawEncoder.encode(source, target);
    }

    /// Flushes complete range-coded bytes after draining the fixed header.
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        copyHeader(target);
        if (header.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        return rawEncoder.flush(target);
    }

    /// Finishes the shared raw stream after draining the fixed header.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        copyHeader(target);
        if (header.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        return rawEncoder.finish(target);
    }

    /// Abandons the stream and restores the header and raw encoder.
    @Override
    public void reset() {
        requireOpen();
        header = ByteBuffer.wrap(headerBytes).asReadOnlyBuffer();
        rawEncoder.reset();
    }

    /// Releases the raw encoder without implicitly finishing it.
    @Override
    public void close() {
        closed = true;
        rawEncoder.close();
        header.position(header.limit());
    }

    /// Copies as many pending header bytes as fit in the caller target.
    private void copyHeader(ByteBuffer target) {
        int length = Math.min(header.remaining(), target.remaining());
        if (length == 0) {
            return;
        }
        int originalLimit = header.limit();
        header.limit(header.position() + length);
        try {
            target.put(header);
        } finally {
            header.limit(originalLimit);
        }
    }

    /// Creates the property, dictionary-size, and uncompressed-size header.
    private static byte @Unmodifiable [] createHeader(LZMAProperties properties, long uncompressedSize) {
        ByteBuffer result = ByteBuffer.allocate(HEADER_SIZE);
        result.put((byte) properties.propertyByte());
        putLittleEndian(result, Integer.toUnsignedLong(properties.dictionarySize()), Integer.BYTES);
        putLittleEndian(result, uncompressedSize, Long.BYTES);
        return result.array();
    }

    /// Writes one fixed-width little-endian integer into a header.
    private static void putLittleEndian(ByteBuffer target, long value, int length) {
        for (int index = 0; index < length; index++) {
            target.put((byte) (value >>> (index * 8)));
        }
    }

    /// Requires this wrapper to remain open.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("LZMA encoder is closed");
        }
    }
}
