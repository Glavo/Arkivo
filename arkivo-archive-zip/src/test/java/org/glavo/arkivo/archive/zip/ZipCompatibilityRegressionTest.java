// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compatibility with ZIP metadata layouts exercised by Apache Commons Compress fixtures.
@NotNullByDefault
final class ZipCompatibilityRegressionTest {
    /// The local file header signature.
    private static final long LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L;

    /// The central directory file header signature.
    private static final long CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50L;

    /// The data descriptor signature.
    private static final long DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L;

    /// The ZIP64 end of central directory signature.
    private static final long ZIP64_END_SIGNATURE = 0x06064b50L;

    /// The ZIP64 end of central directory locator signature.
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;

    /// The end of central directory signature.
    private static final long END_SIGNATURE = 0x06054b50L;

    /// The ZIP64 extended information extra field identifier.
    private static final int ZIP64_EXTRA_FIELD_ID = 0x0001;

    /// The general purpose flag indicating a data descriptor.
    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;

    /// The maximum unsigned 16-bit value.
    private static final int UINT16_MAX = 0xffff;

    /// The maximum unsigned 32-bit value.
    private static final long UINT32_MAX = 0xffff_ffffL;

    /// The caller-owned bytes following a complete streaming archive.
    private static final byte @Unmodifiable [] ARCHIVE_TRAILER =
            "caller trailer".getBytes(StandardCharsets.US_ASCII);

    /// Verifies excess ZIP64 extra-field values are tolerated for producer compatibility.
    @Test
    void readsZip64EntryWithExcessExtraFieldData(@TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "zip64 extra field data".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("zip64-extra-tail.zip");
        Files.write(archive, zip64ArchiveWithExcessExtraFieldData(content, true));

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
    }

