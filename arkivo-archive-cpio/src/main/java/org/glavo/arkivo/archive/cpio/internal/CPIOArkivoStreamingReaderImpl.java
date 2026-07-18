// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio.internal;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.cpio.CPIOArchiveOptions;
import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributes;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.CPIOBinaryByteOrder;
import org.glavo.arkivo.archive.cpio.CPIODialect;
import org.glavo.arkivo.archive.cpio.CPIOMetadataCharsetDetector;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Objects;

/// Implements forward-only reading for every standardized CPIO header dialect.
@NotNullByDefault
public final class CPIOArkivoStreamingReaderImpl extends CPIOArkivoStreamingReader {
    /// The new portable ASCII header size in bytes.
    private static final int NEW_ASCII_HEADER_SIZE = 110;

    /// The old portable ASCII header size in bytes.
    private static final int OLD_ASCII_HEADER_SIZE = 76;

    /// The old binary header size in bytes.
    private static final int OLD_BINARY_HEADER_SIZE = 26;

    /// The CPIO end-of-archive entry name.
    private static final byte @Unmodifiable [] TRAILER_NAME = "TRAILER!!!".getBytes(StandardCharsets.US_ASCII);

    /// The reusable body discard buffer size.
    private static final int DISCARD_BUFFER_SIZE = 8192;

    /// The backing archive input stream.
    private final InputStream source;

    /// Reusable storage for discarded body and padding bytes.
    private final byte[] discardBuffer = new byte[DISCARD_BUFFER_SIZE];

    /// The common archive read-limit tracker.
    private final ArkivoReadLimitTracker readLimits;

    /// The detector used to decode CPIO entry names.
    private final ArchiveMetadataCharsetDetector metadataCharsetDetector;

    /// Whether this reader accepts archive operations.
    private boolean open = true;

    /// Whether the backing stream has been closed.
    private boolean sourceClosed;

    /// Whether the required trailer has terminated the archive.
    private boolean finished;

    /// The current entry attributes, or `null` without an active entry.
    private @Nullable CPIOEntryAttributes currentAttributes;

    /// The remaining data bytes in the current entry.
    private long remainingEntryBytes;

    /// The alignment bytes following the current entry data.
    private int pendingDataPadding;

    /// The checksum stored by the current CRC header, or `NOT_STORED`.
    private long expectedChecksum = CPIOArkivoEntryAttributes.NOT_STORED;

    /// The unsigned sum of current entry data bytes observed so far.
    private long actualChecksum;

    /// Whether the current entry body has been opened.
    private boolean currentBodyOpened;

    /// Creates a streaming CPIO reader.
    public CPIOArkivoStreamingReaderImpl(InputStream source, CPIOArchiveOptions.Read options) {
        this.source = Objects.requireNonNull(source, "source");
        CPIOArchiveOptions.Read checkedOptions = Objects.requireNonNull(options, "options");
        this.readLimits = ArkivoReadLimitTracker.fromLimits(checkedOptions.common().limits());
        this.metadataCharsetDetector = checkedOptions.metadataCharsetDetector();
    }

    /// Advances to the next logical CPIO entry.
    @Override
    protected boolean advance() throws IOException {
        ensureOpen();
        readLimits.requireWithinLimits();
        skipCurrentEntry();
        currentAttributes = null;
        if (finished) {
            return false;
        }

        while (true) {
            Header header = readHeader();
            readLimits.acceptMetadata(header.headerSize(), null);
            int nameSize = requireNameSize(header.nameSize());
            readLimits.acceptMetadata((long) nameSize + header.namePadding(), null);
            byte[] storedName = readBytes(nameSize, "Unexpected end of CPIO entry name");
            if (storedName[storedName.length - 1] != 0) {
                throw new IOException("CPIO entry name is not NUL-terminated");
            }
            skipBytes(header.namePadding(), "Unexpected end of CPIO entry-name padding");
            byte[] nameBytes = Arrays.copyOf(storedName, storedName.length - 1);

            for (byte value : nameBytes) {
                if (value == 0) {
                    throw new IOException("CPIO entry name contains an embedded NUL byte");
                }
            }

            if (Arrays.equals(nameBytes, TRAILER_NAME)) {
                if (header.size() != 0L) {
                    throw new IOException("CPIO trailer has a non-empty body");
                }
                if (header.checksum() != CPIOArkivoEntryAttributes.NOT_STORED && header.checksum() != 0L) {
                    throw new IOException("CPIO trailer has a nonzero checksum");
                }
                finished = true;
                return false;
            }

            String decodedPath = decodeName(nameBytes, header);
            @Nullable String path = normalizePath(decodedPath);
            if (header.size() != 0L
                    && (CPIOPosixSupport.isDirectory(header.mode()) || CPIOPosixSupport.isOther(header.mode()))) {
                throw new IOException("CPIO non-file entry has a non-empty body");
            }
            configureCurrentBody(header);
            if (path == null) {
                if (header.size() != 0L) {
                    throw new IOException("CPIO root entry has a non-empty body");
                }
                skipCurrentEntry();
                continue;
            }

            CPIOEntryAttributes attributes = new CPIOEntryAttributes(
                    path,
                    header.dialect(),
                    header.binaryByteOrder(),
                    header.inode(),
                    header.userId(),
                    header.groupId(),
                    header.linkCount(),
                    header.mode(),
                    header.device(),
                    header.remoteDevice(),
                    header.deviceMajor(),
                    header.deviceMinor(),
                    header.remoteDeviceMajor(),
                    header.remoteDeviceMinor(),
                    header.checksum(),
                    FileTime.fromMillis(Math.multiplyExact(header.modificationTime(), 1000L)),
                    header.size()
            );
            readLimits.acceptEntry(path, header.size());
            currentAttributes = attributes;
            return true;
        }
    }

