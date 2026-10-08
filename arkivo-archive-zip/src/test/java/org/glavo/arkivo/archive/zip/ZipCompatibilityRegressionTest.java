// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    /// Does not confuse unsigned preamble words with the end-of-input sentinel.
    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -2, -1, 0})
    void readsArchiveAfterUnsignedPreamble(int word) throws IOException {
        byte[] original = classicArchiveWithEntryCount(1);
        byte[] bytes = new byte[4 + original.length];
        ByteArrayAccess.writeIntLittleEndian(bytes, 0, word);
        System.arraycopy(original, 0, bytes, 4, original.length);
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes))) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertEquals(0, input.readAllBytes().length);
            }
            assertFalse(reader.next());
        }
    }

    /// Rejects a full-width invalid record after an entry instead of silently terminating the archive.
    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -2, -1})
    void rejectsUnsignedInvalidRecord(int word) throws IOException {
        byte[] bytes = classicArchiveWithEntryCount(1).clone();
        int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        assertEquals((int) CENTRAL_DIRECTORY_HEADER_SIGNATURE, ByteArrayAccess.readIntLittleEndian(bytes, central));
        ByteArrayAccess.writeIntLittleEndian(bytes, central, word);
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes))) {
            assertTrue(reader.next());
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Rejects a final-disk declaration for a missing volume even when the central directory is empty.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnavailableEndRecordVolume(boolean empty, @TempDir Path directory) throws IOException {
        byte[] bytes = classicArchiveWithEntryCount(empty ? 0 : 1);
        int end = bytes.length - 22;
        ByteArrayAccess.writeIntLittleEndian(bytes, end, (int) END_SIGNATURE);
        ByteArrayAccess.writeShortLittleEndian(bytes, end + 4, (short) 1);
        assertUnavailableEndVolume(bytes, directory);
    }

    /// Rejects missing volumes declared by classic metadata, the ZIP64 record, or the ZIP64 locator.
    @ParameterizedTest
    @CsvSource({"18,2,1", "82,4,1", "26,4,2", "26,4,0"})
    void rejectsUnavailableZip64EndVolume(int distance, int width, int value, @TempDir Path directory) throws IOException {
        byte[] bytes = zip64Archive(new byte[0], true, false, false, false);
        if (width == Short.BYTES) {
            ByteArrayAccess.writeShortLittleEndian(bytes, bytes.length - distance, (short) value);
        } else {
            ByteArrayAccess.writeIntLittleEndian(bytes, bytes.length - distance, value);
        }
        assertUnavailableEndVolume(bytes, directory);
    }

    /// Forces indexing through Path and explicit Channel entry points and checks failed updates leave bytes unchanged.
    private static void assertUnavailableEndVolume(byte[] bytes, Path directory) throws IOException {
        Path archive = directory.resolve("missing-volume.zip");
        Files.write(archive, bytes);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(archive);
                 var entries = Files.list(fileSystem.getPath("/"))) {
                entries.toList();
            }
        });
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes));
                 var entries = Files.list(fileSystem.getPath("/"))) {
                entries.toList();
            }
        });
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.update(archive);
                 var entries = Files.list(fileSystem.getPath("/"))) {
                entries.toList();
            }
        });
        assertArrayEquals(bytes, Files.readAllBytes(archive));
    }

    /// Exposes known directory wire metadata without inventing values for synthetic directories or descriptors.
    @ParameterizedTest
    @CsvSource({"0, false", "8, false", "8, true"})
    void retainsDirectoryCompressionMetadata(int method, boolean descriptor, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("directories.zip");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            var entry = new java.util.zip.ZipEntry("explicit/");
            entry.setMethod(method);
            if (!descriptor) {
                entry.setSize(0);
                // JDK emits a two-byte empty fixed-Huffman block for raw Deflate.
                entry.setCompressedSize(method == java.util.zip.ZipEntry.STORED ? 0 : 2);
                entry.setCrc(0);
            }
            output.putNextEntry(entry);
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("implicit/child"));
            output.closeEntry();
        }
        long compressedSize;
        try (var reference = new java.util.zip.ZipFile(archive.toFile())) {
            compressedSize = reference.getEntry("explicit/").getCompressedSize();
        }
        ZipArkivoEntryAttributes snapshot;
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            snapshot = Files.readAttributes(fileSystem.getPath("/explicit"), ZipArkivoEntryAttributes.class);
            assertEquals(compressedSize, snapshot.compressedSize());
            assertEquals(0, snapshot.crc32());
            var implicit = Files.readAttributes(fileSystem.getPath("/implicit"), ZipArkivoEntryAttributes.class);
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, implicit.compressedSize());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_CRC32, implicit.crc32());
        }
        try (var reader = ZipArkivoStreamingReader.open(Files.newInputStream(archive))) {
            assertTrue(reader.next());
            var attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            long expectedSize = descriptor ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : compressedSize;
            long expectedCrc = descriptor ? ZipArkivoEntryAttributes.UNKNOWN_CRC32 : 0;
            assertEquals(expectedSize, attributes.compressedSize());
            assertEquals(expectedCrc, attributes.crc32());
            assertTrue(reader.next());
            assertFalse(reader.next());
            assertEquals(expectedSize, attributes.compressedSize());
            assertEquals(expectedCrc, attributes.crc32());
        }
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            var attributes = Files.readAttributes(fileSystem.getPath("/explicit"), ZipArkivoEntryAttributes.class);
            assertEquals(compressedSize, attributes.compressedSize());
            assertEquals(0, attributes.crc32());
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{1});
        }
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            var attributes = Files.readAttributes(fileSystem.getPath("/explicit"), ZipArkivoEntryAttributes.class);
            assertEquals(compressedSize, attributes.compressedSize());
            assertEquals(0, attributes.crc32());
        }
        assertEquals(compressedSize, snapshot.compressedSize());
        assertEquals(0, snapshot.crc32());
    }

    /// Verifies excess ZIP64 extra-field values are tolerated for producer compatibility.
    @Test
    void readsZip64EntryWithExcessExtraFieldData(@TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "zip64 extra field data".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("zip64-extra-tail.zip");
        Files.write(archive, zip64Archive(content, true, false, false, true));

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
    }

    /// Verifies independently optional ZIP64 local-header location fields survive a complete-rewrite update.
    @ParameterizedTest
    @CsvSource({
            "false, false",
            "true,  false",
            "false, true",
            "true,  true"
    })
    void updatesEntryWithZip64LocationFields(
            boolean zip64LocalHeaderOffset,
            boolean zip64DiskNumber,
            @TempDir Path directory
    ) throws IOException {
        byte @Unmodifiable [] content = "zip64 location fields".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] addedContent = "added during update".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve(
                "zip64-location-" + zip64LocalHeaderOffset + "-" + zip64DiskNumber + ".zip"
        );
        Files.write(archive, zip64ArchiveWithLeadingEntry(
                content,
                zip64LocalHeaderOffset,
                zip64DiskNumber
        ));

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/payload.bin")));
            Files.delete(fileSystem.getPath("/leading.bin"));
            Files.write(fileSystem.getPath("/added.bin"), addedContent);
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertFalse(Files.exists(fileSystem.getPath("/leading.bin")));
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/payload.bin")));
            assertArrayEquals(addedContent, Files.readAllBytes(fileSystem.getPath("/added.bin")));
        }
    }

    /// Verifies an immediately preceding locator selects ZIP64 even when classic end fields are not saturated.
    @Test
    void readsZip64ArchiveWithUnsaturatedClassicEndFields() throws IOException {
        byte @Unmodifiable [] content = "zip64 locator selection".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] archive = zip64Archive(content, false, false, false, false);

        assertArrayEquals(content, readEntry(archive, "payload.bin"));
    }

    /// Verifies the largest classic entry count is not mistaken for a mandatory ZIP64 sentinel.
    @Test
    void readsClassicArchiveWithExactlyMaximumEntryCount() throws IOException {
        byte @Unmodifiable [] archive = classicArchiveWithEntryCount(UINT16_MAX);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new ReadOnlyByteArrayChannel(archive)
        ); var entries = Files.list(fileSystem.getPath("/"))) {
            assertEquals(UINT16_MAX, entries.count());
        }
    }

    /// Rejects both missing and excess classic central-directory entries instead of trusting directory size alone.
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 65535})
    void rejectsIncorrectClassicEntryCount(int count) {
        byte[] archive = classicArchiveWithEntryCount(1);
        int endOffset = archive.length - 22;
        ByteArrayAccess.writeShortLittleEndian(archive, endOffset + 8, (short) count);
        ByteArrayAccess.writeShortLittleEndian(archive, endOffset + 10, (short) count);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive));
                 var entries = Files.list(fileSystem.getPath("/"))) {
                entries.toList();
            }
        });
    }

    /// Rejects inconsistent ZIP64 counts, including unsigned values outside the supported long range.
    @ParameterizedTest
    @ValueSource(longs = {0, 2, Long.MAX_VALUE, Long.MIN_VALUE, -1})
    void rejectsIncorrectZip64EntryCount(long count) {
        byte[] archive = zip64Archive(new byte[0], true, false, false, false);
        int zip64EndOffset = archive.length - 22 - 20 - 56;
        ByteArrayAccess.writeLongLittleEndian(archive, zip64EndOffset + 24, count);
        ByteArrayAccess.writeLongLittleEndian(archive, zip64EndOffset + 32, count);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive));
                 var entries = Files.list(fileSystem.getPath("/"))) {
                entries.toList();
            }
        });
    }

    /// Rejects ZIP64 end lengths that cannot describe the bytes preceding the locator.
    @ParameterizedTest
    @ValueSource(longs = {0, 43, 45, 65536, Long.MAX_VALUE, Long.MIN_VALUE, -1})
    void rejectsInvalidZip64EndLength(long length) throws IOException {
        byte[] archive = zip64Archive(new byte[0], true, false, false, false);
        int end = archive.length - 98;
        ByteArrayAccess.writeLongLittleEndian(archive, end + 4, length);
        assertInvalidZip64Index(archive);

        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertEquals(-1, input.read());
            }
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Rejects overflowing and out-of-range directory extents without changing an update source.
    @ParameterizedTest
    @CsvSource({
            "40, 2147483647", "40, 4294967295", "40, 9223372036854775807",
            "40, -9223372036854775808", "40, -1",
            "48, 2147483647", "48, 4294967295", "48, 9223372036854775807",
            "48, -9223372036854775808", "48, -1"
    })
    void rejectsInvalidZip64DirectoryExtent(int field, long value, @TempDir Path directory)
            throws IOException {
        byte[] archive = zip64Archive(new byte[0], true, false, false, false);
        ByteArrayAccess.writeLongLittleEndian(archive, archive.length - 98 + field, value);
        assertInvalidZip64Index(archive);
        Path path = directory.resolve("invalid-extent.zip");
        Files.write(path, archive);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.update(path)) {
                Files.readAllBytes(fileSystem.getPath("/payload.bin"));
            }
        });
        assertArrayEquals(archive, Files.readAllBytes(path));
    }

    /// Reads opaque ZIP64 extensible data, including false record signatures, with an unadjusted SFX prefix.
    @ParameterizedTest
    @CsvSource({"0, 0", "6, 0", "63, 0", "4096, 0", "0, 113", "6, 113", "63, 113", "4096, 113"})
    void readsZip64ExtensibleData(int extensionSize, int prefixSize, @TempDir Path directory)
            throws IOException {
        byte[] content = "ZIP64 extensible data".getBytes(StandardCharsets.US_ASCII);
        byte[] original = zip64Archive(content, true, false, false, false);
        int end = original.length - 98;
        int locator = end + 56;
        byte[] archive = new byte[prefixSize + original.length + extensionSize];
        Arrays.fill(archive, (byte) 0x5a);
        System.arraycopy(original, 0, archive, prefixSize, locator);
        System.arraycopy(original, locator, archive, prefixSize + locator + extensionSize,
                original.length - locator);
        ByteArrayAccess.writeLongLittleEndian(archive, prefixSize + end + 4, 44L + extensionSize);
        if (extensionSize != 0) {
            ByteArrayAccess.writeShortLittleEndian(archive, prefixSize + locator, (short) 0xffff);
            ByteArrayAccess.writeIntLittleEndian(archive, prefixSize + locator + 2, extensionSize - 6);
        }
        if (extensionSize >= 62) {
            // A signature inside the opaque extension is not another end record.
            ByteArrayAccess.writeIntLittleEndian(archive, prefixSize + locator + 6, (int) ZIP64_END_SIGNATURE);
        }
        assertArrayEquals(content, readEntry(archive, "payload.bin"));
        Path path = directory.resolve("extensible.zip");
        Files.write(path, archive);
        assertArrayEquals(content, readEntry(path, "payload.bin"));
        if (prefixSize == 0) {
            assertArrayEquals(content, readStreamingEntry(path));
        }
    }

    /// Forces lazy index parsing twice and verifies that closing after failure releases the owned channel.
    private static void assertInvalidZip64Index(byte[] archive) throws IOException {
        var source = new ReadOnlyByteArrayChannel(archive);
        try (var fileSystem = ZipArkivoFileSystem.open(source)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(IOException.class, () -> {
                    try (var entries = Files.list(fileSystem.getPath("/"))) {
                        entries.toList();
                    }
                });
            }
        }
        assertFalse(source.isOpen());
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

    /// Preserves local zero padding when an update renames an existing entry.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void renamesEntryWithShortZeroPadding(int padding, @TempDir Path directory) throws IOException {
        byte @Unmodifiable [] content = "renamed zipalign entry".getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("renamed-zipalign.zip");
        Files.write(archive, storedArchiveWithLocalZeroPadding(content, padding));

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Files.move(fileSystem.getPath("/payload.bin"), fileSystem.getPath("/renamed.bin"));
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path renamed = fileSystem.getPath("/renamed.bin");
            assertFalse(Files.exists(fileSystem.getPath("/payload.bin")));
            assertArrayEquals(content, Files.readAllBytes(renamed));
            assertArrayEquals(new byte[padding], Files.readAttributes(renamed, ZipArkivoEntryAttributes.class).localExtraData());
        }
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
        assertStreamingBoundary(zip64Archive(content, false, false, false, false), content);
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
                new ReadOnlyByteArrayChannel(archive)
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

    /// Builds a one-entry ZIP64 archive with configurable classic count and location fields.
    ///
    /// @param content the stored entry content
    /// @param saturateClassicEntryCount whether the classic end record uses the maximum entry-count sentinel
    /// @param zip64LocalHeaderOffset whether the central entry carries its local-header offset in ZIP64 data
    /// @param zip64DiskNumber whether the central entry carries its start disk in ZIP64 data
    /// @param includeExcessLocationValues whether otherwise unneeded location values trail the ZIP64 size values
    /// @return the complete archive bytes
    private static byte @Unmodifiable [] zip64Archive(
            byte @Unmodifiable [] content,
            boolean saturateClassicEntryCount,
            boolean zip64LocalHeaderOffset,
            boolean zip64DiskNumber,
            boolean includeExcessLocationValues
    ) {
        return zip64Archive(
                content,
                saturateClassicEntryCount,
                zip64LocalHeaderOffset,
                zip64DiskNumber,
                includeExcessLocationValues,
                false
        );
    }

    /// Builds a ZIP64 archive whose payload follows a removable classic stored entry.
    ///
    /// @param content the ZIP64 payload entry content
    /// @param zip64LocalHeaderOffset whether the payload location uses a ZIP64 offset
    /// @param zip64DiskNumber whether the payload location uses a ZIP64 disk number
    /// @return the complete two-entry archive bytes
    private static byte @Unmodifiable [] zip64ArchiveWithLeadingEntry(
            byte @Unmodifiable [] content,
            boolean zip64LocalHeaderOffset,
            boolean zip64DiskNumber
    ) {
        return zip64Archive(content, false, zip64LocalHeaderOffset, zip64DiskNumber, false, true);
    }

    /// Builds the configurable ZIP64 archive used by the public fixture helpers.
    private static byte @Unmodifiable [] zip64Archive(
            byte @Unmodifiable [] content,
            boolean saturateClassicEntryCount,
            boolean zip64LocalHeaderOffset,
            boolean zip64DiskNumber,
            boolean includeExcessLocationValues,
            boolean includeLeadingEntry
    ) {
        byte @Unmodifiable [] name = "payload.bin".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        byte @Unmodifiable [] leadingName = "leading.bin".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] leadingContent = "removed during update".getBytes(StandardCharsets.UTF_8);
        int leadingLocalHeaderOffset = output.size();
        if (includeLeadingEntry) {
            writeClassicStoredLocalEntry(output, leadingName, leadingContent);
        }

        int payloadLocalHeaderOffset = output.size();
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
        if (includeLeadingEntry) {
            writeClassicStoredCentralDirectoryEntry(
                    output,
                    leadingName,
                    leadingContent,
                    leadingLocalHeaderOffset
            );
        }
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
        int zip64CentralDataSize = 2 * Long.BYTES
                + (zip64LocalHeaderOffset || includeExcessLocationValues ? Long.BYTES : 0)
                + (zip64DiskNumber || includeExcessLocationValues ? Integer.BYTES : 0);
        writeShort(output, 4 + zip64CentralDataSize);
        writeShort(output, 0);
        writeShort(output, zip64DiskNumber ? UINT16_MAX : 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, zip64LocalHeaderOffset ? UINT32_MAX : payloadLocalHeaderOffset);
        output.writeBytes(name);
        writeShort(output, ZIP64_EXTRA_FIELD_ID);
        writeShort(output, zip64CentralDataSize);
        writeLong(output, content.length);
        writeLong(output, content.length);
        if (zip64LocalHeaderOffset || includeExcessLocationValues) {
            writeLong(output, payloadLocalHeaderOffset);
        }
        if (zip64DiskNumber || includeExcessLocationValues) {
            writeInt(output, 0);
        }
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        int zip64EndOffset = output.size();
        writeInt(output, ZIP64_END_SIGNATURE);
        writeLong(output, 44);
        writeShort(output, 45);
        writeShort(output, 45);
        writeInt(output, 0);
        writeInt(output, 0);
        int entryCount = includeLeadingEntry ? 2 : 1;
        writeLong(output, entryCount);
        writeLong(output, entryCount);
        writeLong(output, centralDirectorySize);
        writeLong(output, centralDirectoryOffset);
        writeInt(output, ZIP64_LOCATOR_SIGNATURE);
        writeInt(output, 0);
        writeLong(output, zip64EndOffset);
        writeInt(output, 1);
        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, saturateClassicEntryCount ? UINT16_MAX : entryCount);
        writeShort(output, saturateClassicEntryCount ? UINT16_MAX : entryCount);
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Writes one classic stored local-file record.
    private static void writeClassicStoredLocalEntry(
            ByteArrayOutputStream output,
            byte @Unmodifiable [] name,
            byte @Unmodifiable [] content
    ) {
        long checksum = crc32(content);
        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, checksum);
        writeInt(output, content.length);
        writeInt(output, content.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        output.writeBytes(name);
        output.writeBytes(content);
    }

    /// Writes one classic stored central-directory record.
    private static void writeClassicStoredCentralDirectoryEntry(
            ByteArrayOutputStream output,
            byte @Unmodifiable [] name,
            byte @Unmodifiable [] content,
            int localHeaderOffset
    ) {
        long checksum = crc32(content);
        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, checksum);
        writeInt(output, content.length);
        writeInt(output, content.length);
        writeShort(output, name.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, localHeaderOffset);
        output.writeBytes(name);
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
