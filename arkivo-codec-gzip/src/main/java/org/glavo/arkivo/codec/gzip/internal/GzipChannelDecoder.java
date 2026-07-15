// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.DecodeDirective;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

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
public final class GzipChannelDecoder implements DecompressingReadableByteChannel {
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

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The raw inflate context used for member bodies.
    private final Inflater inflater = new Inflater(true);

    /// The current member-content checksum.
    private final CRC32 checksum = new CRC32();

    /// The direct compressed-input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The number of compressed bytes logically consumed by the member parser.
    private long inputBytes;

    /// The number of compressed bytes obtained from the source.
    private long sourceBytes;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// The current member output size modulo 2^32.
    private long memberSize;

    /// Whether the completed member boundary is awaiting the next member.
    private boolean nextMemberPending;

    /// Whether the last decode operation completed a member.
    private boolean lastMemberFinished;

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
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        inputBuffer.limit(0);
        try {
            int first = readByteOrEnd();
            if (first < 0) {
                throw new EOFException("Missing gzip member header");
            }
            readMemberHeader(first);
        } catch (IOException | RuntimeException | Error exception) {
            inflater.end();
            sourceCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Reads and validates decoded member content into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        return readDecoded(target, false);
    }

    /// Decodes one increment while optionally stopping after the current member.
    @Override
    public CodecResult decode(ByteBuffer target, DecodeDirective directive) throws IOException {
        Objects.requireNonNull(directive, "directive");
        long inputBefore = inputBytes;
        long outputBefore = outputBytes;
        boolean stopAtFrame = directive == DecodeDirective.STOP_AT_FRAME;
        int read = readDecoded(target, stopAtFrame);
        CodecStatus status = stopAtFrame && lastMemberFinished
                ? CodecStatus.FRAME_FINISHED
                : read < 0 ? CodecStatus.END_OF_INPUT : CodecStatus.ACTIVE;
        return new CodecResult(inputBytes - inputBefore, outputBytes - outputBefore, status);
    }

    /// Performs one decoded read with explicit member-boundary behavior.
    private int readDecoded(ByteBuffer target, boolean stopAtFrame) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        lastMemberFinished = false;
        if (!target.hasRemaining()) {
            return 0;
        }
        if (nextMemberPending && !startNextMember()) {
            return -1;
        }
        if (endOfStream) {
            return -1;
        }

        while (true) {
            int start = target.position();
            int produced;
            int inputPosition = inputBuffer.position();
            try {
                produced = inflater.inflate(target);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid gzip deflate data", exception);
            } finally {
                inputBytes += inputBuffer.position() - inputPosition;
            }
            if (produced > 0) {
                updateOutputChecksum(target, start, target.position());
                memberSize = (memberSize + produced) & 0xffff_ffffL;
                outputBytes += produced;
                if (inflater.finished()) {
                    finishMember();
                    nextMemberPending = true;
                    lastMemberFinished = true;
                }
                return produced;
            }
            if (inflater.finished()) {
                finishMember();
                nextMemberPending = true;
                lastMemberFinished = true;
                if (stopAtFrame) {
                    return 0;
                }
                if (!startNextMember()) {
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

    /// Returns the compressed byte count logically consumed by the member parser.
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
        if (open) {
            open = false;
            inflater.end();
        }
        sourceCloser.close();
    }

    /// Validates the completed member trailer.
    private void finishMember() throws IOException {
        long expectedChecksum = readLittleEndianInt();
        long expectedSize = readLittleEndianInt();
        if (expectedChecksum != checksum.getValue()) {
            throw new IOException("Gzip member checksum mismatch");
        }
        if (expectedSize != memberSize) {
            throw new IOException("Gzip member size mismatch");
        }
    }

    /// Starts the member following a previously reported boundary.
    private boolean startNextMember() throws IOException {
        nextMemberPending = false;
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
        inputBytes++;
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
        sourceBytes += read;
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
