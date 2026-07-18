// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.cpio.CPIOArchiveOptions;
import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributeView;
import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributes;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingWriter;
import org.glavo.arkivo.archive.cpio.CPIOBinaryByteOrder;
import org.glavo.arkivo.archive.cpio.CPIODialect;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/// Implements forward-only CPIO writing for every standardized header dialect.
@NotNullByDefault
public final class CPIOArkivoStreamingWriterImpl extends CPIOArkivoStreamingWriter {
    /// The new portable ASCII header size in bytes.
    private static final int NEW_ASCII_HEADER_SIZE = 110;

    /// The old portable ASCII header size in bytes.
    private static final int OLD_ASCII_HEADER_SIZE = 76;

    /// The old binary header size in bytes.
    private static final int OLD_BINARY_HEADER_SIZE = 26;

    /// The default regular-file mode.
    private static final int DEFAULT_FILE_MODE = 0100644;

    /// The default directory mode.
    private static final int DEFAULT_DIRECTORY_MODE = 040755;

    /// The default symbolic-link mode.
    private static final int DEFAULT_SYMBOLIC_LINK_MODE = 0120777;

    /// The largest unsigned 16-bit field value.
    private static final long MAX_UNSIGNED_SHORT = 0xffffL;

    /// The largest unsigned 32-bit field value.
    private static final long MAX_UNSIGNED_INT = 0xffff_ffffL;

    /// The largest six-digit octal field value.
    private static final long MAX_SIX_DIGIT_OCTAL = 0777777L;

    /// The largest eleven-digit octal field value.
    private static final long MAX_ELEVEN_DIGIT_OCTAL = 077777777777L;

    /// The CPIO end-of-archive entry name.
    private static final String TRAILER_NAME = "TRAILER!!!";

    /// The empty body shared by directory entries and empty regular files.
    private static final byte @Unmodifiable [] EMPTY_BODY = new byte[0];

    /// The backing archive output stream.
    private final OutputStream target;

    /// The configured output header dialect.
    private final CPIODialect dialect;

    /// The configured old binary word byte order.
    private final CPIOBinaryByteOrder binaryByteOrder;

    /// The charset used to encode entry names and symbolic-link targets.
    private final Charset metadataCharset;

    /// The archive block size used for final padding.
    private final int blockSize;

    /// The storage used to stage entry data.
    private final ArkivoEditStorage bodyStorage;

    /// Stored bodies whose first cleanup attempt failed.
    private final ArrayList<ArkivoStoredContent> retiredBodies = new ArrayList<>();

    /// Whether this writer accepts new entry operations.
    private boolean open = true;

    /// Whether the trailer and final block padding were written.
    private boolean archiveFinished;

    /// Whether the backing output stream was closed.
    private boolean targetClosed;

    /// Whether the body storage was closed.
    private boolean bodyStorageClosed;

    /// The terminal output failure that prevents any further archive bytes from being emitted.
    private @Nullable IOException outputFailure;

    /// The number of bytes successfully written to the archive stream.
    private long archiveSize;

    /// The inode assigned to the next entry that keeps its default inode.
    private long nextInode = 1L;

    /// The entry waiting for metadata completion or body opening.
    private @Nullable PendingEntry pendingEntry;

    /// The currently open staged body stream.
    private @Nullable StoredEntryBodyOutputStream currentBody;

    /// Creates a configured streaming CPIO writer.
    public CPIOArkivoStreamingWriterImpl(OutputStream target, CPIOArchiveOptions.Create options) {
        this.target = Objects.requireNonNull(target, "target");
        CPIOArchiveOptions.Create checkedOptions = Objects.requireNonNull(options, "options");
        this.dialect = checkedOptions.dialect();
        this.binaryByteOrder = checkedOptions.binaryByteOrder();
        this.metadataCharset = checkedOptions.metadataCharset();
        this.blockSize = checkedOptions.blockSize();
        @Nullable ArkivoEditStorage configuredStorage = checkedOptions.common().editStorage();
        this.bodyStorage = configuredStorage != null
                ? configuredStorage
                : ArkivoEditStorage.temporaryFiles(defaultBodyStorageDirectory());
    }

    /// Returns the directory used by default temporary-file body storage.
    private static Path defaultBodyStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Begins a pending regular-file entry.
    @Override
    protected void beginFileEntry(String path) throws IOException {
        beginEntry(path, EntryKind.REGULAR_FILE, DEFAULT_FILE_MODE, null);
    }

    /// Begins a pending directory entry with an empty body.
    @Override
    protected void beginDirectoryEntry(String path) throws IOException {
        beginEntry(path, EntryKind.DIRECTORY, DEFAULT_DIRECTORY_MODE, EMPTY_BODY);
    }

    /// Begins a pending symbolic-link entry whose data stores its target.
    @Override
    protected void beginSymbolicLinkEntry(String path, String targetPath) throws IOException {
        Objects.requireNonNull(targetPath, "targetPath");
        if (targetPath.isEmpty()) {
            throw new IllegalArgumentException("CPIO symbolic-link target is empty");
        }
        if (targetPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("CPIO symbolic-link target contains NUL");
        }
        beginEntry(
                path,
                EntryKind.SYMBOLIC_LINK,
                DEFAULT_SYMBOLIC_LINK_MODE,
                encodeMetadata(targetPath, "symbolic-link target")
        );
    }

