// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies public CPIO entry metadata, body, and cursor lifecycle contracts.
@NotNullByDefault
final class CPIOEntryContractTest {
    /// Verifies pending metadata validation, detached snapshots, and post-body view invalidation.
    @Test
    void validatesPendingMetadataAndSnapshots() throws IOException {
        byte[] expected = {(byte) 0x80, (byte) 0xff};
        FileTime modificationTime = FileTime.from(Instant.ofEpochSecond(1_700_000_321L));
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(CPIODialect.NEW_ASCII_CRC)
                .withBlockSize(1);

        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("metadata.bin");
            CPIOArkivoEntryAttributeView view = requireView(entry);
            assertSame(view, entry.attributeView(BasicFileAttributeView.class));
            assertNull(entry.attributeView(PosixFileAttributeView.class));

            assertThrows(IllegalArgumentException.class, () -> view.setInode(-1L));
            assertThrows(IllegalArgumentException.class, () -> view.setUserId(-1L));
            assertThrows(IllegalArgumentException.class, () -> view.setGroupId(-1L));
            assertThrows(IllegalArgumentException.class, () -> view.setLinkCount(0L));
            assertThrows(IllegalArgumentException.class, () -> view.setMode(-1));
            assertThrows(IllegalArgumentException.class, () -> view.setMode(040755));
            assertThrows(IllegalArgumentException.class, () -> view.setDevice(-1L));
            assertThrows(IllegalArgumentException.class, () -> view.setRemoteDevice(-1L));
            assertThrows(IllegalArgumentException.class, () -> view.setDeviceNumbers(-1L, 0L));
            assertThrows(IllegalArgumentException.class, () -> view.setDeviceNumbers(0L, -1L));
            assertThrows(IllegalArgumentException.class, () -> view.setRemoteDeviceNumbers(-1L, 0L));
            assertThrows(IllegalArgumentException.class, () -> view.setRemoteDeviceNumbers(0L, -1L));
            assertThrows(IllegalArgumentException.class, () -> view.setSize(-1L));

            FileTime ignoredAccessTime = FileTime.from(Instant.ofEpochSecond(10L));
            FileTime ignoredCreationTime = FileTime.from(Instant.ofEpochSecond(20L));
            view.setTimes(null, ignoredAccessTime, ignoredCreationTime);
            assertEquals(FileTime.fromMillis(0L), view.readAttributes().lastModifiedTime());

            view.setTimes(modificationTime, ignoredAccessTime, ignoredCreationTime);
            view.setInode(31L);
            view.setUserId(32L);
            view.setGroupId(33L);
            view.setLinkCount(2L);
            view.setMode(0100600);
            view.setDeviceNumbers(34L, 35L);
            view.setRemoteDeviceNumbers(36L, 37L);
            view.setSize(expected.length);

            CPIOArkivoEntryAttributes snapshot = view.readAttributes();
            view.setUserId(42L);
            assertEquals(32L, snapshot.userId());
            assertEquals(modificationTime, snapshot.lastModifiedTime());
            assertEquals(modificationTime, snapshot.lastAccessTime());
            assertEquals(modificationTime, snapshot.creationTime());
            assertNull(snapshot.fileKey());

            try (OutputStream body = entry.openOutputStream()) {
                body.write(expected);
                assertThrows(IOException.class, () -> view.setUserId(43L));
            }
        }

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(target.toByteArray())
        )) {
            assertTrue(reader.next());
            CPIOArkivoEntryAttributes attributes = reader.readAttributes(CPIOArkivoEntryAttributes.class);
            assertEquals(42L, attributes.userId());
            assertEquals(34L, attributes.deviceMajor());
            assertEquals(35L, attributes.deviceMinor());
            assertEquals(36L, attributes.remoteDeviceMajor());
            assertEquals(37L, attributes.remoteDeviceMinor());
            assertEquals(modificationTime, attributes.lastAccessTime());
            assertEquals(modificationTime, attributes.creationTime());
            assertNull(attributes.fileKey());
            try (InputStream body = reader.openInputStream()) {
                assertEquals(0x80, body.read());
                assertEquals(0xff, body.read());
                assertEquals(-1, body.read());
                assertEquals(-1, body.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies each begin operation preserves its entry kind and fixed-body constraints.
    @Test
    void enforcesEntryKindSpecificModesAndBodies() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target)) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("empty", ""));
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("nul", "bad\0target"));

            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            CPIOArkivoEntryAttributeView directoryView = requireView(directory);
            assertThrows(IllegalArgumentException.class, () -> directoryView.setMode(0100644));
            assertThrows(IllegalArgumentException.class, () -> directoryView.setMode(0120777));
            assertThrows(IllegalStateException.class, directory::openOutputStream);
            directory.close();

            ArkivoStreamingWriter.Entry link = writer.beginSymbolicLink("link", "target");
            CPIOArkivoEntryAttributeView linkView = requireView(link);
            assertThrows(IllegalArgumentException.class, () -> linkView.setMode(0100644));
            assertThrows(IllegalArgumentException.class, () -> linkView.setMode(040755));
            assertThrows(IllegalStateException.class, link::openChannel);
            link.close();

            ArkivoStreamingWriter.Entry file = writer.beginFile("file");
            CPIOArkivoEntryAttributeView fileView = requireView(file);
            assertThrows(IllegalArgumentException.class, () -> fileView.setMode(0120777));
            file.close();
        }
    }

    /// Verifies pending snapshots expose only fields represented by their selected header dialect.
    @Test
    void exposesDialectSpecificPendingSnapshots() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                    .withDialect(dialect)
                    .withBinaryByteOrder(CPIOBinaryByteOrder.LITTLE_ENDIAN)
                    .withBlockSize(1);
            try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
                ArkivoStreamingWriter.Entry entry = writer.beginFile("pending.bin");
                CPIOArkivoEntryAttributeView view = requireView(entry);
                view.setDevice(11L);
                view.setRemoteDevice(12L);
                view.setDeviceNumbers(13L, 14L);
                view.setRemoteDeviceNumbers(15L, 16L);
                CPIOArkivoEntryAttributes attributes = view.readAttributes();

                assertEquals(
                        dialect == CPIODialect.OLD_BINARY ? CPIOBinaryByteOrder.LITTLE_ENDIAN : null,
                        attributes.binaryByteOrder()
                );
                if (dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC) {
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.device());
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDevice());
                    assertEquals(13L, attributes.deviceMajor());
                    assertEquals(14L, attributes.deviceMinor());
                    assertEquals(15L, attributes.remoteDeviceMajor());
                    assertEquals(16L, attributes.remoteDeviceMinor());
                } else {
                    assertEquals(11L, attributes.device());
                    assertEquals(12L, attributes.remoteDevice());
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMajor());
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMinor());
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMajor());
                    assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMinor());
                }
                assertEquals(
                        dialect == CPIODialect.NEW_ASCII_CRC
                                ? CPIOArkivoEntryAttributes.UNKNOWN_CHECKSUM
                                : CPIOArkivoEntryAttributes.NOT_STORED,
                        attributes.checksum()
                );
                entry.close();
            }
        }
    }

    /// Verifies unsafe paths and metadata that cannot be represented by the configured charset are rejected.
    @Test
    void rejectsUnsafeAndUnencodableMetadata() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target)) {
            for (String path : new String[]{"", ".", "./", "/absolute", "C:value", "../value", "a/../b"}) {
                assertThrows(IllegalArgumentException.class, () -> writer.beginFile(path), path);
            }
            writer.beginFile("valid\\normalized.bin").close();
        }

        assertEntryNameRejected(StandardCharsets.US_ASCII, "caf\u00e9", "Failed to encode");
        assertEntryNameRejected(StandardCharsets.UTF_16BE, "value", "contains a NUL byte");
        assertLinkTargetRejected(StandardCharsets.US_ASCII, "caf\u00e9", "Failed to encode");
        assertLinkTargetRejected(StandardCharsets.UTF_16BE, "target", "contains a NUL byte");
        assertNegativeTimestampRejected();
    }

    /// Verifies single-byte body I/O, unsupported attributes, repeated end detection, and idempotent reader close.
    @Test
    void enforcesSingleByteReaderCursorLifecycle() throws IOException {
        byte[] content = {0, 1, 0x7f, (byte) 0x80, (byte) 0xff};
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                archive,
                CPIOArchiveOptions.CREATE_DEFAULTS
                        .withDialect(CPIODialect.NEW_ASCII_CRC)
                        .withBlockSize(1)
        )) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("bytes.bin");
            try (OutputStream body = entry.openOutputStream()) {
                for (byte value : content) {
                    body.write(Byte.toUnsignedInt(value));
                }
                body.flush();
            }
        }

        CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray()),
                CPIOArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(bytes -> null)
        );
        assertThrows(IllegalStateException.class, reader::readAttributes);
        assertTrue(reader.next());
        assertThrows(
                UnsupportedOperationException.class,
                () -> reader.readAttributes(PosixFileAttributes.class)
        );
        InputStream body = reader.openInputStream();
        for (byte expected : content) {
            assertEquals(Byte.toUnsignedInt(expected), body.read());
        }
        assertEquals(-1, body.read());
        assertEquals(-1, body.read());
        body.close();
        body.close();
        assertThrows(IOException.class, body::read);
        assertThrows(IllegalStateException.class, reader::openInputStream);
        assertFalse(reader.next());
        assertFalse(reader.next());
        reader.close();
        reader.close();
    }

    /// Returns the required CPIO-specific mutable attribute view.
    private static CPIOArkivoEntryAttributeView requireView(ArkivoStreamingWriter.Entry entry) throws IOException {
        return Objects.requireNonNull(entry.attributeView(CPIOArkivoEntryAttributeView.class));
    }

    /// Verifies a pending entry name is rejected during header emission.
    private static void assertEntryNameRejected(Charset charset, String path, String messageFragment) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withMetadataCharset(charset)
                .withBlockSize(1);
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options);
        ArkivoStreamingWriter.Entry entry = writer.beginFile(path);
        IOException failure = assertThrows(IOException.class, entry::close);
        assertTrue(failure.getMessage().contains(messageFragment), failure.getMessage());
        assertThrows(IOException.class, writer::close);
        assertDoesNotThrow(writer::close);
    }

    /// Verifies a symbolic-link target is rejected before an entry becomes pending.
    private static void assertLinkTargetRejected(
            Charset charset,
            String targetPath,
            String messageFragment
    ) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withMetadataCharset(charset)
                .withBlockSize(1);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> writer.beginSymbolicLink("link", targetPath)
            );
            assertTrue(failure.getMessage().contains(messageFragment), failure.getMessage());
        }
    }

    /// Verifies negative modification times are rejected when the pending header is committed.
    private static void assertNegativeTimestampRejected() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("negative-time");
        requireView(entry).setTimes(FileTime.from(Instant.ofEpochSecond(-1L)), null, null);
        IOException failure = assertThrows(IOException.class, entry::close);
        assertTrue(failure.getMessage().contains("must not be negative"), failure.getMessage());
        assertThrows(IOException.class, writer::close);
        assertDoesNotThrow(writer::close);
    }
}
