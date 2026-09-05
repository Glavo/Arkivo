// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP-specific attribute snapshots exposed from local headers during streaming traversal.
@NotNullByDefault
final class ZipStreamingEntryAttributesTest {
    /// The regular-file payload stored without compression.
    private static final byte @Unmodifiable [] PAYLOAD = "streaming attributes".getBytes(StandardCharsets.UTF_8);

    /// An unrecognized but structurally valid local extra field.
    private static final byte @Unmodifiable [] LOCAL_EXTRA_DATA = {0x34, 0x12, 0x01, 0x00, 0x55};

    /// An unrecognized but structurally valid central-directory extra field.
    private static final byte @Unmodifiable [] CENTRAL_EXTRA_DATA = {0x78, 0x56, 0x01, 0x00, 0x66};

    /// Verifies all local-header attributes, detached snapshots, defensive copies, and directory classification.
    @Test
    void exposesDetachedLocalHeaderMetadata() throws IOException {
        long expectedCrc32 = crc32(PAYLOAD);
        byte @Unmodifiable [] archive = createArchive(expectedCrc32);

        assertCentralDirectoryMetadata(archive);

        ZipArkivoEntryAttributes fileAttributes;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            fileAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertRegularFileAttributes(fileAttributes, expectedCrc32);

            assertTrue(reader.next());
            assertDirectoryAttributes(reader.readAttributes(ZipArkivoEntryAttributes.class));
            assertFalse(reader.next());
        }

        assertRegularFileAttributes(fileAttributes, expectedCrc32);
    }

    /// Verifies attribute access observes the streaming reader's closed state.
    @Test
    void rejectsAttributeReadsAfterReaderClose() throws IOException {
        byte @Unmodifiable [] archive = createArchive(crc32(PAYLOAD));

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            reader.close();

            assertThrows(
                    ClosedChannelException.class,
                    () -> reader.readAttributes(ZipArkivoEntryAttributes.class)
            );
        }
    }

    /// Confirms the generated central directory carries metadata that is intentionally unavailable while streaming.
    private static void assertCentralDirectoryMetadata(byte @Unmodifiable [] archive) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            ZipArkivoEntryAttributes attributes = Files.readAttributes(
                    fileSystem.getPath("/dir/value.bin"),
                    ZipArkivoEntryAttributes.class
            );
            assertEquals("central comment", attributes.comment());
            assertArrayEquals(CENTRAL_EXTRA_DATA, attributes.centralDirectoryExtraData());
            assertArrayEquals("central comment".getBytes(StandardCharsets.UTF_8), attributes.rawComment());
        }
    }

    /// Creates an archive whose local and central records intentionally carry different metadata.
    private static byte @Unmodifiable [] createArchive(long expectedCrc32) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("dir/value.bin");
            ZipArkivoEntryAttributeView view = requireView(file, ZipArkivoEntryAttributeView.class);
            view.setMethod(ZipMethod.STORED);
            view.setUncompressedSizeAndCrc32(PAYLOAD.length, expectedCrc32);
            view.setLocalExtraData(LOCAL_EXTRA_DATA);
            view.setCentralDirectoryExtraData(CENTRAL_EXTRA_DATA);
            view.setRawComment("central comment".getBytes(StandardCharsets.UTF_8));
            try (OutputStream output = file.openOutputStream()) {
                output.write(PAYLOAD);
            }

            writer.beginDirectory("folder").close();
        }
        return archive.toByteArray();
    }

    /// Asserts metadata available from the regular file's local header.
    private static void assertRegularFileAttributes(ZipArkivoEntryAttributes attributes, long expectedCrc32) {
        assertEquals("dir/value.bin", attributes.path());
        assertArrayEquals("dir/value.bin".getBytes(StandardCharsets.UTF_8), attributes.rawPath());
        assertNull(attributes.comment());
        assertEquals(PAYLOAD.length, attributes.compressedSize());
        assertEquals(expectedCrc32, attributes.crc32());
        assertEquals(1 << 11, attributes.generalPurposeFlags());
        assertEquals(0, attributes.versionMadeBy());
        assertEquals(20, attributes.versionNeededToExtract());
        assertEquals(0, attributes.internalAttributes());
        assertEquals(0L, attributes.externalAttributes());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, attributes.userId());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, attributes.groupId());
        assertEquals(ZipMethod.STORED.id(), attributes.compressionMethodId());
        assertEquals(ZipMethod.STORED, attributes.compressionMethod());
        assertEquals(ZipEncryption.NONE, attributes.encryption());
        assertArrayEquals(LOCAL_EXTRA_DATA, attributes.localExtraData());
        assertArrayEquals(new byte[0], attributes.centralDirectoryExtraData());
        assertNull(attributes.rawComment());
        assertEquals(attributes.lastModifiedTime(), attributes.lastAccessTime());
        assertEquals(attributes.lastModifiedTime(), attributes.creationTime());
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isDirectory());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertEquals(PAYLOAD.length, attributes.size());
        assertNull(attributes.fileKey());
        assertEquals("owner", attributes.owner().getName());
        assertEquals("group", attributes.group().getName());
        assertEquals(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
        ), attributes.permissions());

        byte[] rawPath = attributes.rawPath();
        byte[] localExtraData = attributes.localExtraData();
        rawPath[0] = 0;
        localExtraData[0] = 0;
        assertArrayEquals("dir/value.bin".getBytes(StandardCharsets.UTF_8), attributes.rawPath());
        assertArrayEquals(LOCAL_EXTRA_DATA, attributes.localExtraData());
    }

    /// Asserts directory-specific values synthesized from a streaming local header.
    private static void assertDirectoryAttributes(ZipArkivoEntryAttributes attributes) {
        assertEquals("folder/", attributes.path());
        assertArrayEquals("folder/".getBytes(StandardCharsets.UTF_8), attributes.rawPath());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_CRC32, attributes.crc32());
        assertFalse(attributes.isRegularFile());
        assertTrue(attributes.isDirectory());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertEquals(0L, attributes.size());
        assertEquals(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        ), attributes.permissions());
    }

    /// Returns the requested non-null pending entry attribute view.
    private static <V extends FileAttributeView> V requireView(
            ArkivoStreamingWriter.Entry entry,
            Class<V> type
    ) throws IOException {
        return Objects.requireNonNull(entry.attributeView(type), type.getName());
    }

    /// Computes the unsigned CRC-32 value for the given bytes.
    private static long crc32(byte @Unmodifiable [] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }
}
