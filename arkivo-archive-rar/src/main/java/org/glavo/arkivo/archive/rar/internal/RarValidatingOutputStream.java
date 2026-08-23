// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/// Enforces a declared RAR output size and computes CRC32 while forwarding decoded bytes to a borrowed stream.
@NotNullByDefault
final class RarValidatingOutputStream extends OutputStream {
    /// The format name used in validation failures.
    private final String formatName;

    /// The borrowed decoded-data target.
    private final OutputStream target;

    /// The declared decompressed byte count.
    private final long expectedSize;

    /// The CRC32 of successfully forwarded bytes.
    private final CRC32 crc32 = new CRC32();

    /// The number of successfully forwarded bytes.
    private long writtenSize;

    /// Creates a validating stream over a borrowed target.
    ///
    /// @param formatName the format name used in validation failures
    /// @param target the borrowed stream receiving decoded bytes
    /// @param expectedSize the non-negative declared decompressed size
    /// @throws IllegalArgumentException if {@code expectedSize} is negative
    /// @throws NullPointerException if {@code formatName} or {@code target} is {@code null}
    RarValidatingOutputStream(String formatName, OutputStream target, long expectedSize) {
        this.formatName = Objects.requireNonNull(formatName, "formatName");
        this.target = Objects.requireNonNull(target, "target");
        if (expectedSize < 0L) {
            throw new IllegalArgumentException("expectedSize must not be negative");
        }
        this.expectedSize = expectedSize;
    }

    /// Writes one decoded byte and updates the output CRC32.
    ///
    /// @param value the byte value to write
    /// @throws IOException if the declared size would be exceeded or the target write fails
    @Override
    public void write(int value) throws IOException {
        if (writtenSize >= expectedSize) {
            throw sizeExceeded();
        }
        target.write(value);
        crc32.update(value);
        writtenSize++;
    }

    /// Writes decoded bytes and updates the output CRC32.
    ///
    /// @param buffer the source byte array
    /// @param offset the first source index
    /// @param length the number of bytes to write
    /// @throws IOException if the declared size would be exceeded or the target write fails
    /// @throws IndexOutOfBoundsException if the source range is invalid
    /// @throws NullPointerException if {@code buffer} is {@code null}
    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (writtenSize > expectedSize - length) {
            throw sizeExceeded();
        }
        target.write(buffer, offset, length);
        crc32.update(buffer, offset, length);
        writtenSize += length;
    }

    /// Validates the final decoded size and returns the unsigned CRC32 value.
    ///
    /// @return the unsigned CRC32 of every successfully forwarded byte
    /// @throws IOException if fewer than the declared number of bytes were written
    long validatedCrc32() throws IOException {
        if (writtenSize != expectedSize) {
            throw new IOException(
                    formatName + " decompressor produced " + writtenSize + " bytes; expected " + expectedSize
            );
        }
        return crc32.getValue();
    }

    /// Creates a failure for output beyond the declared decompressed size.
    private IOException sizeExceeded() {
        return new IOException(formatName + " decompressor exceeded the declared unpacked size");
    }
}
