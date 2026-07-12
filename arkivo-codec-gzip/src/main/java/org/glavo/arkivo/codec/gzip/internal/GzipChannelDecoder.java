// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Decodes and validates concatenated gzip members directly through NIO buffers.
@NotNullByDefault
public final class GzipChannelDecoder implements CompressionDecoder {
    /// The compressed-input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The gzip flag indicating an extra field.
    private static final int FLAG_EXTRA = 0x04;

    /// The gzip flag indicating an original file name.
    private static final int FLAG_NAME = 0x08;

    /// The gzip flag indicating a comment.
    private static final int FLAG_COMMENT = 0x10;

    /// The gzip flag indicating a header checksum.
    private static final int FLAG_HEADER_CRC = 0x02;

    /// The reserved gzip flag bits.
    private static final int RESERVED_FLAGS = 0xe0;

    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Whether this context owns the source channel.
    private final ChannelOwnership ownership;

    /// The raw inflate context used for member bodies.
    private final Inflater inflater = new Inflater(true);

    /// The current member-content checksum.
    private final CRC32 checksum = new CRC32();

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The number of compressed bytes read from the source.
    private long inputBytes;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// The current member output size modulo 2^32.
    private long memberSize;

    /// Whether all concatenated members have completed.
    private boolean endOfStream;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a gzip decoder and parses the first member header.
    ///
    /// @param source compressed-data source
    /// @param ownership whether this context closes the source
    public GzipChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        inputBuffer.limit(0);
        try {
            int first = readByteOrEnd();
            if (first < 0) {
                throw new EOFException("Missing gzip member header");
            }
            readMemberHeader(first);
        } catch (IOException | RuntimeException | Error exception) {
            inflater.end();
            throw exception;
        }
    }

    /// Reads and validates decoded member content into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (endOfStream) {
            return -1;
        }

        while (true) {
            int start = target.position();
            int produced;
            try {
                produced = inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid gzip deflate data", exception);
            }
            if (produced > 0) {
                updateOutputChecksum(target, start, target.position());
                memberSize = (memberSize + produced) & 0xffff_ffffL;
                outputBytes += produced;
                return produced;
            }
            if (inflater.finished()) {
                if (!finishMemberAndStartNext()) {
                    return -1;
                }
                continue;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Gzip member requires an unsupported preset dictionary");
            }
            if (!inflater.needsInput()) {
                throw new IOException("Gzip decoder made no progress");
            }
            if (!supplyInflaterInput()) {
                throw new EOFException("Unexpected end of gzip member data");
            }
        }
    }

    /// Returns the compressed byte count read from the source.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the uncompressed byte count returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Releases native resources and closes an owned source channel.
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

    /// Validates a completed member trailer and starts the next member when present.
    private boolean finishMemberAndStartNext() throws IOException {
        long expectedChecksum = readLittleEndianInt();
        long expectedSize = readLittleEndianInt();
        if (expectedChecksum != checksum.getValue()) {
            throw new IOException("Gzip member checksum mismatch");
        }
        if (expectedSize != memberSize) {
            throw new IOException("Gzip member size mismatch");
        }

        int first = readByteOrEnd();
        if (first < 0) {
            endOfStream = true;
            return false;
        }

        inflater.reset();
        readMemberHeader(first);
        return true;
    }

    /// Parses a gzip member header, including optional fields and header CRC.
    private void readMemberHeader(int first) throws IOException {
        CRC32 headerChecksum = new CRC32();
        updateHeaderChecksum(headerChecksum, first);
        int second = readHeaderByte(headerChecksum);
        if (first != 0x1f || second != 0x8b) {
            throw new IOException("Invalid gzip member signature");
        }
        if (readHeaderByte(headerChecksum) != 8) {
            throw new IOException("Unsupported gzip compression method");
        }

        int flags = readHeaderByte(headerChecksum);
        if ((flags & RESERVED_FLAGS) != 0) {
            throw new IOException("Gzip member uses reserved flags");
        }
        for (int index = 0; index < 6; index++) {
            readHeaderByte(headerChecksum);
        }

        if ((flags & FLAG_EXTRA) != 0) {
            int low = readHeaderByte(headerChecksum);
            int high = readHeaderByte(headerChecksum);
            int length = low | high << 8;
            for (int index = 0; index < length; index++) {
                readHeaderByte(headerChecksum);
            }
        }
        if ((flags & FLAG_NAME) != 0) {
            readZeroTerminatedHeaderField(headerChecksum);
        }
        if ((flags & FLAG_COMMENT) != 0) {
            readZeroTerminatedHeaderField(headerChecksum);
        }
        if ((flags & FLAG_HEADER_CRC) != 0) {
            int expected = readRequiredByte() | readRequiredByte() << 8;
            if (expected != ((int) headerChecksum.getValue() & 0xffff)) {
                throw new IOException("Gzip member header checksum mismatch");
            }
        }

        checksum.reset();
        memberSize = 0;
    }

    /// Reads a zero-terminated optional header field.
    private void readZeroTerminatedHeaderField(CRC32 headerChecksum) throws IOException {
        while (readHeaderByte(headerChecksum) != 0) {
            // Continue through the complete field.
        }
    }

    /// Reads and checksums one header byte.
    private int readHeaderByte(CRC32 headerChecksum) throws IOException {
        int value = readRequiredByte();
        updateHeaderChecksum(headerChecksum, value);
        return value;
    }

    /// Adds one unsigned byte to the header checksum.
    private static void updateHeaderChecksum(CRC32 headerChecksum, int value) {
        headerChecksum.update(value);
    }

    /// Updates the member checksum from an absolute output-buffer range.
    private void updateOutputChecksum(ByteBuffer output, int start, int end) {
        ByteBuffer view = output.duplicate();
        view.position(start);
        view.limit(end);
        checksum.update(view);
    }

    /// Reads one unsigned little-endian 32-bit value.
    private long readLittleEndianInt() throws IOException {
        return Integer.toUnsignedLong(
                readRequiredByte()
                        | readRequiredByte() << 8
                        | readRequiredByte() << 16
                        | readRequiredByte() << 24
        );
    }

    /// Reads one required unsigned byte.
    private int readRequiredByte() throws IOException {
        int value = readByteOrEnd();
        if (value < 0) {
            throw new EOFException("Unexpected end of gzip member metadata");
        }
        return value;
    }

    /// Reads one unsigned byte, or `-1` at the physical end of the source.
    private int readByteOrEnd() throws IOException {
        if (!inputBuffer.hasRemaining() && !fillInputBuffer()) {
            return -1;
        }
        return Byte.toUnsignedInt(inputBuffer.get());
    }

    /// Supplies buffered or newly read compressed bytes to the inflater.
    private boolean supplyInflaterInput() throws IOException {
        if (!inputBuffer.hasRemaining() && !fillInputBuffer()) {
            return false;
        }
        inflater.setInput(inputBuffer);
        return true;
    }

    /// Reads another physical source chunk into the owned input buffer.
    private boolean fillInputBuffer() throws IOException {
        inputBuffer.clear();
        int read = source.read(inputBuffer);
        if (read < 0) {
            inputBuffer.limit(0);
            return false;
        }
        if (read == 0) {
            throw new IOException("Gzip source channel made no progress");
        }
        inputBytes += read;
        inputBuffer.flip();
        return true;
    }

    /// Requires the decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