    /// Configures body accounting for one parsed entry.
    private void configureCurrentBody(Header header) {
        remainingEntryBytes = header.size();
        pendingDataPadding = header.dataPadding();
        expectedChecksum = header.checksum();
        actualChecksum = 0L;
        currentBodyOpened = false;
    }

    /// Reads the current entry attributes as a supported type.
    @Override
    protected <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException {
        ensureOpen();
        CPIOEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IllegalStateException("No current CPIO entry");
        }
        if (!type.isInstance(attributes)) {
            throw new UnsupportedOperationException("Unsupported CPIO attribute type: " + type.getName());
        }
        return type.cast(attributes);
    }

    /// Opens the current entry data as a bounded readable channel.
    @Override
    protected ReadableByteChannel openCurrentChannel() throws IOException {
        ensureOpen();
        if (currentAttributes == null) {
            throw new IllegalStateException("No current CPIO entry");
        }
        if (currentBodyOpened) {
            throw new IOException("CPIO entry body has already been opened");
        }
        currentBodyOpened = true;
        return StreamChannelAdapters.readableChannel(new EntryInputStream());
    }

    /// Closes this CPIO reader and its source.
    @Override
    protected void closeReader() throws IOException {
        if (!open && sourceClosed) {
            return;
        }
        open = false;
        currentAttributes = null;
        remainingEntryBytes = 0L;
        pendingDataPadding = 0;
        source.close();
        sourceClosed = true;
    }

    /// Reads and parses the next header, requiring the archive to end with its trailer entry.
    private Header readHeader() throws IOException {
        int first = source.read();
        if (first < 0) {
            throw new EOFException("CPIO archive ended before its trailer");
        }
        int second = source.read();
        if (second < 0) {
            throw new EOFException("Unexpected end of CPIO header magic");
        }

        if (first == 0x71 && second == 0xc7) {
            byte[] rest = readBytes(OLD_BINARY_HEADER_SIZE - 2, "Unexpected end of old binary CPIO header");
            return parseOldBinary(rest, CPIOBinaryByteOrder.BIG_ENDIAN);
        }
        if (first == 0xc7 && second == 0x71) {
            byte[] rest = readBytes(OLD_BINARY_HEADER_SIZE - 2, "Unexpected end of old binary CPIO header");
            return parseOldBinary(rest, CPIOBinaryByteOrder.LITTLE_ENDIAN);
        }

        byte[] magic = new byte[6];
        magic[0] = (byte) first;
        magic[1] = (byte) second;
        byte[] magicTail = readBytes(4, "Unexpected end of CPIO header magic");
        System.arraycopy(magicTail, 0, magic, 2, magicTail.length);
        String magicText = new String(magic, StandardCharsets.US_ASCII);
        return switch (magicText) {
            case "070701" -> parseNewAscii(
                    readBytes(NEW_ASCII_HEADER_SIZE - 6, "Unexpected end of new ASCII CPIO header"),
                    CPIODialect.NEW_ASCII
            );
            case "070702" -> parseNewAscii(
                    readBytes(NEW_ASCII_HEADER_SIZE - 6, "Unexpected end of CRC CPIO header"),
                    CPIODialect.NEW_ASCII_CRC
            );
            case "070707" -> parseOldAscii(
                    readBytes(OLD_ASCII_HEADER_SIZE - 6, "Unexpected end of old ASCII CPIO header")
            );
            default -> throw new IOException("Unrecognized CPIO header magic: " + magicText);
        };
    }

    /// Parses one new portable ASCII header after its magic.
    private static Header parseNewAscii(byte[] fields, CPIODialect dialect) throws IOException {
        long inode = parseAscii(fields, 0, 8, 16, "inode");
        int mode = requireMode(parseAscii(fields, 8, 8, 16, "mode"));
        long userId = parseAscii(fields, 16, 8, 16, "user id");
        long groupId = parseAscii(fields, 24, 8, 16, "group id");
        long linkCount = parseAscii(fields, 32, 8, 16, "link count");
        long modificationTime = parseAscii(fields, 40, 8, 16, "modification time");
        long size = parseAscii(fields, 48, 8, 16, "size");
        long deviceMajor = parseAscii(fields, 56, 8, 16, "device major");
        long deviceMinor = parseAscii(fields, 64, 8, 16, "device minor");
        long remoteDeviceMajor = parseAscii(fields, 72, 8, 16, "remote-device major");
        long remoteDeviceMinor = parseAscii(fields, 80, 8, 16, "remote-device minor");
        long nameSize = parseAscii(fields, 88, 8, 16, "name size");
        long storedChecksum = parseAscii(fields, 96, 8, 16, "checksum");
        long checksum = dialect == CPIODialect.NEW_ASCII_CRC
                ? storedChecksum
                : CPIOArkivoEntryAttributes.NOT_STORED;
        return new Header(
                dialect, null, inode, mode, userId, groupId, linkCount, modificationTime, size,
                CPIOArkivoEntryAttributes.NOT_STORED, CPIOArkivoEntryAttributes.NOT_STORED,
                deviceMajor, deviceMinor, remoteDeviceMajor, remoteDeviceMinor,
                nameSize, checksum, NEW_ASCII_HEADER_SIZE, padding(NEW_ASCII_HEADER_SIZE + nameSize, 4),
                padding(size, 4)
        );
    }

    /// Parses one old portable ASCII header after its magic.
    private static Header parseOldAscii(byte[] fields) throws IOException {
        long device = parseAscii(fields, 0, 6, 8, "device");
        long inode = parseAscii(fields, 6, 6, 8, "inode");
        int mode = requireMode(parseAscii(fields, 12, 6, 8, "mode"));
        long userId = parseAscii(fields, 18, 6, 8, "user id");
        long groupId = parseAscii(fields, 24, 6, 8, "group id");
        long linkCount = parseAscii(fields, 30, 6, 8, "link count");
        long remoteDevice = parseAscii(fields, 36, 6, 8, "remote device");
        long modificationTime = parseAscii(fields, 42, 11, 8, "modification time");
        long nameSize = parseAscii(fields, 53, 6, 8, "name size");
        long size = parseAscii(fields, 59, 11, 8, "size");
        return new Header(
                CPIODialect.OLD_ASCII, null, inode, mode, userId, groupId, linkCount, modificationTime, size,
                device, remoteDevice,
                CPIOArkivoEntryAttributes.NOT_STORED, CPIOArkivoEntryAttributes.NOT_STORED,
                CPIOArkivoEntryAttributes.NOT_STORED, CPIOArkivoEntryAttributes.NOT_STORED,
                nameSize, CPIOArkivoEntryAttributes.NOT_STORED, OLD_ASCII_HEADER_SIZE, 0, 0
        );
    }

    /// Parses one old binary header after its magic word.
    private static Header parseOldBinary(byte[] fields, CPIOBinaryByteOrder byteOrder) throws IOException {
        int offset = 0;
        long device = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long inode = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        int mode = requireMode(readBinary(fields, offset, 2, byteOrder));
        offset += 2;
        long userId = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long groupId = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long linkCount = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long remoteDevice = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long modificationTime = readBinary(fields, offset, 4, byteOrder);
        offset += 4;
        long nameSize = readBinary(fields, offset, 2, byteOrder);
        offset += 2;
        long size = readBinary(fields, offset, 4, byteOrder);
        return new Header(
                CPIODialect.OLD_BINARY, byteOrder, inode, mode, userId, groupId, linkCount, modificationTime, size,
                device, remoteDevice,
                CPIOArkivoEntryAttributes.NOT_STORED, CPIOArkivoEntryAttributes.NOT_STORED,
                CPIOArkivoEntryAttributes.NOT_STORED, CPIOArkivoEntryAttributes.NOT_STORED,
                nameSize, CPIOArkivoEntryAttributes.NOT_STORED, OLD_BINARY_HEADER_SIZE,
                padding(OLD_BINARY_HEADER_SIZE + nameSize, 2), padding(size, 2)
        );
    }

    /// Reads an unsigned old binary field composed of high-word-first 16-bit words.
    private static long readBinary(byte[] bytes, int offset, int length, CPIOBinaryByteOrder byteOrder) {
        long value = 0L;
        for (int index = 0; index < length; index += 2) {
            int word = byteOrder == CPIOBinaryByteOrder.BIG_ENDIAN
                    ? Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(bytes, offset + index))
                    : Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, offset + index));
            value = value << 16 | word;
        }
        return value;
    }

    /// Parses one strict fixed-width non-negative ASCII number.
    private static long parseAscii(
            byte[] bytes,
            int offset,
            int length,
            int radix,
            String fieldName
    ) throws IOException {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            int digit = Character.digit((char) Byte.toUnsignedInt(bytes[offset + index]), radix);
            if (digit < 0) {
                throw new IOException("Invalid CPIO " + fieldName + " field");
            }
            if (value > (Long.MAX_VALUE - digit) / radix) {
                throw new IOException("CPIO " + fieldName + " field is out of range");
            }
            value = value * radix + digit;
        }
        return value;
    }

    /// Converts a parsed mode to the public non-negative representation.
    private static int requireMode(long mode) throws IOException {
        if (mode > Integer.MAX_VALUE) {
            throw new IOException("CPIO mode field is out of range");
        }
        return (int) mode;
    }

    /// Converts a parsed name size to an allocatable NUL-terminated non-empty name length.
    private static int requireNameSize(long nameSize) throws IOException {
        if (nameSize < 2L || nameSize > Integer.MAX_VALUE) {
            throw new IOException("CPIO entry name size is out of range");
        }
        return (int) nameSize;
    }

    /// Calculates alignment bytes for a non-negative size.
    private static int padding(long size, int alignment) {
        return (int) ((alignment - size % alignment) % alignment);
    }

    /// Decodes one complete entry name through the configured basic or CPIO-specific detector.
    private String decodeName(byte[] nameBytes, Header header) throws IOException {
        @Nullable Charset detected;
        if (metadataCharsetDetector instanceof CPIOMetadataCharsetDetector cpioDetector) {
            detected = cpioDetector.detect(new CPIOMetadataCharsetDetector.Context(
                    ByteBuffer.wrap(nameBytes),
                    CPIOMetadataCharsetDetector.MetadataKind.ENTRY_NAME,
                    header.dialect(),
                    header.binaryByteOrder(),
                    header.inode(),
                    header.mode(),
                    header.size()
            ));
        } else {
            detected = metadataCharsetDetector.detect(nameBytes);
        }
        Charset charset = detected != null ? detected : StandardCharsets.UTF_8;
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Failed to decode CPIO entry name", exception);
        }
    }

    /// Normalizes a decoded path, returning `null` for the conventional root entry.
    private static @Nullable String normalizePath(String path) throws IOException {
        Objects.requireNonNull(path, "path");
        String normalized = path.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) {
            throw new IOException("CPIO entry path contains NUL");
        }
        if (normalized.startsWith("/") || normalized.length() >= 2 && normalized.charAt(1) == ':') {
            throw new IOException("CPIO entry path must be relative");
        }
        StringBuilder result = new StringBuilder();
        for (String part : normalized.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IOException("CPIO entry path must not contain ..");
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(part);
        }
        return result.isEmpty() ? null : result.toString();
    }

    /// Drains the current entry, validates its checksum, and consumes its padding.
    private void skipCurrentEntry() throws IOException {
        while (remainingEntryBytes > 0L) {
            int read = source.read(
                    discardBuffer,
                    0,
                    (int) Math.min(remainingEntryBytes, discardBuffer.length)
            );
            if (read < 0) {
                throw new EOFException("Unexpected end of CPIO entry data");
            }
            remainingEntryBytes -= read;
            if (expectedChecksum != CPIOArkivoEntryAttributes.NOT_STORED) {
                updateChecksum(discardBuffer, 0, read);
            }
        }
        finishCurrentBody();
        currentBodyOpened = false;
    }

    /// Validates the completed current body and consumes its alignment padding.
    private void finishCurrentBody() throws IOException {
        if (remainingEntryBytes != 0L) {
            return;
        }
        if (expectedChecksum != CPIOArkivoEntryAttributes.NOT_STORED) {
            if (actualChecksum != expectedChecksum) {
                throw new IOException("CPIO entry data checksum mismatch");
            }
            expectedChecksum = CPIOArkivoEntryAttributes.NOT_STORED;
        }
        while (pendingDataPadding > 0) {
            int read = source.read(discardBuffer, 0, Math.min(pendingDataPadding, discardBuffer.length));
            if (read < 0) {
                throw new EOFException("Unexpected end of CPIO entry-data padding");
            }
            pendingDataPadding -= read;
        }
    }

    /// Reads exactly one byte array of the requested size.
    private byte[] readBytes(int size, String eofMessage) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = source.read(bytes, offset, size - offset);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            offset += read;
        }
        return bytes;
    }

    /// Reads and discards exactly the requested metadata or name-padding bytes.
    private void skipBytes(long count, String eofMessage) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            int read = source.read(discardBuffer, 0, (int) Math.min(remaining, discardBuffer.length));
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            remaining -= read;
        }
    }

    /// Adds body bytes to the current unsigned 32-bit checksum.
    private void updateChecksum(byte[] bytes, int offset, int length) {
        long checksum = actualChecksum;
        for (int index = 0; index < length; index++) {
            checksum = checksum + Byte.toUnsignedInt(bytes[offset + index]) & 0xffff_ffffL;
        }
        actualChecksum = checksum;
    }

    /// Requires this reader to remain open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("CPIO streaming reader is closed");
        }
    }

    /// Stores one parsed CPIO header.
    ///
    /// @param dialect the header dialect
    /// @param binaryByteOrder the binary word byte order, or `null` for ASCII
    /// @param inode the inode number
    /// @param mode the POSIX mode bits
    /// @param userId the numeric user identifier
    /// @param groupId the numeric group identifier
    /// @param linkCount the link count
    /// @param modificationTime the modification time in epoch seconds
    /// @param size the entry data size
    /// @param device the old-format device field or `NOT_STORED`
    /// @param remoteDevice the old-format remote-device field or `NOT_STORED`
    /// @param deviceMajor the new-format device major number or `NOT_STORED`
    /// @param deviceMinor the new-format device minor number or `NOT_STORED`
    /// @param remoteDeviceMajor the new-format remote-device major number or `NOT_STORED`
    /// @param remoteDeviceMinor the new-format remote-device minor number or `NOT_STORED`
    /// @param nameSize the stored NUL-terminated name size
    /// @param checksum the stored body checksum or `NOT_STORED`
    /// @param headerSize the fixed header size
    /// @param namePadding the alignment after the stored name
    /// @param dataPadding the alignment after the entry data
    @NotNullByDefault
    private record Header(
            CPIODialect dialect,
            @Nullable CPIOBinaryByteOrder binaryByteOrder,
            long inode,
            int mode,
            long userId,
            long groupId,
            long linkCount,
            long modificationTime,
            long size,
            long device,
            long remoteDevice,
            long deviceMajor,
            long deviceMinor,
            long remoteDeviceMajor,
            long remoteDeviceMinor,
            long nameSize,
            long checksum,
            int headerSize,
            int namePadding,
            int dataPadding
    ) {
        /// Validates parsed non-negative header values and dialect-specific sentinels.
        private Header {
            Objects.requireNonNull(dialect, "dialect");
            if (inode < 0L || mode < 0 || userId < 0L || groupId < 0L || linkCount < 0L
                    || modificationTime < 0L || size < 0L
                    || headerSize <= 0 || namePadding < 0 || dataPadding < 0) {
                throw new IllegalArgumentException("Invalid parsed CPIO header");
            }
        }
    }

    /// Reads bytes from the current bounded entry body.
    @NotNullByDefault
    private final class EntryInputStream extends InputStream {
        /// Reusable storage for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// Whether this body stream has been closed.
        private boolean closed;

        /// Reads one entry-data byte.
        @Override
        public int read() throws IOException {
            int read = read(singleByte, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads entry-data bytes and accounts for their checksum.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) {
                throw new IOException("CPIO entry input stream is closed");
            }
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            if (remainingEntryBytes == 0L) {
                finishCurrentBody();
                return -1;
            }
            int read = source.read(bytes, offset, (int) Math.min(length, remainingEntryBytes));
            if (read < 0) {
                throw new EOFException("Unexpected end of CPIO entry data");
            }
            remainingEntryBytes -= read;
            if (expectedChecksum != CPIOArkivoEntryAttributes.NOT_STORED) {
                updateChecksum(bytes, offset, read);
            }
            if (remainingEntryBytes == 0L) {
                finishCurrentBody();
            }
            return read;
        }

        /// Closes this body stream after draining and validating the entry data.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            skipCurrentEntry();
            closed = true;
        }
    }
}
