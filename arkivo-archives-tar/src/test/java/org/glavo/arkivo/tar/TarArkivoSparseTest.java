// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies GNU PAX sparse TAR expansion, validation, and file system integration.
@NotNullByDefault
final class TarArkivoSparseTest {
    /// The expanded content represented by the standard sparse test map.
    private static final byte[] EXPANDED_CONTENT = {
            0, 0, 'a', 'b', 'c', 0, 0, 0, 'd', 'e', 0, 0
    };

    /// The packed non-hole bytes represented by the standard sparse test map.
    private static final byte[] PACKED_CONTENT = {'a', 'b', 'c', 'd', 'e'};

    /// Verifies GNU sparse format 0.0 repeated PAX map records.
    @Test
    void readsPaxSparseVersion00() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.offset", "2"),
                        Map.entry("GNU.sparse.numbytes", "3"),
                        Map.entry("GNU.sparse.offset", "8"),
                        Map.entry("GNU.sparse.numbytes", "2")
                ),
                PACKED_CONTENT,
                false
        );
        assertSparseArchive(archive);
    }

    /// Verifies GNU sparse format 0.1 comma-separated maps and long logical holes.
    @Test
    void readsPaxSparseVersion01WithLongHole() throws IOException {
        long dataOffset = 5_000_000_000L;
        long logicalSize = dataOffset + 2L;
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", Long.toString(logicalSize)),
                        Map.entry("GNU.sparse.numblocks", "1"),
                        Map.entry("GNU.sparse.name", "large.bin"),
                        Map.entry("GNU.sparse.map", dataOffset + ",1")
                ),
                new byte[]{'x'},
                false
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
            assertEquals(logicalSize, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                assertEquals(dataOffset, body.skip(dataOffset));
                assertEquals('x', body.read());
                assertEquals(0, body.read());
                assertEquals(-1, body.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies GNU sparse 1.0 body maps and advancing after a partially consumed sparse entry.
    @Test
    void readsPaxSparseVersion10AndSkipsRemainingPackedData() throws IOException {
        byte[] sparseBody = sparseVersion10Body("2\n2\n3\n8\n2\n", PACKED_CONTENT);
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.major", "1"),
                        Map.entry("GNU.sparse.minor", "0"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.realsize", "12")
                ),
                sparseBody,
                true
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("value.bin", attributes.path());
            assertEquals(EXPANDED_CONTENT.length, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{0, 0, 'a', 'b'}, body.readNBytes(4));
            }

            assertTrue(reader.next());
            assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies that indexed TAR file systems expose expanded sparse content.
    @Test
    void exposesExpandedSparseContentThroughFileSystem() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.map", "2,3,8,2")
                ),
                PACKED_CONTENT,
                false
        );
        Path archivePath = Files.createTempFile("arkivo-sparse-", ".tar");
        try {
            Files.write(archivePath, archive);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                Path entry = fileSystem.getPath("/value.bin");
                assertEquals(EXPANDED_CONTENT.length, Files.size(entry));
                assertArrayEquals(EXPANDED_CONTENT, Files.readAllBytes(entry));
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies rejection of overlapping sparse blocks and mismatched packed sizes.
    @Test
    void rejectsInvalidPaxSparseMaps() throws IOException {
        byte[] overlapping = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.map", "2,4,5,1")
                ),
                PACKED_CONTENT,
                false
        );
        IOException overlapException = assertThrows(IOException.class, () -> readFirstEntry(overlapping));
        assertTrue(overlapException.getMessage().contains("overlap"));

        byte[] sizeMismatch = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "1"),
                        Map.entry("GNU.sparse.map", "2,4")
                ),
                PACKED_CONTENT,
                false
        );
        IOException sizeException = assertThrows(IOException.class, () -> readFirstEntry(sizeMismatch));
        assertTrue(sizeException.getMessage().contains("packed size mismatch"));

        byte[] oversizedCount = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", Integer.toString(Integer.MAX_VALUE)),
                        Map.entry("GNU.sparse.map", "2,3")
                ),
                PACKED_CONTENT,
                false
        );
        IOException countException = assertThrows(IOException.class, () -> readFirstEntry(oversizedCount));
        assertTrue(countException.getMessage().contains("wrong block count"));
    }

    /// Verifies rejection of non-null GNU sparse 1.0 map padding.
    @Test
    void rejectsInvalidPaxSparseVersion10Padding() throws IOException {
        byte[] sparseBody = sparseVersion10Body("0\n", new byte[0]);
        sparseBody[2] = 1;
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.major", "1"),
                        Map.entry("GNU.sparse.minor", "0"),
                        Map.entry("GNU.sparse.realsize", "0")
                ),
                sparseBody,
                false
        );
        IOException exception = assertThrows(IOException.class, () -> readFirstEntry(archive));
        assertTrue(exception.getMessage().contains("padding"));
    }

    /// Reads and verifies the standard sparse archive fixture.
    private static void assertSparseArchive(byte[] archive) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("value.bin", attributes.path());
            assertEquals(EXPANDED_CONTENT.length, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(EXPANDED_CONTENT, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Advances one reader to its first entry.
    private static void readFirstEntry(byte[] archive) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            reader.next();
        }
    }

    /// Creates a PAX sparse archive and optionally appends a regular entry.
    private static byte[] paxSparseArchive(
            @Unmodifiable List<Map.Entry<String, String>> paxRecords,
            byte[] storedBody,
            boolean appendRegularEntry
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, paxRecords);
        writeHeader(output, "GNUSparseFile/entry", storedBody.length, '0');
        writeBody(output, storedBody);
        if (appendRegularEntry) {
            byte[] content = "next".getBytes(StandardCharsets.UTF_8);
            writeHeader(output, "next.txt", content.length, '0');
            writeBody(output, content);
        }
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    /// Creates a GNU sparse 1.0 body containing a padded textual map and packed data.
    private static byte[] sparseVersion10Body(String map, byte[] packedContent) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] mapBytes = map.getBytes(StandardCharsets.US_ASCII);
        output.write(mapBytes);
        output.write(new byte[(512 - mapBytes.length % 512) % 512]);
        output.write(packedContent);
        return output.toByteArray();
    }

    /// Writes one PAX extended header with ordered records that may repeat keys.
    private static void writePaxHeader(
            ByteArrayOutputStream output,
            @Unmodifiable List<Map.Entry<String, String>> records
    ) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        byte[] bodyBytes = body.toByteArray();
        writeHeader(output, "PaxHeaders/entry", bodyBytes.length, 'x');
        writeBody(output, bodyBytes);
    }

    /// Returns one encoded PAX key-value record.
    private static byte[] paxRecord(String key, String value) {
        String payload = key + "=" + value + "\n";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int digits = 1;
        while (true) {
            int length = digits + 1 + payloadBytes.length;
            int actualDigits = Integer.toString(length).length();
            if (actualDigits == digits) {
                return (length + " " + payload).getBytes(StandardCharsets.UTF_8);
            }
            digits = actualDigits;
        }
    }

    /// Writes a TAR body followed by 512-byte record padding.
    private static void writeBody(ByteArrayOutputStream output, byte[] content) throws IOException {
        output.write(content);
        output.write(new byte[(512 - content.length % 512) % 512]);
    }

    /// Writes one minimal checksummed USTAR header.
    private static void writeHeader(
            ByteArrayOutputStream output,
            String path,
            int size,
            int typeFlag
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) typeFlag;
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes a null-terminated string field.
    private static void writeString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("value is too long");
        }
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /// Writes a fixed-width string field.
    private static void writeRawString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != length) {
            throw new IllegalArgumentException("value must match the field length");
        }
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /// Writes a zero-terminated octal number field.
    private static void writeOctal(byte[] target, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        int start = offset + length - text.length() - 1;
        for (int index = offset; index < start; index++) {
            target[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, start, bytes.length);
    }

    /// Writes the USTAR checksum field.
    private static void writeChecksum(byte[] header, int checksum) {
        byte[] bytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, bytes.length);
        header[154] = 0;
        header[155] = ' ';
    }
}
