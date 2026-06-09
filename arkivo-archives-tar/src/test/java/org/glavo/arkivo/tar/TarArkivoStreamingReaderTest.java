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
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
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

    /// Writes the TAR checksum field.
    private static void writeChecksum(byte[] header, int checksum) {
        String text = String.format("%06o", checksum);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, bytes.length);
        header[154] = 0;
        header[155] = (byte) ' ';
    }
}
