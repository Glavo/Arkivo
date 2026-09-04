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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests CPIO wire-format boundaries and malformed input independently of interoperability fixtures.
@NotNullByDefault
public final class CPIOWireFormatRobustnessTest {
    /// Widths of the numeric fields following a new portable ASCII magic value.
    private static final int @Unmodifiable [] NEW_ASCII_FIELD_WIDTHS = {
            8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8
    };

    /// Widths of the numeric fields following an old portable ASCII magic value.
    private static final int @Unmodifiable [] OLD_ASCII_FIELD_WIDTHS = {
            6, 6, 6, 6, 6, 6, 6, 11, 6, 11
    };

    /// Verifies that every prefix ending before the complete trailer is rejected as truncated.
    @Test
    public void rejectsEveryTruncationBeforeTrailerCompletion() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            for (CPIOBinaryByteOrder byteOrder : byteOrders(dialect)) {
                byte[] archive = writeArchive(dialect, byteOrder, 1);
                String variant = dialect + "/" + byteOrder;
                for (int length = 0; length < archive.length; length++) {
                    byte[] prefix = Arrays.copyOf(archive, length);
                    assertThrows(
                            IOException.class,
                            () -> readEntireArchive(prefix),
                            variant + " prefix length " + length
                    );
                }
                assertDoesNotThrow(() -> readEntireArchive(archive), variant);
            }
        }
    }

    /// Verifies that every fixed-width ASCII numeric field rejects a non-radix digit.
    @Test
    public void rejectsInvalidDigitInEveryAsciiNumericField() throws IOException {
        assertEveryAsciiFieldRejectsInvalidDigit(CPIODialect.NEW_ASCII, NEW_ASCII_FIELD_WIDTHS, (byte) 'g');
        assertEveryAsciiFieldRejectsInvalidDigit(CPIODialect.NEW_ASCII_CRC, NEW_ASCII_FIELD_WIDTHS, (byte) 'g');
        assertEveryAsciiFieldRejectsInvalidDigit(CPIODialect.OLD_ASCII, OLD_ASCII_FIELD_WIDTHS, (byte) '8');
    }

    /// Verifies that every dialect rejects zero and one as impossible stored name sizes.
    @Test
    public void rejectsImpossibleNameSizesInEveryDialect() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            for (CPIOBinaryByteOrder byteOrder : byteOrders(dialect)) {
                byte[] archive = writeArchive(dialect, byteOrder, 1);
                for (int nameSize : new int[]{0, 1}) {
                    byte[] malformed = archive.clone();
                    switch (dialect) {
                        case NEW_ASCII, NEW_ASCII_CRC -> writeAsciiNumber(malformed, 94, 8, nameSize, 16);
                        case OLD_ASCII -> writeAsciiNumber(malformed, 59, 6, nameSize, 8);
                        case OLD_BINARY -> writeBinaryShort(malformed, 20, nameSize, byteOrder);
                    }
                    assertFirstEntryRejected(malformed, dialect + "/" + byteOrder + " name size " + nameSize);
                }
            }
        }
    }

    /// Verifies names must be terminated, NUL-free, and valid in the selected fallback charset.
    @Test
    public void rejectsMalformedNameTerminationAndEncoding() throws IOException {
        byte[] archive = writeArchive(CPIODialect.NEW_ASCII_CRC, CPIOBinaryByteOrder.BIG_ENDIAN, 1);
        int nameOffset = headerSize(CPIODialect.NEW_ASCII_CRC);

        byte[] unterminated = archive.clone();
        unterminated[nameOffset + "payload.bin".length()] = (byte) 'x';
        assertFirstEntryRejected(unterminated, "unterminated entry name");

        byte[] embeddedNul = archive.clone();
        embeddedNul[nameOffset + 3] = 0;
        assertFirstEntryRejected(embeddedNul, "embedded NUL entry name");

        byte[] malformedUtf8 = archive.clone();
        malformedUtf8[nameOffset] = (byte) 0xff;
        assertFirstEntryRejected(malformedUtf8, "malformed UTF-8 entry name");
    }

    /// Verifies out-of-range modes and semantically invalid trailer fields are rejected.
    @Test
    public void rejectsInvalidModesAndTrailerMetadata() throws IOException {
        byte[] archive = writeArchive(CPIODialect.NEW_ASCII_CRC, CPIOBinaryByteOrder.BIG_ENDIAN, 1);

        byte[] invalidMagic = archive.clone();
        invalidMagic[0] = (byte) 'x';
        assertFirstEntryRejected(invalidMagic, "unrecognized header magic");

        byte[] invalidMode = archive.clone();
        Arrays.fill(invalidMode, 14, 22, (byte) 'f');
        assertFirstEntryRejected(invalidMode, "mode wider than a signed int");

        int trailerNameOffset = indexOf(archive, "TRAILER!!!".getBytes(StandardCharsets.US_ASCII));
        assertTrue(trailerNameOffset >= headerSize(CPIODialect.NEW_ASCII_CRC));
        int trailerHeaderOffset = trailerNameOffset - headerSize(CPIODialect.NEW_ASCII_CRC);

        byte[] nonEmptyTrailer = archive.clone();
        writeAsciiNumber(nonEmptyTrailer, trailerHeaderOffset + 54, 8, 1, 16);
        assertArchiveRejected(nonEmptyTrailer, "trailer body");

        byte[] checksummedTrailer = archive.clone();
        writeAsciiNumber(checksummedTrailer, trailerHeaderOffset + 102, 8, 1, 16);
        assertArchiveRejected(checksummedTrailer, "trailer checksum");
    }

    /// Verifies metadata-only root entries are skipped while root entries with bodies are rejected.
    @Test
    public void handlesConventionalRootEntries() throws IOException {
        byte[] rootName = "./././././.".getBytes(StandardCharsets.US_ASCII);
        assertEquals("payload.bin".length(), rootName.length);

        byte[] emptyRoot = writeEmptyArchive(CPIODialect.NEW_ASCII, 0100644);
        System.arraycopy(rootName, 0, emptyRoot, headerSize(CPIODialect.NEW_ASCII), rootName.length);
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(emptyRoot)
        )) {
            assertFalse(reader.next());
            assertFalse(reader.next());
        }

        byte[] nonEmptyRoot = writeArchive(CPIODialect.NEW_ASCII, CPIOBinaryByteOrder.BIG_ENDIAN, 1);
        System.arraycopy(rootName, 0, nonEmptyRoot, headerSize(CPIODialect.NEW_ASCII), rootName.length);
        assertArchiveRejected(nonEmptyRoot, "non-empty root entry");
    }

    /// Verifies that raw unsafe entry names are rejected for every dialect before publication.
    @Test
    public void rejectsUnsafeStoredNamesInEveryDialect() throws IOException {
        List<String> unsafeNames = List.of(
                "/ayload.bin",
                "../evil.bin",
                "..\\evil.bin",
                "C:evil.bin!"
        );
        for (CPIODialect dialect : CPIODialect.values()) {
            for (CPIOBinaryByteOrder byteOrder : byteOrders(dialect)) {
                byte[] archive = writeArchive(dialect, byteOrder, 1);
                int nameOffset = headerSize(dialect);
                for (String unsafeName : unsafeNames) {
                    byte[] encodedName = unsafeName.getBytes(StandardCharsets.US_ASCII);
                    assertEquals("payload.bin".length(), encodedName.length);
                    byte[] malformed = archive.clone();
                    System.arraycopy(encodedName, 0, malformed, nameOffset, encodedName.length);
                    assertFirstEntryRejected(malformed, dialect + "/" + byteOrder + " name " + unsafeName);
                }
            }
        }
    }

    /// Verifies that advancing without opening a CRC entry body still drains and validates it.
    @Test
    public void validatesCrcWhenSkippingAnUnopenedBody() throws IOException {
        byte[] archive = writeArchive(CPIODialect.NEW_ASCII_CRC, CPIOBinaryByteOrder.BIG_ENDIAN, 1);
        int bodyOffset = 124;
        archive[bodyOffset + 2] ^= 0x20;

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Verifies special POSIX types are reported as other and cannot carry entry data.
    @Test
    public void classifiesSpecialTypesAndRejectsTheirBodies() throws IOException {
        for (int mode : new int[]{0010644, 0020644, 0060644, 0140644}) {
            byte[] emptyArchive = writeEmptyArchive(CPIODialect.NEW_ASCII, mode);
            try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                    new ByteArrayInputStream(emptyArchive)
            )) {
                assertTrue(reader.next());
                CPIOArkivoEntryAttributes attributes = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                assertTrue(attributes.isOther(), Integer.toOctalString(mode));
            }

            byte[] nonEmptyArchive = writeArchive(CPIODialect.NEW_ASCII, CPIOBinaryByteOrder.BIG_ENDIAN, 1);
            writeAsciiNumber(nonEmptyArchive, 14, 8, mode, 16);
            assertFirstEntryRejected(nonEmptyArchive, "non-empty special mode " + Integer.toOctalString(mode));
        }
    }

    /// Verifies custom final block sizes while preserving a readable trailer boundary.
    @Test
    public void padsArchiveToEveryConfiguredBlockBoundary() throws IOException {
        for (int blockSize : new int[]{1, 2, 3, 7, 64, 511, 512, 513}) {
            byte[] archive = writeArchive(CPIODialect.NEW_ASCII_CRC, CPIOBinaryByteOrder.BIG_ENDIAN, blockSize);
            assertEquals(0, archive.length % blockSize, "block size " + blockSize);
            assertDoesNotThrow(() -> readEntireArchive(archive), "block size " + blockSize);
        }
    }

    /// Writes one deterministic archive using the selected wire-format settings.
    private static byte[] writeArchive(
            CPIODialect dialect,
            CPIOBinaryByteOrder byteOrder,
            int blockSize
    ) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(dialect)
                .withBinaryByteOrder(byteOrder)
                .withBlockSize(blockSize);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
            var entry = writer.beginFile("payload.bin");
            try (OutputStream body = entry.openOutputStream()) {
                body.write(new byte[]{1, 3, 5, 7, 9});
            }
        }
        return target.toByteArray();
    }

    /// Writes one empty entry and replaces its regular-file mode with the requested raw mode.
    private static byte[] writeEmptyArchive(CPIODialect dialect, int mode) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArchiveOptions.Create options = CPIOArchiveOptions.CREATE_DEFAULTS
                .withDialect(dialect)
                .withBlockSize(1);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options)) {
            writer.beginFile("payload.bin").close();
        }
        byte[] archive = target.toByteArray();
        writeAsciiNumber(archive, 14, 8, mode, 16);
        return archive;
    }

    /// Reads every entry body and requires the archive to reach its trailer normally.
    private static void readEntireArchive(byte[] archive) throws IOException {
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            while (reader.next()) {
                try (InputStream body = reader.openInputStream()) {
                    body.transferTo(OutputStream.nullOutputStream());
                }
            }
        }
    }

    /// Verifies every ASCII field in the first header rejects one invalid digit.
    private static void assertEveryAsciiFieldRejectsInvalidDigit(
            CPIODialect dialect,
            int[] fieldWidths,
            byte invalidDigit
    ) throws IOException {
        byte[] archive = writeArchive(dialect, CPIOBinaryByteOrder.BIG_ENDIAN, 1);
        int offset = 6;
        for (int fieldIndex = 0; fieldIndex < fieldWidths.length; fieldIndex++) {
            int width = fieldWidths[fieldIndex];
            byte[] malformed = archive.clone();
            malformed[offset + width / 2] = invalidDigit;
            assertFirstEntryRejected(malformed, dialect + " field " + fieldIndex);
            offset += width;
        }
        assertEquals(headerSize(dialect), offset);
    }

    /// Requires the first entry header to be rejected as malformed.
    private static void assertFirstEntryRejected(byte[] archive, String description) throws IOException {
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertThrows(IOException.class, reader::next, description);
        }
    }

    /// Requires an archive to fail before reaching its logical trailer boundary.
    private static void assertArchiveRejected(byte[] archive, String description) {
        assertThrows(IOException.class, () -> readEntireArchive(archive), description);
    }

    /// Returns the first offset of a byte sequence, or `-1` when it is absent.
    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int offset = 0; offset <= source.length - target.length; offset++) {
            for (int index = 0; index < target.length; index++) {
                if (source[offset + index] != target[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    /// Writes one non-negative fixed-width ASCII integer into an archive header.
    private static void writeAsciiNumber(
            byte[] archive,
            int offset,
            int width,
            int value,
            int radix
    ) {
        String text = Integer.toString(value, radix);
        Arrays.fill(archive, offset, offset + width, (byte) '0');
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, archive, offset + width - encoded.length, encoded.length);
    }

    /// Writes one unsigned old-binary 16-bit value in the selected byte order.
    private static void writeBinaryShort(
            byte[] archive,
            int offset,
            int value,
            CPIOBinaryByteOrder byteOrder
    ) {
        if (byteOrder == CPIOBinaryByteOrder.BIG_ENDIAN) {
            archive[offset] = (byte) (value >>> 8);
            archive[offset + 1] = (byte) value;
        } else {
            archive[offset] = (byte) value;
            archive[offset + 1] = (byte) (value >>> 8);
        }
    }

    /// Returns the old-binary byte orders relevant to a dialect.
    private static List<CPIOBinaryByteOrder> byteOrders(CPIODialect dialect) {
        return dialect == CPIODialect.OLD_BINARY
                ? List.of(CPIOBinaryByteOrder.values())
                : List.of(CPIOBinaryByteOrder.BIG_ENDIAN);
    }

    /// Returns the fixed header size for a dialect.
    private static int headerSize(CPIODialect dialect) {
        return switch (dialect) {
            case NEW_ASCII, NEW_ASCII_CRC -> 110;
            case OLD_ASCII -> 76;
            case OLD_BINARY -> 26;
        };
    }
}
