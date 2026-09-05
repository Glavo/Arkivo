// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cross-dialect CPIO streaming and corruption handling.
@NotNullByDefault
public final class CPIOArkivoStreamingTest {
    /// The modification timestamp stored by round-trip fixtures.
    private static final long MODIFICATION_TIME_SECONDS = 1_700_000_123L;

    /// Verifies every dialect through one-byte readable and writable channel fragments.
    @Test
    public void roundTripsEveryDialectThroughFragmentedChannels() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            CPIOBinaryByteOrder[] byteOrders = dialect == CPIODialect.OLD_BINARY
                    ? CPIOBinaryByteOrder.values()
                    : new CPIOBinaryByteOrder[]{CPIOBinaryByteOrder.BIG_ENDIAN};
            for (CPIOBinaryByteOrder byteOrder : byteOrders) {
                byte[] archive = writeRepresentativeArchive(dialect, byteOrder);
                if (dialect == CPIODialect.OLD_BINARY) {
                    int expectedFirst = byteOrder == CPIOBinaryByteOrder.BIG_ENDIAN ? 0x71 : 0xc7;
                    int expectedSecond = byteOrder == CPIOBinaryByteOrder.BIG_ENDIAN ? 0xc7 : 0x71;
                    assertEquals(expectedFirst, Byte.toUnsignedInt(archive[0]), byteOrder.name());
                    assertEquals(expectedSecond, Byte.toUnsignedInt(archive[1]), byteOrder.name());
                }
                verifyRepresentativeArchive(archive, dialect, byteOrder);
            }
        }
    }

    /// Verifies that changing CRC-protected entry data is detected at the entry boundary.
    @Test
    public void rejectsCorruptedCrcEntryData() throws IOException {
        byte[] content = "unique-crc-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII_CRC, content);
        int bodyOffset = indexOf(archive, content);
        assertTrue(bodyOffset >= 0);
        archive[bodyOffset + 3] ^= 0x40;

        IOException exception = assertThrows(IOException.class, () -> {
            try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next());
                try (InputStream body = reader.openInputStream()) {
                    body.readAllBytes();
                }
            }
        });
        assertTrue(exception.getMessage().contains("checksum"));
    }

    /// Writes a directory, regular file, and symbolic link with representative CPIO metadata.
    private static byte[] writeRepresentativeArchive(
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WritableByteChannel fragmentedTarget = new FragmentedWritableByteChannel(output, 1);
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(dialect)
                .withBinaryByteOrder(byteOrder);
        byte[] content = representativeContent();

        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(fragmentedTarget, options)) {
            var directory = writer.beginDirectory("dir");
            CPIOArkivoEntryAttributeView directoryAttributes = Objects.requireNonNull(
                    directory.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            directoryAttributes.setInode(0x1233L);
            directoryAttributes.setMode(040750);
            directory.close();

            var file = writer.beginFile("dir/data.bin");
            CPIOArkivoEntryAttributeView fileAttributes = Objects.requireNonNull(
                    file.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            fileAttributes.setTimes(FileTime.from(Instant.ofEpochSecond(MODIFICATION_TIME_SECONDS)), null, null);
            fileAttributes.setInode(0x1234L);
            fileAttributes.setUserId(321L);
            fileAttributes.setGroupId(654L);
            fileAttributes.setLinkCount(2L);
            fileAttributes.setMode(0100600);
            fileAttributes.setDevice(11L);
            fileAttributes.setRemoteDevice(12L);
            fileAttributes.setDeviceNumbers(13L, 14L);
            fileAttributes.setRemoteDeviceNumbers(15L, 16L);
            fileAttributes.setSize(content.length);
            try (OutputStream body = file.openOutputStream()) {
                for (int offset = 0; offset < content.length; offset += 7) {
                    body.write(content, offset, Math.min(7, content.length - offset));
                }
            }

            var link = writer.beginSymbolicLink("dir/link", "data.bin");
            CPIOArkivoEntryAttributeView linkAttributes = Objects.requireNonNull(
                    link.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            linkAttributes.setInode(0x1235L);
            link.close();
        }
        return output.toByteArray();
    }

    /// Reads and verifies the representative archive through a one-byte source channel.
    private static void verifyRepresentativeArchive(
            byte[] archive,
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder
    ) throws IOException {
        byte[] content = representativeContent();
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new FragmentedReadableByteChannel(archive, 1)
        )) {
            assertTrue(reader.next(), dialect.name());
            CPIOArkivoEntryAttributes directory = reader.readAttributes(CPIOArkivoEntryAttributes.class);
            assertEquals("dir", directory.path());
            assertEquals(dialect, directory.dialect());
            assertEquals(dialect == CPIODialect.OLD_BINARY ? byteOrder : null, directory.binaryByteOrder());
            assertEquals(0x1233L, directory.inode());
            assertEquals(040750, directory.mode());
            assertTrue(directory.isDirectory());
            assertFalse(directory.isRegularFile());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(new byte[0], body.readAllBytes());
            }

            assertTrue(reader.next(), dialect.name());
            CPIOArkivoEntryAttributes file = reader.readAttributes(CPIOArkivoEntryAttributes.class);
            assertEquals("dir/data.bin", file.path());
            assertEquals(dialect, file.dialect());
            assertEquals(dialect == CPIODialect.OLD_BINARY ? byteOrder : null, file.binaryByteOrder());
            assertEquals(0x1234L, file.inode());
            assertEquals(321L, file.userId());
            assertEquals(654L, file.groupId());
            assertEquals(2L, file.linkCount());
            assertEquals(0100600, file.mode());
            assertEquals(FileTime.from(Instant.ofEpochSecond(MODIFICATION_TIME_SECONDS)), file.lastModifiedTime());
            assertEquals(content.length, file.size());
            assertTrue(file.isRegularFile());
            assertFalse(file.isDirectory());
            assertDialectSpecificAttributes(file, dialect, byteOrder, checksum(content));
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }

            assertTrue(reader.next(), dialect.name());
            CPIOArkivoEntryAttributes link = reader.readAttributes(CPIOArkivoEntryAttributes.class);
            assertEquals("dir/link", link.path());
            assertEquals(0120777, link.mode());
            assertTrue(link.isSymbolicLink());
            assertFalse(link.isRegularFile());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(
                        "data.bin".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        body.readAllBytes()
                );
            }
            assertFalse(reader.next(), dialect.name());
        }
    }

    /// Verifies fields whose representation differs between old and new CPIO dialects.
    private static void assertDialectSpecificAttributes(
            CPIOArkivoEntryAttributes attributes,
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder,
            long expectedChecksum
    ) {
        if (dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC) {
            assertNull(attributes.binaryByteOrder());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.device());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDevice());
            assertEquals(13L, attributes.deviceMajor());
            assertEquals(14L, attributes.deviceMinor());
            assertEquals(15L, attributes.remoteDeviceMajor());
            assertEquals(16L, attributes.remoteDeviceMinor());
        } else {
            assertEquals(dialect == CPIODialect.OLD_BINARY ? byteOrder : null, attributes.binaryByteOrder());
            assertEquals(11L, attributes.device());
            assertEquals(12L, attributes.remoteDevice());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMajor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.deviceMinor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMajor());
            assertEquals(CPIOArkivoEntryAttributes.NOT_STORED, attributes.remoteDeviceMinor());
        }
        assertEquals(
                dialect == CPIODialect.NEW_ASCII_CRC
                        ? expectedChecksum : CPIOArkivoEntryAttributes.NOT_STORED,
                attributes.checksum()
        );
    }

    /// Creates deterministic content containing every unsigned byte value and an alignment tail.
    private static byte[] representativeContent() {
        byte[] content = new byte[259];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 37 + 11);
        }
        return content;
    }

    /// Writes one archive containing exactly one regular file.
    private static byte[] writeFileArchive(CPIODialect dialect, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                output,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(dialect)
        )) {
            var entry = writer.beginFile("payload.bin");
            try (OutputStream body = entry.openOutputStream()) {
                body.write(content);
            }
        }
        return output.toByteArray();
    }

    /// Calculates the unsigned 32-bit sum used by CRC CPIO entries.
    private static long checksum(byte[] content) {
        long checksum = 0L;
        for (byte value : content) {
            checksum = checksum + Byte.toUnsignedInt(value) & 0xffff_ffffL;
        }
        return checksum;
    }

    /// Finds the first occurrence of a byte sequence in another byte sequence.
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    /// Supplies a bounded in-memory source that returns only small channel fragments.
    @NotNullByDefault
    private static final class FragmentedReadableByteChannel implements ReadableByteChannel {
        /// Immutable source bytes owned by this channel.
        private final byte @Unmodifiable [] source;

        /// Maximum bytes returned by one read.
        private final int maximumFragmentSize;

        /// Current source offset.
        private int offset;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a fragmented readable channel over a private source copy.
        private FragmentedReadableByteChannel(byte[] source, int maximumFragmentSize) {
            this.source = Objects.requireNonNull(source, "source").clone();
            if (maximumFragmentSize <= 0) {
                throw new IllegalArgumentException("maximumFragmentSize must be positive");
            }
            this.maximumFragmentSize = maximumFragmentSize;
        }

        /// Reads at most the configured fragment size.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (offset == source.length) {
                return -1;
            }
            int length = Math.min(Math.min(target.remaining(), maximumFragmentSize), source.length - offset);
            target.put(source, offset, length);
            offset += length;
            return length;
        }

        /// Returns whether the channel accepts reads.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this in-memory channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Supplies a bounded in-memory target that accepts only small channel fragments.
    @NotNullByDefault
    private static final class FragmentedWritableByteChannel implements WritableByteChannel {
        /// Target receiving the accepted bytes.
        private final ByteArrayOutputStream target;

        /// Maximum bytes accepted by one write.
        private final int maximumFragmentSize;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a fragmented writable channel.
        private FragmentedWritableByteChannel(ByteArrayOutputStream target, int maximumFragmentSize) {
            this.target = Objects.requireNonNull(target, "target");
            if (maximumFragmentSize <= 0) {
                throw new IllegalArgumentException("maximumFragmentSize must be positive");
            }
            this.maximumFragmentSize = maximumFragmentSize;
        }

        /// Accepts at most the configured fragment size.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (!source.hasRemaining()) {
                return 0;
            }
            int length = Math.min(source.remaining(), maximumFragmentSize);
            byte[] fragment = new byte[length];
            source.get(fragment);
            target.write(fragment);
            return length;
        }

        /// Returns whether the channel accepts writes.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this in-memory channel.
        @Override
        public void close() {
            open = false;
        }
    }
}
