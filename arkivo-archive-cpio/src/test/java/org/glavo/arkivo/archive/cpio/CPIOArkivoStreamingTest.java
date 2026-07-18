// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests native CPIO streaming behavior, metadata, corruption handling, and operation-scoped read limits.
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

    /// Verifies entry-count, entry-size, total-entry-size, and metadata-size limits.
    @Test
    public void enforcesAllStreamingReadLimits() throws IOException {
        byte[] archive = writeTwoFileArchive();

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumEntryCount(1L).build()
        )) {
            assertTrue(reader.next());
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.ENTRY_COUNT, 1L, 2L, null);
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumEntrySize(4L).build()
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.ENTRY_SIZE, 4L, 5L, "one.bin");
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumTotalEntrySize(6L).build()
        )) {
            assertTrue(reader.next());
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, 6L, 12L, "two.bin");
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumMetadataSize(119L).build()
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.METADATA_SIZE, 119L, 120L, null);
        }
    }

    /// Verifies reserved, unsafe, and non-canonical entry paths at the streaming boundary.
    @Test
    public void validatesAndCanonicalizesEntryPaths() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(output)) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("TRAILER!!!"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("unsafe\0name"));
            writer.beginFile("directory//./value.bin").close();
        }

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(output.toByteArray())
        )) {
            assertTrue(reader.next());
            assertEquals("directory/value.bin", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Verifies a zero name-size field is reported as malformed archive data.
    @Test
    public void rejectsZeroNameSizeAsIOException() throws IOException {
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII, new byte[0]);
        java.util.Arrays.fill(archive, 94, 102, (byte) '0');

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Verifies that physical EOF cannot replace the required CPIO trailer entry.
    @Test
    public void rejectsArchiveWithoutTrailer() throws IOException {
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII, new byte[]{1, 2, 3});
        int trailerNameOffset = indexOf(archive, "TRAILER!!!".getBytes(StandardCharsets.US_ASCII));
        assertTrue(trailerNameOffset >= 110);
        byte[] truncated = Arrays.copyOf(archive, trailerNameOffset - 110);

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(truncated)
        )) {
            assertTrue(reader.next());
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Verifies that a name field containing only its terminator is rejected.
    @Test
    public void rejectsEmptyStoredName() throws IOException {
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII, new byte[0]);
        System.arraycopy("00000001".getBytes(StandardCharsets.US_ASCII), 0, archive, 94, 8);

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Verifies CPIO-specific charset detection receives already parsed header context.
    @Test
    public void suppliesHeaderContextToMetadataCharsetDetector() throws IOException {
        byte[] content = {4, 5, 6};
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII_CRC, content);
        AtomicReference<Boolean> observed = new AtomicReference<>(false);
        CPIOMetadataCharsetDetector detector = context -> {
            assertEquals(CPIOMetadataCharsetDetector.MetadataKind.ENTRY_NAME, context.metadataKind());
            assertEquals(CPIODialect.NEW_ASCII_CRC, context.dialect());
            assertNull(context.binaryByteOrder());
            assertEquals(1L, context.inode());
            assertEquals(0100644, context.mode());
            assertEquals(content.length, context.entrySize());
            assertTrue(context.bytes().isReadOnly());
            byte[] name = new byte[context.bytes().remaining()];
            context.bytes().get(name);
            assertArrayEquals("payload.bin".getBytes(StandardCharsets.UTF_8), name);
            observed.set(true);
            return StandardCharsets.UTF_8;
        };

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                CPIOArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(detector)
        )) {
            assertTrue(reader.next());
        }

        assertTrue(observed.get());
    }

    /// Verifies pending snapshots distinguish unknown size and checksum from stored values.
    @Test
    public void reportsUnknownPendingSizeAndChecksum() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                output,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC)
        )) {
            var entry = writer.beginFile("pending.bin");
            CPIOArkivoEntryAttributeView view = Objects.requireNonNull(
                    entry.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            CPIOArkivoEntryAttributes attributes = view.readAttributes();
            assertEquals(CPIOArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
            assertEquals(CPIOArkivoEntryAttributes.UNKNOWN_CHECKSUM, attributes.checksum());
            assertThrows(IllegalArgumentException.class, () -> view.setMode(040755));
            entry.close();
        }
    }

    /// Verifies a failed body drain can continue from its exact progress on a repeated close.
    @Test
    public void retriesEntryBodyCloseAfterSourceFailure() throws IOException {
        byte[] content = new byte[32];
        byte[] archive = writeFileArchive(CPIODialect.NEW_ASCII_CRC, content);
        int bodyOffset = indexOf(archive, content);
        assertTrue(bodyOffset >= 0);
        FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + 5);

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(source)) {
            assertTrue(reader.next());
            InputStream body = reader.openInputStream();
            assertThrows(IOException.class, body::close);
            body.close();
            assertFalse(reader.next());
        }
    }

    /// Verifies invalid options are rejected before an existing output path is truncated.
    @Test
    public void validatesCreateOptionsBeforeOpeningPath() throws IOException {
        Path path = Files.createTempFile("arkivo-cpio-options-", ".cpio");
        try {
            byte[] original = {9, 8, 7};
            Files.write(path, original);
            assertThrows(NullPointerException.class, () -> CPIOArkivoStreamingWriter.create(path, null));
            assertArrayEquals(original, Files.readAllBytes(path));
        } finally {
            Files.deleteIfExists(path);
        }
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

    /// Writes two new-ASCII regular files for read-limit checks.
    private static byte[] writeTwoFileArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(output)) {
            var first = writer.beginFile("one.bin");
            try (OutputStream body = first.openOutputStream()) {
                body.write(new byte[5]);
            }
            var second = writer.beginFile("two.bin");
            try (OutputStream body = second.openOutputStream()) {
                body.write(new byte[7]);
            }
        }
        return output.toByteArray();
    }

    /// Opens one in-memory archive with the requested common read limits.
    private static CPIOArkivoStreamingReader openWithLimits(byte[] archive, ArchiveReadLimits limits) {
        return CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                CPIOArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withLimits(limits)
                )
        );
    }

    /// Verifies the structured fields of one read-limit failure.
    private static void assertLimit(
            ArkivoReadLimitException exception,
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        assertEquals(kind, exception.kind());
        assertEquals(maximum, exception.maximum());
        assertEquals(actual, exception.actual());
        assertEquals(entryPath, exception.entryPath());
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

    /// Supplies bytes while injecting one recoverable read failure at an exact source offset.
    @NotNullByDefault
    private static final class FailOnceInputStream extends InputStream {
        /// Immutable source bytes owned by this stream.
        private final byte @Unmodifiable [] source;

        /// Source offset at which one failure is injected.
        private final int failureOffset;

        /// Reusable storage for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// Current source offset.
        private int offset;

        /// Whether the configured failure has already been emitted.
        private boolean failed;

        /// Creates one fail-once source over a private byte-array copy.
        private FailOnceInputStream(byte[] source, int failureOffset) {
            this.source = Objects.requireNonNull(source, "source").clone();
            if (failureOffset < 0 || failureOffset > source.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.failureOffset = failureOffset;
        }

        /// Reads one byte or emits the configured failure.
        @Override
        public int read() throws IOException {
            int read = read(singleByte, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads up to the failure boundary and fails once when that boundary is reached.
        @Override
        public int read(byte[] bytes, int targetOffset, int length) throws IOException {
            Objects.checkFromIndexSize(targetOffset, length, bytes.length);
            if (!failed && offset == failureOffset) {
                failed = true;
                throw new IOException("Injected CPIO source failure");
            }
            if (length == 0) {
                return 0;
            }
            if (offset == source.length) {
                return -1;
            }
            int count = Math.min(length, source.length - offset);
            if (!failed && offset < failureOffset) {
                count = Math.min(count, failureOffset - offset);
            }
            System.arraycopy(source, offset, bytes, targetOffset, count);
            offset += count;
            return count;
        }
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
