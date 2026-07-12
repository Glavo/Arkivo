// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.zip.CRC32;

/// Validates CRC-32 while a read-only 7z entry channel is consumed sequentially.
@NotNullByDefault
final class SevenZipCRC32ByteChannel implements SeekableByteChannel {
    /// The wrapped entry channel.
    private final SeekableByteChannel channel;

    /// The expected entry size.
    private final long size;

    /// The expected CRC-32 value.
    private final long expectedCrc32;

    /// The validation failure message.
    private final String failureMessage;

    /// The CRC-32 of bytes read or drained in sequential order.
    private final CRC32 crc32 = new CRC32();

    /// The next sequential position needed for validation.
    private long validationPosition;

    /// Whether validation is still possible for the observed access pattern.
    private boolean validationActive = true;

    /// Whether the CRC-32 has already been validated.
    private boolean crc32Validated;

    /// Whether this channel has been closed.
    private boolean closed;

    /// Whether the wrapped entry channel has been closed.
    private boolean channelClosed;

    /// Creates a validating channel around a read-only entry channel.
    SevenZipCRC32ByteChannel(SeekableByteChannel channel, long size, long expectedCrc32) {
        this(channel, size, expectedCrc32, "7z entry data does not match CRC-32");
    }

    /// Creates a validating channel around a read-only channel.
    SevenZipCRC32ByteChannel(SeekableByteChannel channel, long size, long expectedCrc32, String failureMessage) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (expectedCrc32 < 0 || expectedCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("expectedCrc32 must be an unsigned 32-bit value");
        }
        this.channel = Objects.requireNonNull(channel, "channel");
        this.size = size;
        this.expectedCrc32 = expectedCrc32;
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    /// Reads bytes into the destination buffer.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        Objects.requireNonNull(destination, "destination");
        return readUnchecked(destination);
    }

    /// Reads bytes without checking the wrapper open state for close-time draining.
    private int readUnchecked(ByteBuffer destination) throws IOException {
        int start = destination.position();
        long position = channel.position();
        int read = channel.read(destination);
        if (validationActive) {
            if (read > 0) {
                if (position == validationPosition) {
                    ByteBuffer readBytes = destination.duplicate();
                    readBytes.position(start);
                    readBytes.limit(start + read);
                    crc32.update(readBytes);
                    validationPosition += read;
                    if (validationPosition == size) {
                        validateCrc32();
                    }
                } else {
                    validationActive = false;
                }
            } else if (read < 0) {
                if (validationPosition != size) {
                    throw new EOFException("Unexpected end of 7z entry body");
                }
                validateCrc32();
            } else if (!destination.hasRemaining() && position != validationPosition) {
                validationActive = false;
            }
        }
        return read;
    }

    /// Rejects writes.
    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        throw new UnsupportedOperationException("7z byte channels are read-only");
    }

    /// Returns the current channel position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return channel.position();
    }

    /// Sets the current channel position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        channel.position(newPosition);
        if (newPosition != validationPosition) {
            validationActive = false;
        }
        return this;
    }

    /// Returns the channel size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return channel.size();
    }

    /// Rejects truncation.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new UnsupportedOperationException("7z byte channels are read-only");
    }

    /// Returns whether this channel is open.
    @Override
    public boolean isOpen() {
        return !closed && channel.isOpen();
    }

    /// Closes this channel after draining sequential reads when validation is still active.
    @Override
    public void close() throws IOException {
        if (closed && channelClosed) {
            return;
        }

        Throwable failure = null;
        if (!closed) {
            closed = true;
            try {
                if (validationActive && !crc32Validated && channel.isOpen() && channel.position() == validationPosition) {
                    drainForValidation();
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }

        if (!channelClosed) {
            try {
                channel.close();
                channelClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }

        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Drains remaining sequential bytes so the CRC-32 can be validated on close.
    private void drainForValidation() throws IOException {
        ByteBuffer discard = ByteBuffer.allocate(8192);
        while (validationPosition < size) {
            discard.clear();
            int read = readUnchecked(discard);
            if (read < 0) {
                throw new EOFException("Unexpected end of 7z entry body");
            }
        }
        validateCrc32();
    }

    /// Validates the CRC-32 value after all entry bytes have been consumed.
    private void validateCrc32() throws IOException {
        if (crc32Validated) {
            return;
        }
        crc32Validated = true;
        if (crc32.getValue() != expectedCrc32) {
            throw new IOException(failureMessage);
        }
    }

    /// Requires this channel to be open.
    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }
}
