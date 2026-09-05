// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/// Creates compact, deterministic RAR5 fixtures shared by focused contract tests.
@NotNullByDefault
final class RarTestArchiveFixtures {
    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The RAR5 archive signature.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// The RAR4 file-header flag indicating an encoded Unicode name suffix.
    private static final int RAR4_FILE_FLAG_UNICODE = 0x0200;

    /// The Unix mode used for regular-file fixtures.
    private static final long REGULAR_FILE_MODE = 0100644L;

    /// The deterministic modification time stored in file headers.
    private static final long MODIFICATION_TIME = 1_700_000_000L;

    /// Prevents instantiation.
    private RarTestArchiveFixtures() {
    }

    /// Creates a RAR4 archive containing one empty stored file with caller-supplied name bytes.
    ///
    /// @param nameField the complete file-header name field
    /// @param unicodeName whether the name field includes a RAR4 Unicode suffix
    /// @return the complete archive bytes
    static byte @Unmodifiable [] rar4StoredArchive(byte @Unmodifiable [] nameField, boolean unicodeName)
            throws IOException {
        return rar4StoredArchive(nameField, unicodeName, new byte[0]);
    }

    /// Creates a RAR4 archive containing one stored file with caller-supplied name and content bytes.
    ///
    /// @param nameField the complete file-header name field
    /// @param unicodeName whether the name field includes a RAR4 Unicode suffix
    /// @param content the stored file body
    /// @return the complete archive bytes
    static byte @Unmodifiable [] rar4StoredArchive(
            byte @Unmodifiable [] nameField,
            boolean unicodeName,
            byte @Unmodifiable [] content
    ) throws IOException {
        Objects.requireNonNull(nameField, "nameField");
        Objects.requireNonNull(content, "content");
        if (nameField.length > 0xffff) {
            throw new IllegalArgumentException("RAR4 name field is too long");
        }

        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, content.length);
        writeUInt32(fields, content.length);
        fields.write(3);
        writeUInt32(fields, crc32(content));
        writeUInt32(fields, 0L);
        fields.write(29);
        fields.write(0x30);
        writeUInt16(fields, nameField.length);
        writeUInt32(fields, REGULAR_FILE_MODE);
        fields.write(nameField);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(RAR4_SIGNATURE);
        writeRar4Block(archive, 0x73, 0L, new byte[6]);
        writeRar4Block(
                archive,
                0x74,
                0x8000L | (unicodeName ? RAR4_FILE_FLAG_UNICODE : 0L),
                fields.toByteArray()
        );
        archive.write(content);
        writeRar4Block(archive, 0x7b, 0L, new byte[0]);
        return archive.toByteArray();
    }

    /// Creates a RAR4 archive exposing caller-supplied 64-bit packed and unpacked sizes.
    ///
    /// The generated compressed entry intentionally has no physical body so tests can inspect or reject its header
    /// without allocating content proportional to the declared sizes.
    ///
    /// @param lowPackedSize the low unsigned 32 bits of the packed size
    /// @param lowUnpackedSize the low unsigned 32 bits of the unpacked size
    /// @param highPackedSize the high unsigned 32 bits of the packed size
    /// @param highUnpackedSize the high unsigned 32 bits of the unpacked size
    /// @return the complete archive bytes
    static byte @Unmodifiable [] rar4LargeFileHeaderArchive(
            long lowPackedSize,
            long lowUnpackedSize,
            long highPackedSize,
            long highUnpackedSize
    ) throws IOException {
        requireUInt32(lowPackedSize, "lowPackedSize");
        requireUInt32(lowUnpackedSize, "lowUnpackedSize");
        requireUInt32(highPackedSize, "highPackedSize");
        requireUInt32(highUnpackedSize, "highUnpackedSize");

        byte[] name = "large.bin".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, lowPackedSize);
        writeUInt32(fields, lowUnpackedSize);
        fields.write(3);
        writeUInt32(fields, 0L);
        writeUInt32(fields, 0L);
        fields.write(29);
        fields.write(0x31);
        writeUInt16(fields, name.length);
        writeUInt32(fields, REGULAR_FILE_MODE);
        writeUInt32(fields, highPackedSize);
        writeUInt32(fields, highUnpackedSize);
        fields.write(name);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(RAR4_SIGNATURE);
        writeRar4Block(archive, 0x73, 0L, new byte[6]);
        writeRar4Block(archive, 0x74, 0x8100L, fields.toByteArray());
        writeRar4Block(archive, 0x7b, 0L, new byte[0]);
        return archive.toByteArray();
    }

    /// Creates a RAR5 archive containing one stored regular file.
    ///
    /// @param path    the entry path
    /// @param content the entry body
    /// @return the complete archive bytes
    static byte @Unmodifiable [] storedArchive(String path, byte[] content) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");

        return storedArchive(Map.of(path, content));
    }

    /// Creates a RAR5 archive containing stored regular files in map iteration order.
    ///
    /// @param entries the non-empty path-to-content mapping
    /// @return the complete archive bytes
    static byte @Unmodifiable [] storedArchive(Map<String, byte[]> entries) throws IOException {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("At least one entry is required");
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(RAR5_SIGNATURE);
        writeBlock(archive, 1L, fields(writer -> writer.writeVint(0L)), new byte[0]);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "entry path");
            byte[] content = Objects.requireNonNull(entry.getValue(), "entry content");
            writeBlock(archive, 2L, storedFileFields(path, content), content);
        }
        writeBlock(archive, 5L, fields(writer -> writer.writeVint(0L)), new byte[0]);
        return archive.toByteArray();
    }

    /// Creates a RAR5 archive containing empty stored regular files with the given paths.
    ///
    /// @param paths the entry paths in archive order
    /// @return the complete archive bytes
    static byte @Unmodifiable [] emptyStoredArchive(String... paths) throws IOException {
        if (paths.length == 0) {
            throw new IllegalArgumentException("At least one path is required");
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(RAR5_SIGNATURE);
        writeBlock(archive, 1L, fields(writer -> writer.writeVint(0L)), new byte[0]);
        for (String path : paths) {
            Objects.requireNonNull(path, "path");
            writeBlock(archive, 2L, storedFileFields(path, new byte[0]), new byte[0]);
        }
        writeBlock(archive, 5L, fields(writer -> writer.writeVint(0L)), new byte[0]);
        return archive.toByteArray();
    }

    /// Encodes the file-header fields for one stored regular file.
    private static byte @Unmodifiable [] storedFileFields(String path, byte[] content) throws IOException {
        return fields(writer -> {
            writer.writeVint(0x0002L | 0x0004L);
            writer.writeVint(content.length);
            writer.writeVint(REGULAR_FILE_MODE);
            writer.writeUInt32(MODIFICATION_TIME);
            writer.writeUInt32(crc32(content));
            writer.writeVint(0L);
            writer.writeVint(RarArkivoEntryAttributes.HOST_OS_UNIX);
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            writer.writeVint(name.length);
            writer.write(name);
        });
    }

    /// Writes one RAR5 block and its optional data area.
    private static void writeBlock(
            ByteArrayOutputStream output,
            long type,
            byte[] fields,
            byte[] data
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        VintWriter writer = new VintWriter(headerData);
        writer.writeVint(type);
        writer.writeVint(data.length == 0 ? 0L : 0x0002L);
        if (data.length != 0) {
            writer.writeVint(data.length);
        }
        writer.write(fields);

        byte[] header = headerData.toByteArray();
        byte[] headerSize = vint(header.length);
        CRC32 checksum = new CRC32();
        checksum.update(headerSize);
        checksum.update(header);
        writeUInt32(output, checksum.getValue());
        output.write(headerSize);
        output.write(header);
        output.write(data);
    }

    /// Writes one RAR4 block with its truncated CRC32 header checksum.
    private static void writeRar4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte @Unmodifiable [] fields
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7 + fields.length);
        headerData.write(fields);

        byte[] header = headerData.toByteArray();
        CRC32 checksum = new CRC32();
        checksum.update(header);
        writeUInt16(output, checksum.getValue());
        output.write(header);
    }

    /// Encodes fields through one primitive writer callback.
    private static byte @Unmodifiable [] fields(FieldWriterConsumer consumer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        consumer.accept(new VintWriter(output));
        return output.toByteArray();
    }

    /// Encodes one unsigned RAR variable-length integer.
    private static byte @Unmodifiable [] vint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long remaining = value;
        do {
            int next = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            output.write(remaining == 0L ? next : next | 0x80);
        } while (remaining != 0L);
        return output.toByteArray();
    }

    /// Writes one unsigned 32-bit integer in little-endian order.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        byte[] encoded = new byte[Integer.BYTES];
        ByteArrayAccess.writeIntLittleEndian(encoded, 0, (int) value);
        output.writeBytes(encoded);
    }

    /// Writes one unsigned 16-bit integer in little-endian order.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        byte[] encoded = new byte[Short.BYTES];
        ByteArrayAccess.writeShortLittleEndian(encoded, 0, (short) value);
        output.writeBytes(encoded);
    }

    /// Requires one fixture value to fit in an unsigned 32-bit field.
    private static void requireUInt32(long value, String name) {
        if (value < 0L || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(name + " must fit in an unsigned 32-bit integer");
        }
    }

    /// Returns the unsigned CRC32 of the supplied bytes.
    private static long crc32(byte[] content) {
        CRC32 checksum = new CRC32();
        checksum.update(content);
        return checksum.getValue();
    }

    /// Receives one RAR field writer.
    @FunctionalInterface
    private interface FieldWriterConsumer {
        /// Writes fixture fields to the supplied writer.
        void accept(VintWriter writer) throws IOException;
    }

    /// Writes the primitive fields needed by the compact fixtures.
    @NotNullByDefault
    private static final class VintWriter {
        /// The encoded-field destination.
        private final ByteArrayOutputStream output;

        /// Creates a writer targeting the supplied byte stream.
        private VintWriter(ByteArrayOutputStream output) {
            this.output = output;
        }

        /// Writes one unsigned RAR variable-length integer.
        private void writeVint(long value) {
            output.writeBytes(vint(value));
        }

        /// Writes one unsigned 32-bit integer in little-endian order.
        private void writeUInt32(long value) {
            RarTestArchiveFixtures.writeUInt32(output, value);
        }

        /// Writes raw bytes.
        private void write(byte[] bytes) throws IOException {
            output.write(bytes);
        }
    }
}
