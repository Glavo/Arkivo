// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.zip.CRC32;

/// Reads fixed 7z header structures from a seekable channel.
@NotNullByDefault
public final class SevenZipHeaderReader {
    /// The fixed 7z file signature bytes.
    private static final byte[] SIGNATURE = new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

    /// Creates no instances.
    private SevenZipHeaderReader() {
    }

    /// Reads and validates the fixed 7z signature header.
    public static SevenZipSignatureHeader readSignatureHeader(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        ByteBuffer buffer = ByteBuffer.allocate(SevenZipSignatureHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(0);
        readFully(channel, buffer);
        buffer.flip();

        for (byte expected : SIGNATURE) {
            byte actual = buffer.get();
            if (actual != expected) {
                throw new IOException("Invalid 7z signature");
            }
        }

        int majorVersion = Byte.toUnsignedInt(buffer.get());
        int minorVersion = Byte.toUnsignedInt(buffer.get());
        long expectedStartHeaderCrc32 = Integer.toUnsignedLong(buffer.getInt());

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        long actualStartHeaderCrc32 = crc32.getValue();
        if (actualStartHeaderCrc32 != expectedStartHeaderCrc32) {
            throw new IOException("Invalid 7z start header CRC-32");
        }

        long nextHeaderOffset = buffer.getLong();
        long nextHeaderSize = buffer.getLong();
        long nextHeaderCrc32 = Integer.toUnsignedLong(buffer.getInt());
        SevenZipSignatureHeader signatureHeader = new SevenZipSignatureHeader(
                majorVersion,
                minorVersion,
                nextHeaderOffset,
                nextHeaderSize,
                nextHeaderCrc32
        );
        validateNextHeader(channel, signatureHeader);
        return signatureHeader;
    }

    /// Reads bytes until the destination buffer is full.
    private static void readFully(SeekableByteChannel channel, ByteBuffer destination) throws IOException {
        while (destination.hasRemaining()) {
            int read = channel.read(destination);
            if (read < 0) {
                throw new EOFException("Unexpected end of 7z signature header");
            }
        }
    }

    /// Validates that the next header is present and matches the stored CRC-32 value.
    private static void validateNextHeader(
            SeekableByteChannel channel,
            SevenZipSignatureHeader signatureHeader
    ) throws IOException {
        long nextHeaderStart = checkedAdd(
                SevenZipSignatureHeader.SIZE,
                signatureHeader.nextHeaderOffset(),
                "Invalid 7z next header offset"
        );
        long nextHeaderEnd = checkedAdd(
                nextHeaderStart,
                signatureHeader.nextHeaderSize(),
                "Invalid 7z next header size"
        );
        if (nextHeaderEnd > channel.size()) {
            throw new EOFException("Unexpected end of 7z next header");
        }

        CRC32 crc32 = new CRC32();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long remaining = signatureHeader.nextHeaderSize();
        channel.position(nextHeaderStart);
        while (remaining > 0) {
            buffer.clear();
            int chunkSize = (int) Math.min(buffer.capacity(), remaining);
            buffer.limit(chunkSize);
            readFully(channel, buffer);
            crc32.update(buffer.array(), 0, chunkSize);
            remaining -= chunkSize;
        }

        if (crc32.getValue() != signatureHeader.nextHeaderCrc32()) {
            throw new IOException("Invalid 7z next header CRC-32");
        }
    }

    /// Adds two non-negative 7z size values and reports overflow as an I/O failure.
    private static long checkedAdd(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }
}
