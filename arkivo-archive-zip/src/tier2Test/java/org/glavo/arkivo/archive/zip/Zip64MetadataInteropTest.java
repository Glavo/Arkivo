// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks ZIP64 location and timestamp field ordering before and after local records are relocated.
///
/// Independently exercises the format interaction reported in JDK-8255380 and JDK-8257445.
@NotNullByDefault
final class Zip64MetadataInteropTest {
    /// Whole-second times representable in both DOS and extended timestamp records.
    private static final FileTime MODIFIED = FileTime.from(Instant.parse("2023-07-08T09:10:12Z"));

    /// Access time present only in the local extended timestamp record.
    private static final FileTime ACCESSED = FileTime.from(Instant.parse("2023-07-09T10:11:12Z"));

    /// Creation time present only in the local extended timestamp record.
    private static final FileTime CREATED = FileTime.from(Instant.parse("2023-07-06T07:08:10Z"));

    /// Payload independent of the compressed representation used by either implementation.
    private static final byte @Unmodifiable [] CONTENT = content();

    /// Combines independently saturated size/offset fields, extra-field permutations, and compression methods.
    private static Stream<Arguments> layouts() {
        return Stream.of(ZipEntry.STORED, ZipEntry.DEFLATED).flatMap(method ->
                IntStream.range(1, 8).boxed().flatMap(mask ->
                        Stream.of("ztu", "zut", "tzu", "tuz", "uzt", "utz")
                                .map(order -> Arguments.of(method, mask, order))));
    }