    /// Verifies an immediately preceding locator selects ZIP64 even when classic end fields are not saturated.
    @Test
    void readsZip64ArchiveWithUnsaturatedClassicEndFields() throws IOException {
        byte @Unmodifiable [] content = "zip64 locator selection".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] archive = zip64ArchiveWithExcessExtraFieldData(content, false);

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
    }

    /// Verifies the largest classic entry count is not mistaken for a mandatory ZIP64 sentinel.
    @Test
    void readsClassicArchiveWithExactlyMaximumEntryCount() throws IOException {
        byte @Unmodifiable [] archive = classicArchiveWithEntryCount(UINT16_MAX);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new SeekableInMemoryByteChannel(archive)
        ); var entries = Files.list(fileSystem.getPath("/"))) {
            assertEquals(UINT16_MAX, entries.count());
        }
    }

    /// Verifies seekable and streaming reads of stored entries with signed and unsigned data descriptors.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsStoredEntryWithDataDescriptor(boolean signature, @TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "stored data descriptor".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve(signature ? "stored-dd.zip" : "stored-dd-nosig.zip");
        Files.write(archive, storedDataDescriptorArchive(content, signature, 0, false, true));

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
        assertArrayEquals(content, readStreamingEntry(archive));
    }

    /// Verifies Android zipalign one-to-three-byte local extra padding is treated as alignment, not a field.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void readsAndroidZipalignShortZeroPadding(int padding, @TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "android zipalign padding".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("zipalign-" + padding + ".zip");
        Files.write(archive, storedArchiveWithLocalZeroPadding(content, padding));

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
        assertArrayEquals(content, readStreamingEntry(archive));
    }

    /// Verifies a stored data descriptor cannot contradict central-directory sizes.
    @Test
    void rejectsStoredDataDescriptorWithDifferentSizes(@TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "stored data descriptor".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("stored-dd-sizes-differ.zip");
        Files.write(archive, storedDataDescriptorArchive(content, true, 1, false, true));

        assertThrows(IOException.class, () -> readEntry(archive, "payload.bin"));
        assertThrows(IOException.class, () -> readStreamingEntry(archive));
    }

    /// Verifies unexpected bytes before a stored data descriptor are rejected.
    @Test
    void rejectsStoredDataDescriptorThatContradictsActualSize(@TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "stored data descriptor".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("stored-dd-actual-size.zip");
        Files.write(archive, storedDataDescriptorArchive(content, true, 0, true, true));

        assertThrows(IOException.class, () -> readEntry(archive, "payload.bin"));
        assertThrows(IOException.class, () -> readStreamingEntry(archive));
    }

    /// Verifies a zero-length stored entry still consumes its declared data descriptor.
    @Test
    void readsZeroLengthStoredEntryWithDataDescriptor() throws IOException {
        byte @Unmodifiable [] archive = storedDataDescriptorArchive(new byte[0], true, 0, false, true);

        assertArrayEquals(new byte[0], readEntry(archive, "payload.bin"));
    }

    /// Verifies a zero-length stored entry cannot omit its locally declared data descriptor.
    @Test
    void rejectsZeroLengthStoredEntryWithoutDataDescriptor() {
        byte @Unmodifiable [] archive = storedDataDescriptorArchive(new byte[0], true, 0, false, false);

        assertThrows(IOException.class, () -> readEntry(archive, "payload.bin"));
    }

    /// Verifies streaming EOF consumes the complete ZIP directory while leaving following caller bytes unread.
    @Test
    void leavesCallerSourceAtFirstTrailerByte() throws IOException {
        byte @Unmodifiable [] content = "streaming source boundary".getBytes(StandardCharsets.UTF_8);
        assertStreamingBoundary(storedArchiveWithLocalZeroPadding(content, 0), content);
    }

    /// Verifies the same source boundary after ZIP64 end records and their locator have been consumed.
    @Test
    void leavesCallerSourceAtFirstTrailerByteAfterZip64Directory() throws IOException {
        byte @Unmodifiable [] content =
                "ZIP64 streaming source boundary".getBytes(StandardCharsets.UTF_8);
        assertStreamingBoundary(zip64ArchiveWithExcessExtraFieldData(content, false), content);
    }

    /// Reads one complete streaming archive and verifies the caller-owned bytes immediately following it.
    private static void assertStreamingBoundary(
            byte @Unmodifiable [] archive,
            byte @Unmodifiable [] content
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(archive);
        bytes.writeBytes(ARCHIVE_TRAILER);
        ByteArrayInputStream source = new ByteArrayInputStream(bytes.toByteArray());

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source)) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertFalse(reader.next());
            assertArrayEquals(ARCHIVE_TRAILER, source.readNBytes(ARCHIVE_TRAILER.length));
            assertEquals(-1, source.read());
        }
    }

    /// Reads one complete entry from a path-backed ZIP file system.
    private static byte @Unmodifiable [] readEntry(Path archive, String entryName) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            return Files.readAllBytes(fileSystem.getPath("/" + entryName));
        }
    }

    /// Reads one complete entry from an in-memory ZIP file system.
    private static byte @Unmodifiable [] readEntry(
            byte @Unmodifiable [] archive,
            String entryName
    ) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new SeekableInMemoryByteChannel(archive)
        )) {
            return Files.readAllBytes(fileSystem.getPath("/" + entryName));
        }
    }

    /// Reads the only entry body through the forward-only ZIP API.
    private static byte @Unmodifiable [] readStreamingEntry(Path archive) throws IOException {
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            assertTrue(reader.next());
            byte @Unmodifiable [] content;
            try (var input = reader.openInputStream()) {
                content = input.readAllBytes();
            }
            assertFalse(reader.next());
            return content;
        }
    }

    /// Builds a one-entry ZIP64 archive whose central ZIP64 field has excess trailing values.
    private static byte @Unmodifiable [] zip64ArchiveWithExcessExtraFieldData(
            byte @Unmodifiable [] content,
            boolean saturateClassicEntryCount
    ) {
        byte @Unmodifiable [] name = "payload.bin".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, 45);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc32);
        writeInt(output, UINT32_MAX);
        writeInt(output, UINT32_MAX);
        writeShort(output, name.length);
        writeShort(output, 20);
        output.writeBytes(name);
        writeShort(output, ZIP64_EXTRA_FIELD_ID);
        writeShort(output, 16);
        writeLong(output, content.length);
        writeLong(output, content.length);
        output.writeBytes(content);

        int centralDirectoryOffset = output.size();
        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, 45);
        writeShort(output, 45);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc32);
        writeInt(output, UINT32_MAX);
        writeInt(output, UINT32_MAX);
        writeShort(output, name.length);
        writeShort(output, 32);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        output.writeBytes(name);
        writeShort(output, ZIP64_EXTRA_FIELD_ID);
        writeShort(output, 28);
        writeLong(output, content.length);
        writeLong(output, content.length);
        writeLong(output, 0);
        writeInt(output, 0);
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        int zip64EndOffset = output.size();
        writeInt(output, ZIP64_END_SIGNATURE);
        writeLong(output, 44);
        writeShort(output, 45);
        writeShort(output, 45);
        writeInt(output, 0);
        writeInt(output, 0);
        writeLong(output, 1);
        writeLong(output, 1);
        writeLong(output, centralDirectorySize);
        writeLong(output, centralDirectoryOffset);
        writeInt(output, ZIP64_LOCATOR_SIGNATURE);
        writeInt(output, 0);
        writeLong(output, zip64EndOffset);
        writeInt(output, 1);
        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, saturateClassicEntryCount ? UINT16_MAX : 1);
        writeShort(output, saturateClassicEntryCount ? UINT16_MAX : 1);
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Builds a valid classic ZIP archive with the requested number of empty root entries.
    private static byte @Unmodifiable [] classicArchiveWithEntryCount(int entryCount) {
        int nameLength = 5;
        int localRecordSize = 30 + nameLength;
        ByteArrayOutputStream output = new ByteArrayOutputStream(entryCount * (localRecordSize + 46 + nameLength) + 22);

        for (int index = 0; index < entryCount; index++) {
            byte @Unmodifiable [] name = hexadecimalEntryName(index);
            writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, 0);
            writeInt(output, 0);
            writeInt(output, 0);
            writeShort(output, name.length);
            writeShort(output, 0);
            output.writeBytes(name);
        }

        int centralDirectoryOffset = output.size();
        for (int index = 0; index < entryCount; index++) {
            byte @Unmodifiable [] name = hexadecimalEntryName(index);
            writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
            writeShort(output, 20);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, 0);
            writeInt(output, 0);
            writeInt(output, 0);
            writeShort(output, name.length);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, 0);
            writeInt(output, (long) index * localRecordSize);
            output.writeBytes(name);
        }
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, entryCount);
        writeShort(output, entryCount);
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Returns a fixed-width ASCII entry name for one unsigned 16-bit index.
    private static byte @Unmodifiable [] hexadecimalEntryName(int index) {
        byte[] name = new byte[]{'e', '0', '0', '0', '0'};
        for (int position = name.length - 1; position > 0; position--) {
            int digit = index & 0x0f;
            name[position] = (byte) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            index >>>= 4;
        }
        return name;
    }

    /// Builds a one-entry stored archive whose descriptor bytes are absent from stored offsets.
    private static byte @Unmodifiable [] storedDataDescriptorArchive(
            byte @Unmodifiable [] content,
            boolean signature,
            int compressedSizeDelta,
            boolean unexpectedByteBeforeDescriptor,
            boolean descriptorPresent
    ) {
        byte @Unmodifiable [] name = "payload.bin".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, DATA_DESCRIPTOR_FLAG);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        writeShort(output, name.length);
        writeShort(output, 0);
        output.writeBytes(name);
        output.writeBytes(content);
        int storedCentralDirectoryOffset = output.size();

        if (descriptorPresent) {
            if (unexpectedByteBeforeDescriptor) {
                output.write('\n');
            }
            if (signature) {
                writeInt(output, DATA_DESCRIPTOR_SIGNATURE);
            }
            writeInt(output, crc32);
            writeInt(output, content.length + compressedSizeDelta);
            writeInt(output, content.length);
        }

        int centralDirectoryOffset = output.size();
        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc32);
        writeInt(output, content.length);
        writeInt(output, content.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        output.writeBytes(name);
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, centralDirectorySize);
        writeInt(output, storedCentralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Builds a one-entry stored archive with a short all-zero local extra-field tail.
    private static byte @Unmodifiable [] storedArchiveWithLocalZeroPadding(
            byte @Unmodifiable [] content,
            int padding
    ) {
        byte @Unmodifiable [] name = "payload.bin".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc32);
        writeInt(output, content.length);
        writeInt(output, content.length);
        writeShort(output, name.length);
        writeShort(output, padding);
        output.writeBytes(name);
        for (int index = 0; index < padding; index++) {
            output.write(0);
        }
        output.writeBytes(content);

        int centralDirectoryOffset = output.size();
        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc32);
        writeInt(output, content.length);
        writeInt(output, content.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        output.writeBytes(name);
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Computes the unsigned CRC-32 value of a byte array.
    private static long crc32(byte @Unmodifiable [] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    /// Writes a little-endian unsigned 16-bit value.
    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
    }

    /// Writes a little-endian unsigned 32-bit value.
    private static void writeInt(ByteArrayOutputStream output, long value) {
        writeShort(output, (int) value);
        writeShort(output, (int) (value >>> 16));
    }

    /// Writes a little-endian 64-bit value.
    private static void writeLong(ByteArrayOutputStream output, long value) {
        writeInt(output, value);
        writeInt(output, value >>> 32);
    }
}
