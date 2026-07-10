// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
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
        SevenZipSignatureHeader signatureHeader = readFixedSignatureHeader(channel);
        validateNextHeader(channel, signatureHeader);
        return signatureHeader;
    }

    /// Reads and validates 7z archive metadata.
    public static SevenZipArchiveMetadata readArchiveMetadata(SeekableByteChannel channel) throws IOException {
        return readArchiveMetadata(channel, null);
    }

    /// Reads and validates 7z archive metadata.
    public static SevenZipArchiveMetadata readArchiveMetadata(
            SeekableByteChannel channel,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        Objects.requireNonNull(channel, "channel");
        SevenZipSignatureHeader signatureHeader = readSignatureHeader(channel);
        byte[] nextHeader = readNextHeader(channel, signatureHeader);
        return new SevenZipArchiveMetadata(
                signatureHeader,
                SevenZipHeaderParser.parseEntries(
                        nextHeader,
                        (offset, size) -> openPackedStream(channel, offset, size),
                        passwordProvider
                )
        );
    }

    /// Reads and validates only the fixed signature bytes and start-header CRC-32.
    private static SevenZipSignatureHeader readFixedSignatureHeader(SeekableByteChannel channel) throws IOException {
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

        long nextHeaderOffset = readUnsignedLong(buffer, "next header offset");
        long nextHeaderSize = readUnsignedLong(buffer, "next header size");
        long nextHeaderCrc32 = Integer.toUnsignedLong(buffer.getInt());
        return new SevenZipSignatureHeader(
                majorVersion,
                minorVersion,
                nextHeaderOffset,
                nextHeaderSize,
                nextHeaderCrc32
        );
    }

    /// Reads an unsigned 64-bit 7z field that must fit in a Java `long`.
    private static long readUnsignedLong(ByteBuffer buffer, String description) throws IOException {
        long value = buffer.getLong();
        if (value < 0) {
            throw new IOException("7z " + description + " is too large");
        }
        return value;
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

    /// Reads the validated next header bytes.
    private static byte[] readNextHeader(
            SeekableByteChannel channel,
            SevenZipSignatureHeader signatureHeader
    ) throws IOException {
        if (signatureHeader.nextHeaderSize() > Integer.MAX_VALUE) {
            throw new IOException("7z next header is too large to index");
        }

        byte[] bytes = new byte[(int) signatureHeader.nextHeaderSize()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        channel.position(SevenZipSignatureHeader.SIZE + signatureHeader.nextHeaderOffset());
        readFully(channel, buffer);
        return bytes;
    }

    /// Reads a packed header stream and exposes it as an input stream.
    private static ByteArrayInputStream openPackedStream(
            SeekableByteChannel channel,
            long offset,
            long size
    ) throws IOException {
        if (offset < 0) {
            throw new IOException("Invalid 7z packed stream offset");
        }
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IOException("7z packed stream is too large to index");
        }
        long end = checkedAdd(offset, size, "Invalid 7z packed stream size");
        if (end > channel.size()) {
            throw new EOFException("Unexpected end of 7z packed stream");
        }

        byte[] bytes = new byte[(int) size];
        channel.position(offset);
        readFully(channel, ByteBuffer.wrap(bytes));
        return new ByteArrayInputStream(bytes);
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