    /// Rejects missing or incomplete required ZIP64 values regardless of neighboring metadata fields.
    @ParameterizedTest(name = "method={0}, zip64Mask={1}, order={2}")
    @MethodSource("layouts")
    void rejectsMissingAndShortZip64Fields(int method, int mask, String order) throws IOException {
        byte[] valid = archive(method, mask, order);
        int central = middleCentralHeader(valid);
        int field = central + ZipFile.CENHDR + unsignedShort(valid, central + ZipFile.CENNAM);
        int end = field + unsignedShort(valid, central + ZipFile.CENEXT);
        while (field < end && unsignedShort(valid, field) != 1) {
            field += 4 + unsignedShort(valid, field + 2);
        }
        assertTrue(field < end, "missing generated ZIP64 field");
        for (boolean removeTag : new boolean[]{false, true}) {
            byte[] corrupt = valid.clone();
            if (removeTag) {
                ByteArrayAccess.writeShortLittleEndian(corrupt, field, (short) 0xcafb);
            } else {
                ByteArrayAccess.writeShortLittleEndian(corrupt, field + 2,
                        (short) (unsignedShort(valid, field + 2) - 1));
            }
            assertThrows(IOException.class, () -> {
                try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(corrupt))) {
                    Files.readAttributes(fileSystem.getPath("/payload.bin"), ZipArkivoEntryAttributes.class);
                }
            }, removeTag ? "missing ZIP64 field" : "short ZIP64 payload");
        }
    }

    /// Preserves local-only timestamps while renaming and relocating a record whose offset may be ZIP64 encoded.
    @ParameterizedTest(name = "method={0}, zip64Mask={1}, order={2}")
    @MethodSource("layouts")
    void readsAndRelocatesEveryLayout(int method, int mask, String order, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("layout.zip");
        Files.write(archive, archive(method, mask, order));
        verify(archive, "payload.bin", method, 3);
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path original = fileSystem.getPath("/payload.bin");
            ZipArkivoEntryAttributes snapshot = Files.readAttributes(original, ZipArkivoEntryAttributes.class);
            Files.delete(fileSystem.getPath("/prefix.bin"));
            Files.move(original, fileSystem.getPath("/renamed-payload.bin"));
            assertFalse(Files.exists(original));
            assertTimes(snapshot);
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/renamed-payload.bin")));
        }
        verify(archive, "renamed-payload.bin", method, 2);
        try (var reference = new ZipFile(archive.toFile())) {
            assertNull(reference.getEntry("prefix.bin"));
            @Nullable ZipEntry suffix = reference.getEntry("suffix.bin");
            assertNotNull(suffix);
            try (var input = reference.getInputStream(suffix)) {
                assertArrayEquals(new byte[]{17, 31, 47}, input.readAllBytes());
            }
        }
    }

    /// Checks the same bytes through Arkivo, JDK ZipFile, and the JDK NIO provider.
    private static void verify(Path archive, String name, int method, int entryCount) throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path path = fileSystem.getPath("/" + name);
            try (var entries = Files.list(fileSystem.getPath("/"))) {
                assertEquals(entryCount, entries.count());
            }
            ZipArkivoEntryAttributes attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            assertTimes(attributes);
            assertOpaqueField(attributes.centralDirectoryExtraData());
            assertEquals(CONTENT.length, attributes.size());
            assertEquals(method == ZipEntry.STORED ? ZipMethod.STORED : ZipMethod.DEFLATED,
                    attributes.compressionMethod());
            assertArrayEquals(CONTENT, Files.readAllBytes(path));
        }
        try (var reference = new ZipFile(archive.toFile())) {
            assertEquals(entryCount, reference.size());
            @Nullable ZipEntry entry = reference.getEntry(name);
            assertNotNull(entry);
            assertEquals(method, entry.getMethod());
            assertEquals(MODIFIED, entry.getLastModifiedTime());
            assertEquals(CONTENT.length, entry.getSize());
            CRC32 checksum = new CRC32();
            checksum.update(CONTENT);
            assertEquals(checksum.getValue(), entry.getCrc());
            try (var input = reference.getInputStream(entry)) {
                assertArrayEquals(CONTENT, input.readAllBytes());
            }
        }
        try (var reference = FileSystems.newFileSystem(archive, Map.of())) {
            Path path = reference.getPath("/" + name);
            assertTimes(Files.readAttributes(path, BasicFileAttributes.class));
            assertArrayEquals(CONTENT, Files.readAllBytes(path));
        }
    }

    /// Checks complete metadata, including times that require reading the local record.
    private static void assertTimes(BasicFileAttributes attributes) {
        assertEquals(MODIFIED, attributes.lastModifiedTime());
        assertEquals(ACCESSED, attributes.lastAccessTime());
        assertEquals(CREATED, attributes.creationTime());
    }

    /// Checks that an uninterpreted central extra field survives record relocation unchanged.
    private static void assertOpaqueField(byte @Unmodifiable [] extra) {
        for (int offset = 0; offset < extra.length;) {
            int length = unsignedShort(extra, offset + 2);
            if (unsignedShort(extra, offset) == 0xcafe) {
                assertArrayEquals(new byte[]{3, 1, 4, 1, 5},
                        Arrays.copyOfRange(extra, offset + 4, offset + 4 + length));
                return;
            }
            offset += 4 + length;
        }
        throw new AssertionError("missing opaque central extra field");
    }

    /// Replaces only the middle entry's central extra fields in a ZIP produced by the JDK.
    private static byte[] archive(int method, int mask, String order) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (String name : List.of("prefix.bin", "payload.bin", "suffix.bin")) {
                byte @Unmodifiable [] body = name.equals("payload.bin") ? CONTENT : new byte[]{17, 31, 47};
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(method);
                entry.setSize(body.length);
                CRC32 checksum = new CRC32();
                checksum.update(body);
                entry.setCrc(checksum.getValue());
                entry.setLastModifiedTime(MODIFIED);
                entry.setLastAccessTime(ACCESSED);
                entry.setCreationTime(CREATED);
                zip.putNextEntry(entry);
                zip.write(body);
                zip.closeEntry();
            }
        }
        byte[] original = output.toByteArray();
        int end = original.length - ZipFile.ENDHDR;
        int central = middleCentralHeader(original);
        assertEquals(ZipFile.CENSIG, Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(original, central)));
        int local = ByteArrayAccess.readIntLittleEndian(original, central + ZipFile.CENOFF);
        assertTrue(local > 0, "the ZIP64 offset must not point to the first local header");
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        for (int index = 0; index < 3; index++) {
            if ((mask & (1 << index)) != 0) {
                int field = switch (index) {
                    case 0 -> ZipFile.CENLEN;
                    case 1 -> ZipFile.CENSIZ;
                    case 2 -> ZipFile.CENOFF;
                    default -> throw new AssertionError();
                };
                byte[] value = new byte[Long.BYTES];
                ByteArrayAccess.writeLongLittleEndian(value, 0,
                        Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(original, central + field)));
                values.writeBytes(value);
                ByteArrayAccess.writeIntLittleEndian(original, central + field, -1);
            }
        }
        ByteArrayAccess.writeShortLittleEndian(original, central + ZipFile.CENVER, (short) 45);
        byte[] timestamp = new byte[5];
        timestamp[0] = 1;
        ByteArrayAccess.writeIntLittleEndian(timestamp, 1, (int) MODIFIED.toInstant().getEpochSecond());
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        for (char field : order.toCharArray()) {
            switch (field) {
                case 'z' -> appendField(extra, 1, values.toByteArray());
                case 't' -> appendField(extra, 0x5455, timestamp);
                case 'u' -> appendField(extra, 0xcafe, new byte[]{3, 1, 4, 1, 5});
                default -> throw new AssertionError(field);
            }
        }
        int oldSize = unsignedShort(original, central + ZipFile.CENEXT);
        int start = central + ZipFile.CENHDR + unsignedShort(original, central + ZipFile.CENNAM);
        int delta = extra.size() - oldSize;
        output.reset();
        output.write(original, 0, start);
        output.writeBytes(extra.toByteArray());
        output.write(original, start + oldSize, original.length - start - oldSize);
        byte[] result = output.toByteArray();
        ByteArrayAccess.writeShortLittleEndian(result, central + ZipFile.CENEXT, (short) extra.size());
        ByteArrayAccess.writeIntLittleEndian(result, end + delta + ZipFile.ENDSIZ,
                ByteArrayAccess.readIntLittleEndian(original, end + ZipFile.ENDSIZ) + delta);
        return result;
    }

    /// Appends a complete extra field without relying on either ZIP implementation's metadata writer.
    private static void appendField(ByteArrayOutputStream output, int tag, byte[] payload) {
        byte[] header = new byte[4];
        ByteArrayAccess.writeShortLittleEndian(header, 0, (short) tag);
        ByteArrayAccess.writeShortLittleEndian(header, 2, (short) payload.length);
        output.writeBytes(header);
        output.writeBytes(payload);
    }

    /// Reads a ZIP header length without sign extension.
    private static int unsignedShort(byte[] bytes, int offset) {
        return Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, offset));
    }

    /// Locates the second central record by stepping over the first record's variable-length fields.
    private static int middleCentralHeader(byte[] archive) {
        int directory = ByteArrayAccess.readIntLittleEndian(archive, archive.length - ZipFile.ENDHDR + ZipFile.ENDOFF);
        return directory + ZipFile.CENHDR + unsignedShort(archive, directory + ZipFile.CENNAM)
                + unsignedShort(archive, directory + ZipFile.CENEXT) + unsignedShort(archive, directory + ZipFile.CENCOM);
    }

    /// Creates a deterministic binary payload containing every byte value.
    private static byte @Unmodifiable [] content() {
        byte[] bytes = new byte[513];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 73);
        return bytes;
    }
}
