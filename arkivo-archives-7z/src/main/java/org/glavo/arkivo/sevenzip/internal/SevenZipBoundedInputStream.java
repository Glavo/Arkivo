// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Limits reads from an input stream to a fixed byte count.
@NotNullByDefault
final class SevenZipBoundedInputStream extends InputStream {
    /// The underlying input stream.
    private final InputStream input;

    /// The remaining byte count.
    private long remaining;

    /// Creates a bounded input stream.
    SevenZipBoundedInputStream(InputStream input, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        this.input = Objects.requireNonNull(input, "input");
        this.remaining = size;
    }

    /// Reads one byte.
    @Override
    public int read() throws IOException {
        if (remaining == 0) {
            return -1;
        }
        int value = input.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    /// Reads bytes into the target array.
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (remaining == 0) {
            return -1;
        }
        int count = input.read(buffer, offset, (int) Math.min(length, remaining));
        if (count > 0) {
            remaining -= count;
        }
        return count;
    }

    /// Closes this stream and its underlying stream.
    @Override
    public void close() throws IOException {
        input.close();
    }
}
