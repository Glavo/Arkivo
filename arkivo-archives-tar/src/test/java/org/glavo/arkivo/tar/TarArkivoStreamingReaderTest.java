// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests TAR streaming reader behavior.
@NotNullByDefault
public final class TarArkivoStreamingReaderTest {
    /// Verifies that regular files, directories, and symbolic links are read in stream order.
    @Test
    public void readsStreamingEntries() throws IOException {
        byte[] archive = tarArchive();
        ArrayList<String> paths = new ArrayList<>();

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes directory = reader.readAttributes(TarArkivoEntryAttributes.class);
            paths.add(directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(0755, directory.mode());

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            BasicFileAttributes basicFile = reader.readAttributes(BasicFileAttributes.class);
            paths.add(file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(5L, basicFile.size());
            assertEquals("user", file.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            paths.add(link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());

            assertEquals(false, reader.next());
        }

        assertEquals(List.of("dir/", "dir/hello.txt", "link"), paths);
    }

    /// Verifies that per-entry PAX metadata overrides fixed-width USTAR header fields.
    @Test
    public void readsPaxExtendedMetadata() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String path = "this/path/is/long/enough/to/require/pax/metadata/when/stored/by/portable/tar/tools/file.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "atime", "1893456001.5",
                "ctime", "1893456002.75",
                "mtime", "1893456000.25",
                "path", path,
                "size", Long.toString(content.length),
                "uname", "pax-user"
        ));
        writeHeader(output, "short-name", 0644, 1000, 1000, 0, '0', "", "user", "group");
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            assertEquals(5L, file.size());
            assertEquals("pax-user", file.userName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 250_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_001L, 500_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_002L, 750_000_000L), file.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that TAR entry paths cannot contain parent-directory segments.
    @Test
    public void rejectsParentSegmentEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "../evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR entry path must not contain .."));
        }
    }

    /// Verifies that TAR entry paths must be relative.
    @Test
    public void rejectsAbsoluteEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "/evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR entry path must be relative"));
        }
    }

    /// Verifies that backslash-separated parent-directory segments are rejected.
    @Test
    public void rejectsBackslashParentSegmentEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "..\\evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR entry path must not contain .."));
        }
    }

    /// Verifies that PAX path overrides must still contain a usable entry path.
    @Test
    public void rejectsDotOnlyPaxEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of("path", "."));
        writeEntry(output, "fallback.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR entry is missing a path"));
        }
    }

    /// Verifies that global PAX metadata applies until per-entry PAX metadata overrides it.
    @Test
    public void readsGlobalPaxMetadata() throws IOException {
        byte[] firstContent = "first".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of(
                "atime", "1893456010.25",
                "ctime", "1893456020.5",
                "gid", "222",
                "gname", "global-group",
                "mtime", "1893456000.125",
                "uid", "111",
                "uname", "global-user"
        ));
        writeEntry(output, "first.txt", firstContent);
        writePaxHeader(output, Map.of(
                "mtime", "1893456030.75",
                "uname", "entry-user"
        ));
        writeEntry(output, "second.txt", secondContent);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes first = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("first.txt", first.path());
            assertEquals(111L, first.userId());
            assertEquals(222L, first.groupId());
            assertEquals("global-user", first.userName());
            assertEquals("global-group", first.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 125_000_000L), first.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), first.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), first.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(firstContent, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes second = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("second.txt", second.path());
            assertEquals(111L, second.userId());
            assertEquals(222L, second.groupId());
            assertEquals("entry-user", second.userName());
            assertEquals("global-group", second.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_030L, 750_000_000L), second.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), second.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), second.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that negative fractional PAX timestamps are parsed relative to the Unix epoch.
    @Test
    public void readsNegativePaxTimestamps() throws IOException {
        byte[] content = "negative time".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "atime", "-0.25",
                "ctime", "-2",
                "mtime", "-1.5"
        ));
        writeEntry(output, "negative-time.txt", content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("negative-time.txt", file.path());
            assertEquals(Instant.ofEpochSecond(-2L, 500_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-1L, 750_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-2L), file.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that out-of-range PAX timestamps are reported as I/O errors.
    @Test
    public void rejectsOutOfRangePaxTimestamp() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "mtime", Long.toString(Long.MAX_VALUE)
        ));
        writeEntry(output, "out-of-range-pax-time.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR PAX mtime is out of range"));
        }
    }

    /// Verifies that malformed PAX record boundaries are rejected.
    @Test
    public void rejectsMalformedPaxRecordBoundary() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', "100 path=file.txt\n".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Invalid TAR PAX record boundary"));
        }
    }

    /// Verifies that TAR string fields preserve leading and trailing spaces.
    @Test
    public void preservesStringFieldSpaces() throws IOException {
        byte[] content = "spaces".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeHeader(output, " leading-and-trailing.txt ", 0644, 1000, 1000, content.length, '0', "", " user ", " group ");
        writeBody(output, content);
        writeHeader(output, " link-with-spaces ", 0777, 0, 0, 0, '2', " target-with-spaces ", "", "");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(" leading-and-trailing.txt ", file.path());
            assertEquals(" user ", file.userName());
            assertEquals(" group ", file.groupName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(" link-with-spaces ", link.path());
            assertEquals(" target-with-spaces ", link.linkName());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that GNU long path and long link metadata entries are applied to the following entries.
    @Test
    public void readsGnuLongMetadata() throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String path = "gnu/long/path/that/does/not/fit/in/the/plain/ustar/name/field/without/a/metadata/entry/file.txt";
        String target = "gnu/long/target/that/does/not/fit/in/the/plain/ustar/link/field/without/a/metadata/entry/file.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "././@LongLink", 'L', (path + "\0").getBytes(StandardCharsets.UTF_8));
        writeEntry(output, "short-name", content);
        writeMetadataEntry(output, "././@LongLink", 'K', (target + "\0").getBytes(StandardCharsets.UTF_8));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "short-target", "user", "group");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(target, link.linkName());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that base-256 encoded numeric fields are parsed.
    @Test
    public void readsBase256NumericFields() throws IOException {
        byte[] content = "base256".getBytes(StandardCharsets.UTF_8);
        long userId = 10_000_000_000L;
        long groupId = 20_000_000_000L;
        long modificationTime = 4_294_967_296L;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "binary-numbers.txt", 0640, userId, groupId, content.length, modificationTime);
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);

            assertEquals("binary-numbers.txt", file.path());
            assertEquals(0640, file.mode());
            assertEquals(userId, file.userId());
            assertEquals(groupId, file.groupId());
            assertEquals(content.length, file.size());
            assertEquals(Instant.ofEpochSecond(modificationTime), file.lastModifiedTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that out-of-range header timestamps are reported as I/O errors.
    @Test
    public void rejectsOutOfRangeHeaderTimestamp() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "out-of-range-header-time.txt", 0644, 1000, 1000, 0, Long.MAX_VALUE);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR modification time is out of range"));
        }
    }

    /// Verifies that legacy signed TAR header checksums are accepted.
    @Test
    public void readsSignedChecksumHeader() throws IOException {
        byte[] content = "signed".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeSignedChecksumHeader(output, "signed-checksum.txt", content.length);
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);

            assertEquals("signed-checksum.txt", file.path());
            assertEquals("u\u00ff", file.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that negative base-256 entry sizes are rejected as invalid TAR metadata.
    @Test
    public void rejectsNegativeBase256Size() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeNegativeBase256SizeHeader(output, "negative-size.txt");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("entry size must not be negative"));
        }
    }

    /// Verifies that PAX user and group IDs must be non-negative.
    @Test
    public void rejectsNegativePaxUserAndGroupIds() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "gid", "-2",
                "uid", "-1"
        ));
        writeEntry(output, "negative-ids.txt", "negative ids".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("TAR PAX uid must not be negative"));
        }
    }

    /// Verifies that a huge declared size cannot overflow body and padding skipping.
    @Test
    public void rejectsTruncatedEntryWhosePaddingOverflowsSizeSum() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "huge-size.txt", 0644, 1000, 1000, Long.MAX_VALUE, 1_893_456_000L);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            assertEquals(Long.MAX_VALUE, reader.readAttributes(TarArkivoEntryAttributes.class).size());

            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Unexpected end of TAR entry body"));
        }
    }

    /// Returns a small TAR archive.
    private static byte[] tarArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeHeader(output, "dir/", 0755, 0, 0, 0, '5', "", "user", "group");
        writeEntry(output, "dir/hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "dir/hello.txt", "user", "group");
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    /// Writes a regular file entry.
    private static void writeEntry(ByteArrayOutputStream output, String path, byte[] content) throws IOException {
        writeHeader(output, path, 0644, 1000, 1000, content.length, '0', "", "user", "group");
        writeBody(output, content);
    }

    /// Writes a TAR entry body with record padding.
    private static void writeBody(ByteArrayOutputStream output, byte[] content) throws IOException {
        output.write(content);
        int padding = (int) ((512 - (content.length % 512)) % 512);
        output.write(new byte[padding]);
    }

    /// Writes one metadata entry.
    private static void writeMetadataEntry(
            ByteArrayOutputStream output,
            String path,
            int typeFlag,
            byte[] body
    ) throws IOException {
        writeHeader(output, path, 0644, 0, 0, body.length, typeFlag, "", "", "");
        writeBody(output, body);
    }

    /// Writes one PAX extended header entry.
    private static void writePaxHeader(ByteArrayOutputStream output, Map<String, String> records) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records.entrySet()) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', body.toByteArray());
    }

    /// Writes one PAX global extended header entry.
    private static void writeGlobalPaxHeader(ByteArrayOutputStream output, Map<String, String> records)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records.entrySet()) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        writeMetadataEntry(output, "GlobalHead/entry", 'g', body.toByteArray());
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

    /// Writes one TAR header.
    private static void writeHeader(
            ByteArrayOutputStream output,
            String path,
            int mode,
            int userId,
            int groupId,
            int size,
            int typeFlag,
            String linkName,
            String userName,
            String groupName
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, userId);
        writeOctal(header, 116, 8, groupId);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) typeFlag;
        writeString(header, 157, 100, linkName);
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");
        writeString(header, 265, 32, userName);
        writeString(header, 297, 32, groupName);

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header whose numeric fields use base-256 encoding.
    private static void writeBase256Header(
            ByteArrayOutputStream output,
            String path,
            int mode,
            long userId,
            long groupId,
            long size,
            long modificationTime
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeBase256Number(header, 100, 8, mode);
        writeBase256Number(header, 108, 8, userId);
        writeBase256Number(header, 116, 8, groupId);
        writeBase256Number(header, 124, 12, size);
        writeBase256Number(header, 136, 12, modificationTime);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header with a negative base-256 size field.
    private static void writeNegativeBase256SizeHeader(ByteArrayOutputStream output, String path) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 1000);
        writeOctal(header, 116, 8, 1000);
        writeNegativeBase256Number(header, 124, 12);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header whose checksum uses signed byte arithmetic.
    private static void writeSignedChecksumHeader(ByteArrayOutputStream output, String path, int size) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 1000);
        writeOctal(header, 116, 8, 1000);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");
        header[265] = 'u';
        header[266] = (byte) 0xc3;
        header[267] = (byte) 0xbf;

        int checksum = 0;
        for (byte value : header) {
            checksum += value;
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes a null-terminated string field.
    private static void writeString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("value is too long");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Writes a fixed-width string field.
    private static void writeRawString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != length) {
            throw new IllegalArgumentException("value must match the field length");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Writes an octal number field.
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        int start = offset + length - text.length() - 1;
        for (int index = offset; index < start; index++) {
            header[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
    }

    /// Writes a positive base-256 TAR number field.
    private static void writeBase256Number(byte[] header, int offset, int length, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
        long remaining = value;
        for (int index = offset + length - 1; index > offset; index--) {
            header[index] = (byte) remaining;
            remaining >>>= 8;
        }
        if (remaining > 0x7f) {
            throw new IllegalArgumentException("value is too large");
        }
        header[offset] = (byte) (0x80 | remaining);
    }

    /// Writes a negative base-256 TAR number field.
    private static void writeNegativeBase256Number(byte[] header, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            header[index] = (byte) 0xff;
        }
    }

    /// Writes the TAR checksum field.
    private static void writeChecksum(byte[] header, int checksum) {
        String text = String.format("%06o", checksum);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, bytes.length);
        header[154] = 0;
        header[155] = (byte) ' ';
    }
}
