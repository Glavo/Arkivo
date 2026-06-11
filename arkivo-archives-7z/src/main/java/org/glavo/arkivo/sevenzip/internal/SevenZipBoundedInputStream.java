// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/// Limits reads from an input stream to a fixed byte count.
@NotNullByDefault
final class SevenZipBoundedInputStream extends InputStream {
    /// The underlying input stream.
    private final InputStream input;

    /// The remaining byte count.
    private long remaining;

    /// The expected CRC-32 value, or `UNKNOWN_CRC32` when this stream should not validate a digest.
    private final long expectedCrc32;

    /// The CRC-32 of bytes returned or drained from this stream, or `null` when validation is disabled.
    private final @Nullable CRC32 crc32;

    /// The validation failure message.
    private final String failureMessage;

    /// Whether the CRC-32 value has already been validated.
    private boolean crc32Validated;

    /// Whether this stream has been closed.
    private boolean closed;

    /// Whether the underlying input stream has been closed.
    private boolean inputClosed;

    /// Creates a bounded input stream.
    SevenZipBoundedInputStream(InputStream input, long size) {
        this(input, size, SevenZipEntryMetadata.UNKNOWN_CRC32);
    }

    /// Creates a bounded input stream with an expected CRC-32 value.
    SevenZipBoundedInputStream(InputStream input, long size, long expectedCrc32) {
        this(input, size, expectedCrc32, "7z entry data does not match CRC-32");
    }

    /// Creates a bounded input stream with an expected CRC-32 value and failure message.
    SevenZipBoundedInputStream(InputStream input, long size, long expectedCrc32, String failureMessage) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (expectedCrc32 < SevenZipEntryMetadata.UNKNOWN_CRC32 || expectedCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("expectedCrc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
        }
        this.input = Objects.requireNonNull(input, "input");
        this.remaining = size;
        this.expectedCrc32 = expectedCrc32;
        this.crc32 = expectedCrc32 != SevenZipEntryMetadata.UNKNOWN_CRC32 ? new CRC32() : null;
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    /// Reads one byte.
    @Override
    public int read() throws IOException {
        ensureOpen();
        return readUnchecked();
    }

    /// Reads one byte without checking the wrapper open state for close-time draining.
    private int readUnchecked() throws IOException {
        if (remaining == 0) {
            validateCrc32();
            return -1;
        }
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of 7z entry body");
        }
        remaining--;
        CRC32 currentCrc32 = crc32;
        if (currentCrc32 != null) {
            currentCrc32.update(value);
            if (remaining == 0) {
                validateCrc32();
            }
        }
        return value;
    }

    /// Reads bytes into the target array.
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureOpen();
        return readUnchecked(buffer, offset, length);
    }

    /// Reads bytes without checking the wrapper open state for close-time draining.
    private int readUnchecked(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining == 0) {
            validateCrc32();
            return -1;
        }
        int count = input.read(buffer, offset, (int) Math.min(length, remaining));
        if (count < 0) {
            throw new EOFException("Unexpected end of 7z entry body");
        }
        if (count > 0) {
            remaining -= count;
            CRC32 currentCrc32 = crc32;
            if (currentCrc32 != null) {
                currentCrc32.update(buffer, offset, count);
                if (remaining == 0) {
                    validateCrc32();
                }
            }
        }
        return count;
    }

    /// Closes this stream and its underlying stream.
    @Override
    public void close() throws IOException {
        if (closed && inputClosed) {
            return;
        }

        Throwable failure = null;
        if (!closed) {
            closed = true;
            try {
                byte[] discard = new byte[8192];
                while (remaining > 0) {
                    readUnchecked(discard, 0, discard.length);
                }
                validateCrc32();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }

        if (!inputClosed) {
            try {
                input.close();
                inputClosed = true;
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

    /// Validates the CRC-32 value after the bounded stream body has been fully consumed.
    private void validateCrc32() throws IOException {
        CRC32 currentCrc32 = crc32;
        if (currentCrc32 == null || crc32Validated) {
            return;
        }
        crc32Validated = true;
        if (currentCrc32.getValue() != expectedCrc32) {
            throw new IOException(failureMessage);
        }
    }

    /// Requires this stream to be open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("stream is closed");
        }
    }
}
