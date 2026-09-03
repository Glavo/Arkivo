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
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests CPIO writer field boundaries, fixed bodies, and declared-size failure behavior.
@NotNullByDefault
public final class CPIOWriterBoundaryTest {
    /// Largest unsigned 16-bit old-binary field value.
    private static final long MAXIMUM_UNSIGNED_SHORT = 0xffffL;

    /// Largest unsigned 32-bit new-ASCII or old-binary field value.
    private static final long MAXIMUM_UNSIGNED_INT = 0xffff_ffffL;

    /// Largest six-digit old-ASCII octal field value.
    private static final long MAXIMUM_SIX_DIGIT_OCTAL = 0777777L;

    /// Largest eleven-digit old-ASCII octal field value.
    private static final long MAXIMUM_ELEVEN_DIGIT_OCTAL = 077777777777L;

    /// Verifies every dialect preserves its maximum representable metadata values.
    @Test
    public void roundTripsMaximumRepresentableMetadata() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            for (CPIOBinaryByteOrder byteOrder : byteOrders(dialect)) {
                long maximumSmallField = maximumSmallField(dialect);
                long maximumTime = maximumTime(dialect);
                byte[] archive = writeMaximumMetadataArchive(dialect, byteOrder);

                try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                        new ByteArrayInputStream(archive)
                )) {
                    assertTrue(reader.next());
                    CPIOArkivoEntryAttributes attributes = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                    assertEquals(maximumSmallField, attributes.inode());
                    assertEquals(maximumSmallField, attributes.userId());
                    assertEquals(maximumSmallField, attributes.groupId());
                    assertEquals(maximumSmallField, attributes.linkCount());
                    assertEquals(FileTime.from(Instant.ofEpochSecond(maximumTime)), attributes.lastModifiedTime());
                    assertEquals(0L, attributes.size());
                    if (isNewAscii(dialect)) {
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.device());
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDevice());
                        assertEquals(maximumSmallField, attributes.deviceMajor());
                        assertEquals(maximumSmallField, attributes.deviceMinor());
                        assertEquals(maximumSmallField, attributes.remoteDeviceMajor());
                        assertEquals(maximumSmallField, attributes.remoteDeviceMinor());
                    } else {
                        assertEquals(maximumSmallField, attributes.device());
                        assertEquals(maximumSmallField, attributes.remoteDevice());
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMajor());
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMinor());
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMajor());
                        assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMinor());
                    }
                    assertEquals(
                            dialect == CPIODialect.NEW_ASCII_CRC
                                    ? 0L : CPIOArkivoEntryAttributes.NOT_STORED,
                            attributes.checksum()
                    );
                    assertEquals(
                            dialect == CPIODialect.OLD_BINARY ? byteOrder : null,
                            attributes.binaryByteOrder()
                    );
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Verifies every dialect rejects its first unrepresentable metadata values at commit time.
    @Test
    public void rejectsMetadataBeyondDialectFieldRanges() throws IOException {
        long beyondUnsignedInt = MAXIMUM_UNSIGNED_INT + 1L;
        assertCommitRejected(CPIODialect.NEW_ASCII, view -> view.setInode(beyondUnsignedInt), "inode");
        assertCommitRejected(CPIODialect.NEW_ASCII, view -> view.setUserId(beyondUnsignedInt), "user id");
        assertCommitRejected(CPIODialect.NEW_ASCII, view -> view.setGroupId(beyondUnsignedInt), "group id");
        assertCommitRejected(CPIODialect.NEW_ASCII, view -> view.setLinkCount(beyondUnsignedInt), "link count");
        assertCommitRejected(
                CPIODialect.NEW_ASCII,
                view -> view.setDeviceNumbers(beyondUnsignedInt, 0L),
                "device major"
        );
        assertCommitRejected(
                CPIODialect.NEW_ASCII,
                view -> view.setDeviceNumbers(0L, beyondUnsignedInt),
                "device minor"
        );
        assertCommitRejected(
                CPIODialect.NEW_ASCII,
                view -> view.setRemoteDeviceNumbers(beyondUnsignedInt, 0L),
                "remote-device major"
        );
        assertCommitRejected(
                CPIODialect.NEW_ASCII,
                view -> view.setRemoteDeviceNumbers(0L, beyondUnsignedInt),
                "remote-device minor"
        );
        assertCommitRejected(
                CPIODialect.NEW_ASCII,
                view -> view.setTimes(FileTime.from(Instant.ofEpochSecond(beyondUnsignedInt)), null, null),
                "modification time"
        );

        long beyondOldAsciiSmall = MAXIMUM_SIX_DIGIT_OCTAL + 1L;
        assertOldFormatSmallFieldsRejected(CPIODialect.OLD_ASCII, beyondOldAsciiSmall);
        assertCommitRejected(
                CPIODialect.OLD_ASCII,
                view -> view.setTimes(
                        FileTime.from(Instant.ofEpochSecond(MAXIMUM_ELEVEN_DIGIT_OCTAL + 1L)),
                        null,
                        null
                ),
                "modification time"
        );

        long beyondOldBinarySmall = MAXIMUM_UNSIGNED_SHORT + 1L;
        assertOldFormatSmallFieldsRejected(CPIODialect.OLD_BINARY, beyondOldBinarySmall);
        assertCommitRejected(
                CPIODialect.OLD_BINARY,
                view -> view.setTimes(FileTime.from(Instant.ofEpochSecond(beyondUnsignedInt)), null, null),
                "modification time"
        );
    }

    /// Verifies an impossible declared body size is rejected before body storage is opened.
    @Test
    public void rejectsBodySizeBeyondEveryDialectRange() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            long maximumBodySize = dialect == CPIODialect.OLD_ASCII
                    ? MAXIMUM_ELEVEN_DIGIT_OCTAL
                    : MAXIMUM_UNSIGNED_INT;
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                    target,
                    CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(dialect).withBlockSize(1)
            )) {
                ArkivoStreamingWriter.Entry entry = writer.beginFile("bounded.bin");
                CPIOArkivoEntryAttributeView view = attributeView(entry);
                assertThrows(IllegalArgumentException.class, () -> view.setSize(maximumBodySize + 1L));
                view.setSize(0L);
                entry.close();
            }
            assertArrayEquals(new byte[0], readOnlyBody(target.toByteArray()));
        }
    }

    /// Verifies declared sizes reject overruns before mutation and underruns at body completion.
    @Test
    public void enforcesDeclaredRegularFileBodySize() throws IOException {
        ByteArrayOutputStream exactTarget = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(exactTarget)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("exact.bin");
            attributeView(entry).setSize(2L);
            try (OutputStream body = entry.openOutputStream()) {
                body.write(new byte[]{11, 22});
                assertThrows(IOException.class, () -> body.write(33));
            }
        }
        assertArrayEquals(new byte[]{11, 22}, readOnlyBody(exactTarget.toByteArray()));

        ByteArrayOutputStream shortTarget = new ByteArrayOutputStream();
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(shortTarget);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("short.bin");
        attributeView(entry).setSize(3L);
        WritableByteChannel body = entry.openChannel();
        assertEquals(2, body.write(ByteBuffer.wrap(new byte[]{44, 55})));
        IOException firstFailure = assertThrows(IOException.class, body::close);
        assertSame(firstFailure, assertThrows(IOException.class, body::close));
        assertSame(firstFailure, assertThrows(IOException.class, writer::close));
        assertDoesNotThrow(writer::close);
        assertArchiveIsEmpty(shortTarget.toByteArray());
    }

    /// Verifies fixed directory and symbolic-link bodies expose and enforce their actual sizes.
    @Test
    public void exposesAndEnforcesFixedEntryBodySizes() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target)) {
            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            CPIOArkivoEntryAttributeView directoryView = attributeView(directory);
            assertEquals(0L, directoryView.readAttributes().size());
            directoryView.setSize(0L);
            assertThrows(IOException.class, () -> directoryView.setSize(1L));
            directory.close();

            ArkivoStreamingWriter.Entry link = writer.beginSymbolicLink("link", "target");
            CPIOArkivoEntryAttributeView linkView = attributeView(link);
            assertEquals(6L, linkView.readAttributes().size());
            linkView.setSize(6L);
            assertThrows(IOException.class, () -> linkView.setSize(5L));
            link.close();
        }

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(target.toByteArray())
        )) {
            assertTrue(reader.next());
            assertTrue(reader.readAttributes().isDirectory());
            assertArrayEquals(new byte[0], readCurrentBody(reader));
            assertTrue(reader.next());
            assertTrue(reader.readAttributes().isSymbolicLink());
            assertArrayEquals("target".getBytes(java.nio.charset.StandardCharsets.UTF_8), readCurrentBody(reader));
            assertFalse(reader.next());
        }
    }

    /// Writes one empty entry containing maximum representable metadata for a dialect.
    private static byte[] writeMaximumMetadataArchive(
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder
    ) throws IOException {
        long maximumSmallField = maximumSmallField(dialect);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(dialect)
                .withBinaryByteOrder(byteOrder)
                .withBlockSize(1);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("maximum.bin");
            CPIOArkivoEntryAttributeView view = attributeView(entry);
            view.setInode(maximumSmallField);
            view.setUserId(maximumSmallField);
            view.setGroupId(maximumSmallField);
            view.setLinkCount(maximumSmallField);
            view.setTimes(FileTime.from(Instant.ofEpochSecond(maximumTime(dialect))), null, null);
            view.setDevice(maximumSmallField);
            view.setRemoteDevice(maximumSmallField);
            view.setDeviceNumbers(maximumSmallField, maximumSmallField);
            view.setRemoteDeviceNumbers(maximumSmallField, maximumSmallField);
            view.setSize(0L);
            entry.close();
        }
        return target.toByteArray();
    }

    /// Verifies all shared small fields of one old dialect reject the supplied value.
    private static void assertOldFormatSmallFieldsRejected(CPIODialect dialect, long value) throws IOException {
        assertCommitRejected(dialect, view -> view.setInode(value), "inode");
        assertCommitRejected(dialect, view -> view.setUserId(value), "user id");
        assertCommitRejected(dialect, view -> view.setGroupId(value), "group id");
        assertCommitRejected(dialect, view -> view.setLinkCount(value), "link count");
        assertCommitRejected(dialect, view -> view.setDevice(value), "device");
        assertCommitRejected(dialect, view -> view.setRemoteDevice(value), "remote device");
    }

    /// Verifies one positive but unrepresentable metadata value fails atomically during writer close.
    private static void assertCommitRejected(
            CPIODialect dialect,
            AttributeMutation mutation,
            String fieldName
    ) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                target,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(dialect).withBlockSize(1)
        );
        ArkivoStreamingWriter.Entry entry = writer.beginFile("invalid.bin");
        mutation.apply(attributeView(entry));
        IOException failure = assertThrows(IOException.class, writer::close, dialect + " " + fieldName);
        assertTrue(failure.getMessage().contains("out of range"), failure.getMessage());
        assertDoesNotThrow(writer::close);
        assertArchiveIsEmpty(target.toByteArray());
    }

    /// Verifies a completed archive contains no logical entries.
    private static void assertArchiveIsEmpty(byte[] archive) throws IOException {
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertFalse(reader.next());
        }
    }

    /// Reads the only entry body in an archive.
    private static byte[] readOnlyBody(byte[] archive) throws IOException {
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            byte[] body = readCurrentBody(reader);
            assertFalse(reader.next());
            return body;
        }
    }

    /// Reads the current entry body completely.
    private static byte[] readCurrentBody(CPIOArkivoStreamingReader reader) throws IOException {
        try (InputStream body = reader.openInputStream()) {
            return body.readAllBytes();
        }
    }

    /// Returns the required CPIO-specific mutable view for one pending entry.
    private static CPIOArkivoEntryAttributeView attributeView(ArkivoStreamingWriter.Entry entry) throws IOException {
        return Objects.requireNonNull(entry.attributeView(CPIOArkivoEntryAttributeView.class));
    }

    /// Returns the maximum common small metadata field for a dialect.
    private static long maximumSmallField(CPIODialect dialect) {
        return switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> MAXIMUM_UNSIGNED_INT;
            case OLD_ASCII -> MAXIMUM_SIX_DIGIT_OCTAL;
            case OLD_BINARY -> MAXIMUM_UNSIGNED_SHORT;
        };
    }

    /// Returns the maximum modification time for a dialect.
    private static long maximumTime(CPIODialect dialect) {
        return dialect == CPIODialect.OLD_ASCII
                ? MAXIMUM_ELEVEN_DIGIT_OCTAL
                : MAXIMUM_UNSIGNED_INT;
    }

    /// Returns whether a dialect stores split device numbers.
    private static boolean isNewAscii(CPIODialect dialect) {
        return dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC;
    }

    /// Returns the old-binary byte orders relevant to a dialect.
    private static List<CPIOBinaryByteOrder> byteOrders(CPIODialect dialect) {
        return dialect == CPIODialect.OLD_BINARY
                ? List.of(CPIOBinaryByteOrder.values())
                : List.of(CPIOBinaryByteOrder.BIG_ENDIAN);
    }

    /// Applies one checked mutation to pending CPIO entry metadata.
    @FunctionalInterface
    @NotNullByDefault
    private interface AttributeMutation {
        /// Applies the metadata mutation.
        ///
        /// @param view the pending entry view to mutate
        /// @throws IOException if the view rejects the value immediately
        void apply(CPIOArkivoEntryAttributeView view) throws IOException;
    }
}