    /// Creates one pending entry after validating writer state and path text.
    private void beginEntry(String path, EntryKind kind, int mode, byte @Nullable [] fixedBody) throws IOException {
        ensureOpen();
        if (pendingEntry != null) {
            throw new IllegalStateException("A CPIO streaming entry is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("A CPIO streaming entry body is still open");
        }
        String normalizedPath = entryPathText(path);
        if (nextInode > maximumInode()) {
            throw new IOException("CPIO inode space is exhausted for the configured dialect");
        }
        long inode = nextInode++;
        pendingEntry = new PendingEntry(normalizedPath, kind, mode, inode, fixedBody);
    }

    /// Returns the largest inode representable by the configured dialect.
    private long maximumInode() {
        return switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> MAX_UNSIGNED_INT;
            case OLD_ASCII -> MAX_SIX_DIGIT_OCTAL;
            case OLD_BINARY -> MAX_UNSIGNED_SHORT;
        };
    }

    /// Returns one supported pending entry attribute view.
    @Override
    protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        PendingEntry entry = requirePendingEntry();
        if (type == BasicFileAttributeView.class || type == CPIOArkivoEntryAttributeView.class) {
            return type.cast(entry.attributes);
        }
        return null;
    }

    /// Commits the pending entry with its fixed body or an empty regular-file body.
    @Override
    protected void finishCurrentEntry() throws IOException {
        ensureOpen();
        PendingEntry entry = requirePendingEntry();
        entry.ensurePending();
        byte @Unmodifiable [] body = entry.fixedBodyOrEmpty();
        ensureBodySize(entry, body.length);
        writeEntry(entry, body, checksum(body));
        pendingEntry = null;
    }

    /// Opens a writable channel that stages and commits the pending regular-file body.
    @Override
    protected WritableByteChannel openCurrentChannel() throws IOException {
        ensureOpen();
        PendingEntry entry = requirePendingEntry();
        entry.ensurePending();
        if (entry.kind != EntryKind.REGULAR_FILE) {
            throw new IllegalStateException("CPIO " + entry.kind.description + " entries cannot open a body stream");
        }
        StoredEntryBodyOutputStream body = new StoredEntryBodyOutputStream(entry);
        pendingEntry = null;
        currentBody = body;
        return StreamChannelAdapters.writableChannel(body);
    }

    /// Finishes the archive and closes its output and staging storage.
    @Override
    protected void closeWriter() throws IOException {
        if (!open && targetClosed && bodyStorageClosed && retiredBodies.isEmpty()) {
            return;
        }

        @Nullable IOException failure = outputFailure;
        if (!archiveFinished && outputFailure == null) {
            try {
                writeTrailerAndPadding();
                archiveFinished = true;
            } catch (IOException exception) {
                failure = exception;
            }
        }
        open = false;
        if (!targetClosed) {
            try {
                target.close();
                targetClosed = true;
            } catch (IOException exception) {
                failure = combine(failure, exception);
            }
        }
        failure = closeBodyStorage(failure);
        if (failure != null) {
            throw failure;
        }
    }

    /// Writes the format trailer and zero padding to the configured archive block boundary.
    private void writeTrailerAndPadding() throws IOException {
        byte[] nameBytes = TRAILER_NAME.getBytes(StandardCharsets.US_ASCII);
        writeHeader(
                nameBytes,
                0L,
                0,
                0L,
                0L,
                1L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
        );
        writeName(nameBytes);
        writePadding(namePadding(nameBytes.length + 1L));
        long finalPadding = (blockSize - archiveSize % blockSize) % blockSize;
        writePadding(finalPadding);
    }

    /// Writes one complete fixed-body entry.
    private void writeEntry(PendingEntry entry, byte[] body, long checksum) throws IOException {
        byte[] nameBytes = encodeMetadata(entry.path, "entry name");
        PendingCPIOEntryAttributeView attributes = entry.attributes;
        writeHeader(
                nameBytes,
                attributes.inode,
                attributes.mode,
                attributes.userId,
                attributes.groupId,
                attributes.linkCount,
                timestampSeconds(attributes.lastModifiedTime),
                body.length,
                attributes.device,
                attributes.remoteDevice,
                attributes.deviceMajor,
                attributes.deviceMinor,
                attributes.remoteDeviceMajor,
                attributes.remoteDeviceMinor,
                checksum
        );
        writeName(nameBytes);
        writePadding(namePadding(nameBytes.length + 1L));
        writeBytes(body);
        writePadding(dataPadding(body.length));
        entry.committed = true;
    }

    /// Writes one complete staged-body entry from a bounded readable channel.
    private void writeStoredEntry(
            PendingEntry entry,
            ReadableByteChannel body,
            long bodySize,
            long checksum
    ) throws IOException {
        byte[] nameBytes = encodeMetadata(entry.path, "entry name");
        PendingCPIOEntryAttributeView attributes = entry.attributes;
        writeHeader(
                nameBytes,
                attributes.inode,
                attributes.mode,
                attributes.userId,
                attributes.groupId,
                attributes.linkCount,
                timestampSeconds(attributes.lastModifiedTime),
                bodySize,
                attributes.device,
                attributes.remoteDevice,
                attributes.deviceMajor,
                attributes.deviceMinor,
                attributes.remoteDeviceMajor,
                attributes.remoteDeviceMinor,
                checksum
        );
        writeName(nameBytes);
        writePadding(namePadding(nameBytes.length + 1L));
        copyBody(body, bodySize);
        writePadding(dataPadding(bodySize));
        entry.committed = true;
    }

    /// Writes a dialect-specific CPIO header after validating all representable fields.
    private void writeHeader(
            byte[] nameBytes,
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
            long checksum
    ) throws IOException {
        long nameSize = (long) nameBytes.length + 1L;
        byte[] header = switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> createNewAsciiHeader(
                    inode, mode, userId, groupId, linkCount, modificationTime, size,
                    deviceMajor, deviceMinor, remoteDeviceMajor, remoteDeviceMinor, nameSize, checksum
            );
            case OLD_ASCII -> createOldAsciiHeader(
                    device, inode, mode, userId, groupId, linkCount, remoteDevice,
                    modificationTime, nameSize, size
            );
            case OLD_BINARY -> createOldBinaryHeader(
                    device, inode, mode, userId, groupId, linkCount, remoteDevice,
                    modificationTime, nameSize, size
            );
        };
        writeBytes(header);
    }

    /// Creates one new portable ASCII header.
    private byte[] createNewAsciiHeader(
            long inode,
            int mode,
            long userId,
            long groupId,
            long linkCount,
            long modificationTime,
            long size,
            long deviceMajor,
            long deviceMinor,
            long remoteDeviceMajor,
            long remoteDeviceMinor,
            long nameSize,
            long checksum
    ) throws IOException {
        requireField(inode, MAX_UNSIGNED_INT, "inode");
        requireField(mode, MAX_UNSIGNED_INT, "mode");
        requireField(userId, MAX_UNSIGNED_INT, "user id");
        requireField(groupId, MAX_UNSIGNED_INT, "group id");
        requireField(linkCount, MAX_UNSIGNED_INT, "link count");
        requireField(modificationTime, MAX_UNSIGNED_INT, "modification time");
        requireField(size, MAX_UNSIGNED_INT, "size");
        requireField(deviceMajor, MAX_UNSIGNED_INT, "device major");
        requireField(deviceMinor, MAX_UNSIGNED_INT, "device minor");
        requireField(remoteDeviceMajor, MAX_UNSIGNED_INT, "remote-device major");
        requireField(remoteDeviceMinor, MAX_UNSIGNED_INT, "remote-device minor");
        requireField(nameSize, MAX_UNSIGNED_INT, "name size");
        requireField(checksum, MAX_UNSIGNED_INT, "checksum");

        byte[] header = new byte[NEW_ASCII_HEADER_SIZE];
        int offset = writeAscii(header, 0, dialect == CPIODialect.NEW_ASCII_CRC ? "070702" : "070701");
        offset = writeAsciiField(header, offset, inode, 8, 16, MAX_UNSIGNED_INT, "inode");
        offset = writeAsciiField(header, offset, mode, 8, 16, MAX_UNSIGNED_INT, "mode");
        offset = writeAsciiField(header, offset, userId, 8, 16, MAX_UNSIGNED_INT, "user id");
        offset = writeAsciiField(header, offset, groupId, 8, 16, MAX_UNSIGNED_INT, "group id");
        offset = writeAsciiField(header, offset, linkCount, 8, 16, MAX_UNSIGNED_INT, "link count");
        offset = writeAsciiField(header, offset, modificationTime, 8, 16, MAX_UNSIGNED_INT, "modification time");
        offset = writeAsciiField(header, offset, size, 8, 16, MAX_UNSIGNED_INT, "size");
        offset = writeAsciiField(header, offset, deviceMajor, 8, 16, MAX_UNSIGNED_INT, "device major");
        offset = writeAsciiField(header, offset, deviceMinor, 8, 16, MAX_UNSIGNED_INT, "device minor");
        offset = writeAsciiField(header, offset, remoteDeviceMajor, 8, 16, MAX_UNSIGNED_INT, "remote-device major");
        offset = writeAsciiField(header, offset, remoteDeviceMinor, 8, 16, MAX_UNSIGNED_INT, "remote-device minor");
        offset = writeAsciiField(header, offset, nameSize, 8, 16, MAX_UNSIGNED_INT, "name size");
        offset = writeAsciiField(
                header, offset, dialect == CPIODialect.NEW_ASCII_CRC ? checksum : 0L,
                8, 16, MAX_UNSIGNED_INT, "checksum"
        );
        return completeHeader(header, offset);
    }

    /// Creates one old portable ASCII header.
    private static byte[] createOldAsciiHeader(
            long device,
            long inode,
            int mode,
            long userId,
            long groupId,
            long linkCount,
            long remoteDevice,
            long modificationTime,
            long nameSize,
            long size
    ) throws IOException {
        requireField(device, MAX_SIX_DIGIT_OCTAL, "device");
        requireField(inode, MAX_SIX_DIGIT_OCTAL, "inode");
        requireField(mode, MAX_SIX_DIGIT_OCTAL, "mode");
        requireField(userId, MAX_SIX_DIGIT_OCTAL, "user id");
        requireField(groupId, MAX_SIX_DIGIT_OCTAL, "group id");
        requireField(linkCount, MAX_SIX_DIGIT_OCTAL, "link count");
        requireField(remoteDevice, MAX_SIX_DIGIT_OCTAL, "remote device");
        requireField(modificationTime, MAX_ELEVEN_DIGIT_OCTAL, "modification time");
        requireField(nameSize, MAX_SIX_DIGIT_OCTAL, "name size");
        requireField(size, MAX_ELEVEN_DIGIT_OCTAL, "size");

        byte[] header = new byte[OLD_ASCII_HEADER_SIZE];
        int offset = writeAscii(header, 0, "070707");
        offset = writeAsciiField(header, offset, device, 6, 8, MAX_SIX_DIGIT_OCTAL, "device");
        offset = writeAsciiField(header, offset, inode, 6, 8, MAX_SIX_DIGIT_OCTAL, "inode");
        offset = writeAsciiField(header, offset, mode, 6, 8, MAX_SIX_DIGIT_OCTAL, "mode");
        offset = writeAsciiField(header, offset, userId, 6, 8, MAX_SIX_DIGIT_OCTAL, "user id");
        offset = writeAsciiField(header, offset, groupId, 6, 8, MAX_SIX_DIGIT_OCTAL, "group id");
        offset = writeAsciiField(header, offset, linkCount, 6, 8, MAX_SIX_DIGIT_OCTAL, "link count");
        offset = writeAsciiField(header, offset, remoteDevice, 6, 8, MAX_SIX_DIGIT_OCTAL, "remote device");
        offset = writeAsciiField(
                header, offset, modificationTime, 11, 8, MAX_ELEVEN_DIGIT_OCTAL, "modification time"
        );
        offset = writeAsciiField(header, offset, nameSize, 6, 8, MAX_SIX_DIGIT_OCTAL, "name size");
        offset = writeAsciiField(header, offset, size, 11, 8, MAX_ELEVEN_DIGIT_OCTAL, "size");
        return completeHeader(header, offset);
    }

    /// Creates one old binary header.
    private byte[] createOldBinaryHeader(
            long device,
            long inode,
            int mode,
            long userId,
            long groupId,
            long linkCount,
            long remoteDevice,
            long modificationTime,
            long nameSize,
            long size
    ) throws IOException {
        requireField(device, MAX_UNSIGNED_SHORT, "device");
        requireField(inode, MAX_UNSIGNED_SHORT, "inode");
        requireField(mode, MAX_UNSIGNED_SHORT, "mode");
        requireField(userId, MAX_UNSIGNED_SHORT, "user id");
        requireField(groupId, MAX_UNSIGNED_SHORT, "group id");
        requireField(linkCount, MAX_UNSIGNED_SHORT, "link count");
        requireField(remoteDevice, MAX_UNSIGNED_SHORT, "remote device");
        requireField(modificationTime, MAX_UNSIGNED_INT, "modification time");
        requireField(nameSize, MAX_UNSIGNED_SHORT, "name size");
        requireField(size, MAX_UNSIGNED_INT, "size");

        byte[] header = new byte[OLD_BINARY_HEADER_SIZE];
        int offset = writeBinaryField(header, 0, 070707, 2, MAX_UNSIGNED_SHORT, "magic");
        offset = writeBinaryField(header, offset, device, 2, MAX_UNSIGNED_SHORT, "device");
        offset = writeBinaryField(header, offset, inode, 2, MAX_UNSIGNED_SHORT, "inode");
        offset = writeBinaryField(header, offset, mode, 2, MAX_UNSIGNED_SHORT, "mode");
        offset = writeBinaryField(header, offset, userId, 2, MAX_UNSIGNED_SHORT, "user id");
        offset = writeBinaryField(header, offset, groupId, 2, MAX_UNSIGNED_SHORT, "group id");
        offset = writeBinaryField(header, offset, linkCount, 2, MAX_UNSIGNED_SHORT, "link count");
        offset = writeBinaryField(header, offset, remoteDevice, 2, MAX_UNSIGNED_SHORT, "remote device");
        offset = writeBinaryField(header, offset, modificationTime, 4, MAX_UNSIGNED_INT, "modification time");
        offset = writeBinaryField(header, offset, nameSize, 2, MAX_UNSIGNED_SHORT, "name size");
        offset = writeBinaryField(header, offset, size, 4, MAX_UNSIGNED_INT, "size");
        return completeHeader(header, offset);
    }

    /// Writes one zero-padded fixed-width ASCII numeric field into a header buffer.
    private static int writeAsciiField(
            byte[] target,
            int offset,
            long value,
            int width,
            int radix,
            long maximum,
            String fieldName
    ) throws IOException {
        requireField(value, maximum, fieldName);
        String text = Long.toString(value, radix);
        for (int index = text.length(); index < width; index++) {
            target[offset++] = '0';
        }
        return writeAscii(target, offset, text);
    }

    /// Writes one unsigned old binary field as high-word-first 16-bit words into a header buffer.
    private int writeBinaryField(
            byte[] target,
            int offset,
            long value,
            int width,
            long maximum,
            String fieldName
    ) throws IOException {
        requireField(value, maximum, fieldName);
        for (int shift = (width - 2) * 8; shift >= 0; shift -= 16) {
            short word = (short) (value >>> shift);
            if (binaryByteOrder == CPIOBinaryByteOrder.BIG_ENDIAN) {
                ByteArrayAccess.writeShortBigEndian(target, offset, word);
            } else {
                ByteArrayAccess.writeShortLittleEndian(target, offset, word);
            }
            offset += Short.BYTES;
        }
        return offset;
    }

    /// Returns a completed header after verifying its fixed layout was filled exactly.
    private static byte[] completeHeader(byte[] header, int offset) {
        if (offset != header.length) {
            throw new AssertionError("CPIO header layout produced " + offset + " of " + header.length + " bytes");
        }
        return header;
    }

    /// Requires a numeric field to fit its dialect-specific unsigned range.
    private static void requireField(long value, long maximum, String fieldName) throws IOException {
        if (value < 0L || value > maximum) {
            throw new IOException("CPIO " + fieldName + " field is out of range");
        }
    }

    /// Writes US-ASCII header text into a header buffer.
    private static int writeAscii(byte[] target, int offset, String text) throws IOException {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character > 0x7f) {
                throw new IOException("CPIO header text is not US-ASCII");
            }
            target[offset++] = (byte) character;
        }
        return offset;
    }

    /// Writes an encoded name followed by its one-byte terminator.
    private void writeName(byte[] nameBytes) throws IOException {
        writeBytes(nameBytes);
        writeByte(0);
    }

    /// Copies exactly one staged entry body to the archive output.
    private void copyBody(ReadableByteChannel input, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = size;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(remaining, buffer.capacity()));
            int read = input.read(buffer);
            if (read < 0) {
                throw new IOException("Staged CPIO entry body ended before its declared size");
            }
            if (read == 0) {
                continue;
            }
            writeBytes(buffer.array(), 0, read);
            remaining -= read;
        }
    }

    /// Writes zero padding bytes.
    private void writePadding(long count) throws IOException {
        for (long index = 0L; index < count; index++) {
            writeByte(0);
        }
    }

    /// Returns the padding after one stored name for the configured dialect.
    private int namePadding(long nameSize) {
        return switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> padding(110L + nameSize, 4);
            case OLD_ASCII -> 0;
            case OLD_BINARY -> padding(26L + nameSize, 2);
        };
    }

    /// Returns the padding after one entry body for the configured dialect.
    private int dataPadding(long bodySize) {
        return switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> padding(bodySize, 4);
            case OLD_ASCII -> 0;
            case OLD_BINARY -> padding(bodySize, 2);
        };
    }

    /// Calculates alignment bytes for a non-negative size.
    private static int padding(long size, int alignment) {
        return (int) ((alignment - size % alignment) % alignment);
    }

    /// Encodes one CPIO metadata value strictly with the configured charset.
    private byte[] encodeMetadata(String value, String description) throws IOException {
        if (value.indexOf('\0') >= 0) {
            throw new IOException("CPIO " + description + " contains NUL");
        }
        try {
            ByteBuffer buffer = metadataCharset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            for (byte encoded : bytes) {
                if (encoded == 0) {
                    throw new IOException("Encoded CPIO " + description + " contains a NUL byte");
                }
            }
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IOException("Failed to encode CPIO " + description, exception);
        }
    }

    /// Calculates the unsigned 32-bit byte sum required by the CRC dialect.
    private long checksum(byte[] body) {
        if (dialect != CPIODialect.NEW_ASCII_CRC) {
            return 0L;
        }
        long checksum = 0L;
        for (byte value : body) {
            checksum = checksum + Byte.toUnsignedInt(value) & MAX_UNSIGNED_INT;
        }
        return checksum;
    }

    /// Writes one byte and accounts for the physical archive size.
    private void writeByte(int value) throws IOException {
        requireWritableOutput();
        try {
            target.write(value);
        } catch (IOException exception) {
            outputFailure = exception;
            throw exception;
        }
        archiveSize++;
    }

    /// Writes a complete byte array and accounts for the physical archive size.
    private void writeBytes(byte[] bytes) throws IOException {
        writeBytes(bytes, 0, bytes.length);
    }

    /// Writes byte-array content and accounts for the physical archive size.
    private void writeBytes(byte[] bytes, int offset, int length) throws IOException {
        requireWritableOutput();
        try {
            target.write(bytes, offset, length);
        } catch (IOException exception) {
            outputFailure = exception;
            throw exception;
        }
        archiveSize = Math.addExact(archiveSize, length);
    }

    /// Requires the forward-only target not to have failed during an earlier write.
    private void requireWritableOutput() throws IOException {
        @Nullable IOException failure = outputFailure;
        if (failure != null) {
            throw failure;
        }
    }

    /// Returns the current pending entry.
    private PendingEntry requirePendingEntry() {
        PendingEntry entry = pendingEntry;
        if (entry == null) {
            throw new IllegalStateException("No CPIO streaming entry is pending");
        }
        return entry;
    }

    /// Requires an entry's actual body size to match its configured size.
    private static void ensureBodySize(PendingEntry entry, long actualSize) throws IOException {
        long expectedSize = entry.attributes.expectedSize;
        if (expectedSize != ArkivoEditStorage.UNKNOWN_SIZE && expectedSize != actualSize) {
            throw new IOException("CPIO entry body size does not match configured size for " + entry.path);
        }
    }

    /// Converts a modification time to non-negative epoch seconds.
    private static long timestampSeconds(FileTime time) throws IOException {
        long seconds = time.toInstant().getEpochSecond();
        if (seconds < 0L) {
            throw new IOException("CPIO modification time must not be negative");
        }
        return seconds;
    }

    /// Converts one logical entry path to normalized archive-local text.
    private static String entryPathText(String path) {
        Objects.requireNonNull(path, "path");
        String normalized = path.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("CPIO streaming entry path contains NUL");
        }
        if (normalized.startsWith("/") || normalized.length() >= 2 && normalized.charAt(1) == ':') {
            throw new IllegalArgumentException("CPIO streaming entry paths must be relative");
        }
        StringBuilder result = new StringBuilder();
        for (String part : normalized.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("CPIO streaming entry paths must not contain ..");
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(part);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("CPIO streaming entry path is empty");
        }
        String entryPath = result.toString();
        if (TRAILER_NAME.equals(entryPath)) {
            throw new IllegalArgumentException("CPIO streaming entry path is reserved: " + TRAILER_NAME);
        }
        return entryPath;
    }

    /// Returns the maximum body size representable by the configured dialect.
    private long maximumBodySize() {
        return dialect == CPIODialect.OLD_ASCII ? MAX_ELEVEN_DIGIT_OCTAL : MAX_UNSIGNED_INT;
    }

    /// Releases staged content immediately or retains it for close-time cleanup retry.
    private void releaseBody(ArkivoStoredContent content) {
        try {
            content.close();
        } catch (IOException exception) {
            retiredBodies.add(content);
        }
    }

    /// Closes retained staged bodies and their owning storage.
    private @Nullable IOException closeBodyStorage(@Nullable IOException failure) {
        var iterator = retiredBodies.iterator();
        while (iterator.hasNext()) {
            ArkivoStoredContent content = iterator.next();
            try {
                content.close();
                iterator.remove();
            } catch (IOException exception) {
                failure = combine(failure, exception);
            }
        }
        if (!bodyStorageClosed) {
            try {
                bodyStorage.close();
                bodyStorageClosed = true;
            } catch (IOException exception) {
                failure = combine(failure, exception);
            }
        }
        return failure;
    }

    /// Combines one cleanup failure with an earlier failure.
    private static IOException combine(@Nullable IOException failure, IOException next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    /// Requires this writer to remain open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("CPIO streaming writer is closed");
        }
        requireWritableOutput();
    }

    /// Describes the logical kind of a pending entry.
    @NotNullByDefault
    private enum EntryKind {
        /// A regular file with a caller-provided body.
        REGULAR_FILE("regular file"),

        /// A directory with an empty body.
        DIRECTORY("directory"),

        /// A symbolic link whose target is stored as its body.
        SYMBOLIC_LINK("symbolic link");

        /// The diagnostic description of this entry kind.
        private final String description;

        /// Creates an entry kind.
        EntryKind(String description) {
            this.description = Objects.requireNonNull(description, "description");
        }
    }

    /// Stores one pending CPIO entry and its mutable attributes.
    @NotNullByDefault
    private final class PendingEntry {
        /// The normalized archive-local entry path.
        private final String path;

        /// The logical entry kind.
        private final EntryKind kind;

        /// The mutable pending attributes.
        private final PendingCPIOEntryAttributeView attributes;

        /// The fixed body for a directory or symbolic link, or `null` for a regular file.
        private final byte @Nullable @Unmodifiable [] fixedBody;

        /// Whether this entry's header has been written.
        private boolean committed;

        /// Creates one pending entry.
        private PendingEntry(String path, EntryKind kind, int mode, long inode, byte @Nullable [] fixedBody) {
            this.path = Objects.requireNonNull(path, "path");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.attributes = new PendingCPIOEntryAttributeView(this, mode, inode);
            this.fixedBody = fixedBody != null ? fixedBody.clone() : null;
        }

        /// Requires this entry to remain pending and configurable.
        private void ensurePending() {
            if (committed || pendingEntry != this) {
                throw new IllegalStateException("CPIO streaming entry has already been committed");
            }
        }

        /// Returns the fixed body or an empty regular-file body.
        private byte @Unmodifiable [] fixedBodyOrEmpty() {
            byte @Nullable @Unmodifiable [] body = fixedBody;
            return body != null ? body : EMPTY_BODY;
        }

        /// Returns the fixed body size, or zero for a regular file.
        private long fixedBodySize() {
            byte @Nullable @Unmodifiable [] body = fixedBody;
            return body != null ? body.length : 0L;
        }
    }

    /// Configures metadata for one pending CPIO entry.
    @NotNullByDefault
    private final class PendingCPIOEntryAttributeView implements CPIOArkivoEntryAttributeView {
        /// The pending entry configured by this view.
        private final PendingEntry entry;

        /// The modification time.
        private FileTime lastModifiedTime = FileTime.fromMillis(0L);

        /// The inode number.
        private long inode;

        /// The numeric user identifier.
        private long userId;

        /// The numeric group identifier.
        private long groupId;

        /// The link count.
        private long linkCount = 1L;

        /// The POSIX mode and type bits.
        private int mode;

        /// The old-format device field.
        private long device;

        /// The old-format remote-device field.
        private long remoteDevice;

        /// The new-format device major number.
        private long deviceMajor;

        /// The new-format device minor number.
        private long deviceMinor;

        /// The new-format remote-device major number.
        private long remoteDeviceMajor;

        /// The new-format remote-device minor number.
        private long remoteDeviceMinor;

        /// The configured expected body size, or the unknown-size sentinel.
        private long expectedSize = ArkivoEditStorage.UNKNOWN_SIZE;

        /// Creates one pending metadata view.
        private PendingCPIOEntryAttributeView(PendingEntry entry, int mode, long inode) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.mode = mode;
            this.inode = inode;
        }

        /// Reads an immutable snapshot of pending CPIO metadata.
        @Override
        public CPIOArkivoEntryAttributes readAttributes() {
            return new CPIOEntryAttributes(
                    entry.path,
                    dialect,
                    dialect == CPIODialect.OLD_BINARY ? binaryByteOrder : null,
                    inode,
                    userId,
                    groupId,
                    linkCount,
                    mode,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? CPIOArkivoEntryAttributes.NOT_STORED : device,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? CPIOArkivoEntryAttributes.NOT_STORED : remoteDevice,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? deviceMajor : CPIOArkivoEntryAttributes.NOT_STORED,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? deviceMinor : CPIOArkivoEntryAttributes.NOT_STORED,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? remoteDeviceMajor : CPIOArkivoEntryAttributes.NOT_STORED,
                    dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC
                            ? remoteDeviceMinor : CPIOArkivoEntryAttributes.NOT_STORED,
                    dialect == CPIODialect.NEW_ASCII_CRC
                            ? CPIOArkivoEntryAttributes.UNKNOWN_CHECKSUM
                            : CPIOArkivoEntryAttributes.NOT_STORED,
                    lastModifiedTime,
                    visibleSize()
            );
        }

        /// Sets the modification time and ignores unsupported access and creation times.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            entry.ensurePending();
            if (lastModifiedTime != null) {
                this.lastModifiedTime = lastModifiedTime;
            }
        }

        /// Sets the inode number.
        @Override
        public void setInode(long inode) {
            requireNonNegative(inode, "inode");
            entry.ensurePending();
            this.inode = inode;
        }

        /// Sets the numeric user identifier.
        @Override
        public void setUserId(long userId) {
            requireNonNegative(userId, "userId");
            entry.ensurePending();
            this.userId = userId;
        }

        /// Sets the numeric group identifier.
        @Override
        public void setGroupId(long groupId) {
            requireNonNegative(groupId, "groupId");
            entry.ensurePending();
            this.groupId = groupId;
        }

        /// Sets the positive link count.
        @Override
        public void setLinkCount(long linkCount) {
            if (linkCount <= 0L) {
                throw new IllegalArgumentException("linkCount must be positive");
            }
            entry.ensurePending();
            this.linkCount = linkCount;
        }

        /// Sets the POSIX mode while preserving the entry kind selected by its begin operation.
        @Override
        public void setMode(int mode) {
            if (mode < 0) {
                throw new IllegalArgumentException("mode must not be negative");
            }
            entry.ensurePending();
            if (entry.kind == EntryKind.REGULAR_FILE && !CPIOPosixSupport.isRegularFile(mode)) {
                throw new IllegalArgumentException("mode must describe a CPIO regular file");
            }
            if (entry.kind == EntryKind.DIRECTORY && !CPIOPosixSupport.isDirectory(mode)) {
                throw new IllegalArgumentException("mode must describe a CPIO directory");
            }
            if (entry.kind == EntryKind.SYMBOLIC_LINK && !CPIOPosixSupport.isSymbolicLink(mode)) {
                throw new IllegalArgumentException("mode must describe a CPIO symbolic link");
            }
            this.mode = mode;
        }

        /// Sets the old-format device field.
        @Override
        public void setDevice(long device) {
            requireNonNegative(device, "device");
            entry.ensurePending();
            this.device = device;
        }

        /// Sets the old-format remote-device field.
        @Override
        public void setRemoteDevice(long remoteDevice) {
            requireNonNegative(remoteDevice, "remoteDevice");
            entry.ensurePending();
            this.remoteDevice = remoteDevice;
        }

        /// Sets the new-format device major and minor numbers.
        @Override
        public void setDeviceNumbers(long major, long minor) {
            requireNonNegative(major, "major");
            requireNonNegative(minor, "minor");
            entry.ensurePending();
            this.deviceMajor = major;
            this.deviceMinor = minor;
        }

        /// Sets the new-format remote-device major and minor numbers.
        @Override
        public void setRemoteDeviceNumbers(long major, long minor) {
            requireNonNegative(major, "major");
            requireNonNegative(minor, "minor");
            entry.ensurePending();
            this.remoteDeviceMajor = major;
            this.remoteDeviceMinor = minor;
        }

        /// Sets the expected body size.
        @Override
        public void setSize(long size) throws IOException {
            requireNonNegative(size, "size");
            entry.ensurePending();
            if (size > maximumBodySize()) {
                throw new IOException("CPIO size field is out of range");
            }
            if (entry.fixedBody != null && size != entry.fixedBodySize()) {
                throw new IOException("CPIO entry body size does not match configured size for " + entry.path);
            }
            expectedSize = size;
        }

        /// Requires a configurable numeric value to be non-negative.
        private static void requireNonNegative(long value, String name) {
            if (value < 0L) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }

        /// Returns the pending body size visible through an attribute snapshot.
        private long visibleSize() {
            if (expectedSize != ArkivoEditStorage.UNKNOWN_SIZE) {
                return expectedSize;
            }
            return entry.fixedBody != null
                    ? entry.fixedBodySize()
                    : CPIOArkivoEntryAttributes.UNKNOWN_SIZE;
        }
    }

    /// Stages one regular-file body and commits it when closed.
    @NotNullByDefault
    private final class StoredEntryBodyOutputStream extends OutputStream {
        /// The entry committed by this stream.
        private final PendingEntry entry;

        /// The stored body owned until commit cleanup.
        private final ArkivoStoredContent content;

        /// The writable stream over staged content.
        private final OutputStream storageOutput;

        /// The number of staged body bytes.
        private long written;

        /// The unsigned sum of staged body bytes.
        private long checksum;

        /// Whether this stream rejects further writes.
        private boolean closed;

        /// Whether the commit attempt and cleanup have finished.
        private boolean finished;

        /// The commit failure rethrown by repeated close attempts, or `null` after success.
        private @Nullable IOException closeFailure;

        /// Creates a staged body stream.
        private StoredEntryBodyOutputStream(PendingEntry entry) throws IOException {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.content = bodyStorage.createContent(entry.path, entry.attributes.expectedSize);
            try {
                this.storageOutput = Channels.newOutputStream(content.openChannel(Set.of(
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )));
            } catch (IOException | RuntimeException | Error exception) {
                releaseBody(content);
                throw exception;
            }
        }

        /// Writes one staged body byte.
        @Override
        public void write(int value) throws IOException {
            ensureWritable(1);
            storageOutput.write(value);
            written++;
            if (dialect == CPIODialect.NEW_ASCII_CRC) {
                checksum = checksum + (value & 0xff) & MAX_UNSIGNED_INT;
            }
        }

        /// Writes staged body bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureWritable(length);
            storageOutput.write(bytes, offset, length);
            written += length;
            if (dialect == CPIODialect.NEW_ASCII_CRC) {
                for (int index = 0; index < length; index++) {
                    checksum = checksum + Byte.toUnsignedInt(bytes[offset + index]) & MAX_UNSIGNED_INT;
                }
            }
        }

        /// Flushes staged body bytes.
        @Override
        public void flush() throws IOException {
            ensureWritable(0);
            storageOutput.flush();
        }

        /// Closes staged content, writes the entry, and releases temporary resources.
        @Override
        public void close() throws IOException {
            if (finished) {
                if (closeFailure != null) {
                    throw closeFailure;
                }
                return;
            }
            closed = true;
            @Nullable IOException failure = null;
            try {
                storageOutput.close();
                long size = content.size();
                ensureBodySize(entry, size);
                try (SeekableByteChannel input = content.openChannel(Set.of(StandardOpenOption.READ))) {
                    writeStoredEntry(entry, input, size, checksum);
                }
            } catch (IOException exception) {
                failure = exception;
            } finally {
                closeFailure = failure;
                finished = true;
                releaseBody(content);
                if (currentBody == this) {
                    currentBody = null;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Requires this body stream to accept the requested byte count.
        private void ensureWritable(int count) throws IOException {
            if (!open) {
                throw new IOException("CPIO streaming writer is closed");
            }
            if (closed) {
                throw new IOException("CPIO entry body stream is closed");
            }
            long maximum = maximumBodySize();
            if (count > maximum - written) {
                throw new IOException("CPIO entry body exceeds the maximum representable size for " + entry.path);
            }
            long expectedSize = entry.attributes.expectedSize;
            if (expectedSize != ArkivoEditStorage.UNKNOWN_SIZE && count > expectedSize - written) {
                throw new IOException("CPIO entry body exceeds configured size for " + entry.path);
            }
        }
    }
}
