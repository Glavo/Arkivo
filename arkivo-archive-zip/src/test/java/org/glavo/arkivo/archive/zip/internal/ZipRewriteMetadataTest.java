// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks metadata retained when existing ZIP entry bodies are replaced or extended.
@NotNullByDefault
final class ZipRewriteMetadataTest {
    /// Modification time requiring an NTFS extra field for lossless serialization.
    private static final FileTime MODIFIED = FileTime.from(Instant.parse("2001-02-03T04:05:06.123456700Z"));

    /// Access time distinct from modification time.
    private static final FileTime ACCESSED = FileTime.from(Instant.parse("2002-03-04T05:06:07.234567800Z"));

    /// Creation time distinct from the other recorded times.
    private static final FileTime CREATED = FileTime.from(Instant.parse("2000-01-02T03:04:05.345678900Z"));

    /// The comment whose exact UTF-8 bytes must survive rewriting.
    private static final String COMMENT = "entry comment \u03b1";

    /// Preserves both physical extra-field lists and their decoded values across repeated body mutations.
    @ParameterizedTest
    @ValueSource(strings = {"channel", "stream", "append"})
    void preservesEntryMetadata(String operation, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("metadata.zip");
        byte @Unmodifiable [] local = concatenate(ntfsField(), extraField(0x7875, new byte[]{1, 1, 42, 1, 43}),
                extraField(0xcafe, new byte[]{1, 2, 3}));
        byte @Unmodifiable [] central = concatenate(ntfsField(), extraField(0xcafe, new byte[]{4, 5}));
        createArchive(archive, local, central);
        try (var source = ZipArkivoFileSystem.open(archive)) {
            assertMetadata(Files.readAttributes(source.getPath("/file"), ZipArkivoEntryAttributes.class), local, central);
        }
        byte[] expected = new byte[]{1, 2, 3};
        try (var target = ZipArkivoFileSystem.update(archive)) {
            assertMetadata(Files.readAttributes(target.getPath("/file"), ZipArkivoEntryAttributes.class), local, central);
            for (int pass = 0; pass < 2; pass++) {
                Path file = target.getPath("/file");
                if (operation.equals("stream")) {
                    try (var output = Files.newOutputStream(file)) {
                        output.write(new byte[]{8, 9});
                    }
                    expected = new byte[]{8, 9};
                } else {
                    var options = operation.equals("append")
                            ? Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                            : Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    try (var channel = Files.newByteChannel(file, options)) {
                        channel.write(ByteBuffer.wrap(new byte[]{8, 9}));
                    }
                    expected = operation.equals("append") ? concatenate(expected, new byte[]{8, 9}) : new byte[]{8, 9};
                }
                assertArrayEquals(expected, Files.readAllBytes(file));
                assertMetadata(Files.readAttributes(file, ZipArkivoEntryAttributes.class), local, central);
            }
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            assertMetadata(Files.readAttributes(target.getPath("/file"), ZipArkivoEntryAttributes.class), local, central);
            assertArrayEquals(expected, Files.readAllBytes(target.getPath("/file")));
        }
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archive).get()) {
            var entry = reference.getEntry("file");
            assertNotNull(entry);
            assertEquals(MODIFIED, entry.getLastModifiedTime());
            assertEquals(ACCESSED, entry.getLastAccessTime());
            assertEquals(CREATED, entry.getCreationTime());
            assertEquals(COMMENT, entry.getComment());
            assertEquals(1, entry.getInternalAttributes());
            assertEquals(0100640, entry.getUnixMode());
            try (var input = reference.getInputStream(entry)) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
        try (var reference = new java.util.zip.ZipFile(archive.toFile())) {
            var entry = reference.getEntry("file");
            assertNotNull(entry);
            // The JDK reader converts NTFS ticks to microseconds; Arkivo and Commons retain all 100-ns ticks above.
            assertEquals(MODIFIED.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                    entry.getLastModifiedTime().toInstant());
            assertEquals(COMMENT, entry.getComment());
            try (var input = reference.getInputStream(entry)) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    /// Rebuilds ZIP64 sizes and replaces legacy Unicode records when rewriting a Commons-generated archive.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rewritesZip64AndLegacyNames(boolean stream, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("legacy.zip");
        String name = "caf\u00e9.txt";
        String comment = "Gr\u00fc\u00dfe";
        try (var output = new ZipArchiveOutputStream(archive)) {
            output.setUseZip64(Zip64Mode.Always);
            output.setEncoding("IBM437");
            output.setUseLanguageEncodingFlag(false);
            output.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS);
            var entry = new ZipArchiveEntry(name);
            entry.setComment(comment);
            entry.setExtra(extraField(0xcafe, new byte[]{7, 8, 9}));
            output.putArchiveEntry(entry);
            output.write(new byte[]{1, 2, 3});
            output.closeArchiveEntry();
        }
        byte[] replacement = "replacement content".repeat(256).getBytes(StandardCharsets.UTF_8);
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path file = target.getPath("/" + name);
            var before = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertNotNull(ZipExtraFields.find(before.localExtraData(), 0x0001));
            assertNotNull(ZipExtraFields.find(before.centralDirectoryExtraData(), 0x7075));
            assertNotNull(ZipExtraFields.find(before.centralDirectoryExtraData(), 0x6375));
            assertEquals(comment, before.comment());
            replaceBody(file, replacement, stream);
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            Path file = target.getPath("/" + name);
            var actual = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertArrayEquals(replacement, Files.readAllBytes(file));
            assertEquals(comment, actual.comment());
            assertArrayEquals(name.getBytes(StandardCharsets.UTF_8), actual.rawPath());
            assertArrayEquals(comment.getBytes(StandardCharsets.UTF_8), actual.rawComment());
            for (byte[] extra : new byte[][]{actual.localExtraData(), actual.centralDirectoryExtraData()}) {
                assertNull(ZipExtraFields.find(extra, 0x0001));
                assertNull(ZipExtraFields.find(extra, 0x7075));
                assertNull(ZipExtraFields.find(extra, 0x6375));
                assertArrayEquals(extraField(0xcafe, new byte[]{7, 8, 9}), extra);
            }
        }
        try (var reference = new java.util.zip.ZipFile(archive.toFile())) {
            var entry = reference.getEntry(name);
            assertNotNull(entry);
            assertEquals(comment, entry.getComment());
            assertEquals(replacement.length, entry.getSize());
            try (var input = reference.getInputStream(entry)) {
                assertArrayEquals(replacement, input.readAllBytes());
            }
        }
    }

    /// Regenerates encryption metadata from the update policy instead of retaining an old AES record.
    @ParameterizedTest
    @EnumSource(ZipEncryption.class)
    void rewritesEncryptedMetadata(ZipEncryption encryption, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("encrypted.zip");
        var password = ArkivoPasswordProvider.fixed("test password".getBytes(StandardCharsets.UTF_8));
        var create = ZipArchiveOptions.CREATE_DEFAULTS.withPasswordProvider(password)
                .withDefaultEncryption(ZipEncryption.WINZIP_AES_256);
        try (var writer = ZipArkivoStreamingWriter.create(archive, create);
             var entry = writer.beginFile("file")) {
            var view = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            view.setLocalExtraData(ntfsField());
            view.setCentralDirectoryExtraData(ntfsField());
            view.setRawComment(COMMENT.getBytes(StandardCharsets.UTF_8));
            try (var output = entry.openOutputStream()) {
                output.write(new byte[]{1, 2, 3});
            }
        }
        byte[] replacement = new byte[]{8, 9};
        var update = ZipArchiveOptions.UPDATE_DEFAULTS.withPasswordProvider(password).withDefaultEncryption(encryption);
        try (var target = ZipArkivoFileSystem.update(archive, update)) {
            Path file = target.getPath("/file");
            // Exercise both first replacement and rewriting a completed replacement in the same session.
            replaceBody(file, replacement, false);
            replaceBody(file, replacement, true);
            assertArrayEquals(replacement, Files.readAllBytes(file));
        }
        try (var target = ZipArkivoFileSystem.open(archive,
                ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password))) {
            Path file = target.getPath("/file");
            var actual = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertEquals(encryption, actual.encryption());
            assertEquals(MODIFIED, actual.lastModifiedTime());
            assertEquals(ACCESSED, actual.lastAccessTime());
            assertEquals(CREATED, actual.creationTime());
            assertEquals(COMMENT, actual.comment());
            assertArrayEquals(replacement, Files.readAllBytes(file));
            for (byte[] extra : new byte[][]{actual.localExtraData(), actual.centralDirectoryExtraData()}) {
                byte[] retained = ZipExtraFields.remove(extra, ZipConstants.WINZIP_AES_EXTRA_FIELD_ID);
                assertArrayEquals(ntfsField(), retained);
                int aesSize = encryption == ZipEncryption.NONE || encryption == ZipEncryption.ZIP_CRYPTO ? 0 : 11;
                assertEquals(retained.length + aesSize, extra.length);
            }
        }
    }

    /// Rejected opens leave a completed replacement intact and permit another successful replacement.
    @Test
    void rejectsDuplicateCreationWithoutRemovingStagedEntry(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("duplicate.zip");
        createArchive(archive, ntfsField(), ntfsField());
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path file = target.getPath("/file");
            replaceBody(file, new byte[]{4, 5}, true);
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.newOutputStream(file, StandardOpenOption.CREATE_NEW));
            assertThrows(UnsupportedOperationException.class,
                    () -> Files.newOutputStream(file, StandardOpenOption.WRITE));
            assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(file));
            assertEquals(MODIFIED, Files.getLastModifiedTime(file));
            replaceBody(file, new byte[]{6, 7}, true);
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Replaces a complete entry body through either public output entry point.
    private static void replaceBody(Path file, byte[] body, boolean stream) throws IOException {
        if (stream) {
            try (var output = Files.newOutputStream(file)) {
                output.write(body);
            }
        } else {
            try (var channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                ByteBuffer input = ByteBuffer.wrap(body);
                while (input.hasRemaining()) {
                    channel.write(input);
                }
            }
        }
    }

    /// Keeps local-only metadata attached to an entry when its visible name changes before or after replacement.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMetadataAcrossRename(boolean renameFirst, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("renamed.zip");
        byte[] local = concatenate(ntfsField(), extraField(0x7875, new byte[]{1, 1, 42, 1, 43}));
        byte[] central = ntfsField();
        createArchive(archive, local, central);
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path original = target.getPath("/file");
            Path renamed = target.getPath("/renamed");
            var snapshot = Files.readAttributes(original, ZipArkivoEntryAttributes.class);
            if (renameFirst) {
                Files.move(original, renamed);
                replaceBody(renamed, new byte[]{4, 5}, true);
            } else {
                replaceBody(original, new byte[]{4, 5}, false);
                Files.move(original, renamed);
            }
            assertMetadata(Files.readAttributes(renamed, ZipArkivoEntryAttributes.class), local, central);
            assertMetadata(snapshot, local, central);
            assertEquals(3, snapshot.size());
            assertEquals(2, Files.size(renamed));
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            Path renamed = target.getPath("/renamed");
            assertMetadata(Files.readAttributes(renamed, ZipArkivoEntryAttributes.class), local, central);
            assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(renamed));
        }
    }

    /// Updates NTFS metadata in both headers while retaining access and creation times through subsequent mutations.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesExistingTimestampFields(boolean replaceFirst, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("times.zip");
        byte[] unknown = extraField(0xcafe, new byte[]{7, 8, 9});
        byte[] extra = concatenate(ntfsField(), unknown);
        createArchive(archive, extra, extra);
        FileTime changed = FileTime.from(Instant.parse("2020-06-07T08:09:10.765432100Z"));
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path file = target.getPath("/file");
            if (replaceFirst) {
                replaceBody(file, new byte[]{1, 2, 3}, false);
            }
            var before = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertThrows(IOException.class, () -> Files.setLastModifiedTime(file,
                    FileTime.from(Instant.parse("1601-01-01T00:00:00Z"))));
            assertEquals(MODIFIED, Files.getLastModifiedTime(file));
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2010-01-02T03:04:05Z")));
            Files.setLastModifiedTime(file, changed);
            var attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertEquals(changed, attributes.lastModifiedTime());
            assertEquals(ACCESSED, attributes.lastAccessTime());
            assertEquals(CREATED, attributes.creationTime());
            assertEquals(MODIFIED, before.lastModifiedTime());
            Files.move(file, target.getPath("/renamed"));
            Files.setPosixFilePermissions(target.getPath("/renamed"), PosixFilePermissions.fromString("rwx------"));
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            var actual = Files.readAttributes(target.getPath("/renamed"), ZipArkivoEntryAttributes.class);
            assertEquals(changed, actual.lastModifiedTime());
            assertEquals(ACCESSED, actual.lastAccessTime());
            assertEquals(CREATED, actual.creationTime());
            assertArrayEquals(unknown, ZipExtraFields.remove(actual.localExtraData(), 0x000a));
            assertArrayEquals(unknown, ZipExtraFields.remove(actual.centralDirectoryExtraData(), 0x000a));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/renamed")));
        }
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archive).get()) {
            var entry = reference.getEntry("renamed");
            assertEquals(changed, entry.getLastModifiedTime());
            assertEquals(ACCESSED, entry.getLastAccessTime());
            assertEquals(CREATED, entry.getCreationTime());
        }
        try (var reference = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals(changed.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                    reference.getEntry("renamed").getLastModifiedTime().toInstant());
        }
    }

    /// Synchronizes signed extended and unsigned legacy Unix times in original and staged entries.
    @ParameterizedTest
    @CsvSource({"21589,false", "21589,true", "13,false", "13,true", "22613,false", "22613,true"})
    void updatesUnixTimestampFields(int id, boolean replaceFirst, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("unix-times.zip");
        byte[] payload = new byte[id == 0x5455 ? 13 : 12];
        if (id == 0x5455) {
            payload[0] = 7;
            ByteArrayAccess.writeIntLittleEndian(payload, 1, 100);
            ByteArrayAccess.writeIntLittleEndian(payload, 5, 200);
            ByteArrayAccess.writeIntLittleEndian(payload, 9, 300);
        } else {
            ByteArrayAccess.writeIntLittleEndian(payload, 0, 200);
            ByteArrayAccess.writeIntLittleEndian(payload, 4, 100);
            ByteArrayAccess.writeIntLittleEndian(payload, 8, 0x12345678);
        }
        byte[] extra = extraField(id, payload);
        createArchive(archive, extra, extra);
        FileTime changed = FileTime.from(Instant.parse("2021-02-03T04:05:06Z"));
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path file = target.getPath("/file");
            if (replaceFirst) {
                replaceBody(file, new byte[]{1, 2, 3}, true);
            }
            Files.setLastModifiedTime(file, changed);
            var actual = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertEquals(changed, actual.lastModifiedTime());
            assertEquals(FileTime.from(Instant.ofEpochSecond(200)), actual.lastAccessTime());
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            var actual = Files.readAttributes(target.getPath("/file"), ZipArkivoEntryAttributes.class);
            assertEquals(changed, actual.lastModifiedTime());
            assertEquals(FileTime.from(Instant.ofEpochSecond(200)), actual.lastAccessTime());
            ByteArrayAccess.writeIntLittleEndian(payload, id == 0x5455 ? 1 : 4, (int) changed.toInstant().getEpochSecond());
            assertArrayEquals(extraField(id, payload), actual.localExtraData());
            assertArrayEquals(extraField(id, payload), actual.centralDirectoryExtraData());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Rejects an unrepresentable local or central time before mutation and permits a later valid update.
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void rejectsUnrepresentableTimestampWithoutChangingEntry(boolean localUnix, boolean replaceFirst,
                                                            @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("timestamp-range.zip");
        byte[] unixPayload = new byte[5];
        unixPayload[0] = 1;
        ByteArrayAccess.writeIntLittleEndian(unixPayload, 1, 100);
        byte[] unix = extraField(0x5455, unixPayload);
        byte[] local = localUnix ? unix : ntfsField();
        byte[] central = localUnix ? ntfsField() : unix;
        createArchive(archive, local, central);
        FileTime changed = FileTime.from(Instant.parse("2022-03-04T05:06:07Z"));
        try (var target = ZipArkivoFileSystem.update(archive)) {
            Path file = target.getPath("/file");
            if (replaceFirst) {
                replaceBody(file, new byte[]{1, 2, 3}, true);
            }
            assertThrows(IOException.class, () -> Files.setLastModifiedTime(file,
                    FileTime.from(Instant.parse("2040-01-01T00:00:00Z"))));
            var actual = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertEquals(MODIFIED, actual.lastModifiedTime());
            assertArrayEquals(local, actual.localExtraData());
            assertArrayEquals(central, actual.centralDirectoryExtraData());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
            Files.setLastModifiedTime(file, changed);
            // A subsequent body replacement must retain the successful override, not reload the source time.
            replaceBody(file, new byte[]{4, 5}, false);
        }
        try (var target = ZipArkivoFileSystem.open(archive)) {
            assertEquals(changed, Files.getLastModifiedTime(target.getPath("/file")));
            assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(target.getPath("/file")));
        }
    }

    /// Asserts metadata independently of the compressed sizes and CRC that must change with the body.
    private static void assertMetadata(ZipArkivoEntryAttributes actual, byte[] local, byte[] central) {
        assertEquals(MODIFIED, actual.lastModifiedTime());
        assertEquals(ACCESSED, actual.lastAccessTime());
        assertEquals(CREATED, actual.creationTime());
        assertEquals(42, actual.userId());
        assertEquals(43, actual.groupId());
        assertEquals(1, actual.internalAttributes());
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), actual.permissions());
        assertArrayEquals(COMMENT.getBytes(StandardCharsets.UTF_8), actual.rawComment());
        assertArrayEquals(local, actual.localExtraData());
        assertArrayEquals(central, actual.centralDirectoryExtraData());
    }

    /// Creates an entry with independently specified local and central metadata.
    private static void createArchive(Path archive, byte[] local, byte[] central) throws IOException {
        try (var writer = ZipArkivoStreamingWriter.create(archive);
             var entry = writer.beginFile("file")) {
            var view = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            view.setTimes(MODIFIED, null, null);
            view.setPermissions(PosixFilePermissions.fromString("rw-r-----"));
            view.setInternalAttributes(1);
            view.setRawComment(COMMENT.getBytes(StandardCharsets.UTF_8));
            view.setLocalExtraData(local);
            view.setCentralDirectoryExtraData(central);
            try (var output = entry.openOutputStream()) {
                output.write(new byte[]{1, 2, 3});
            }
        }
    }

    /// Encodes NTFS timestamp metadata at its native 100-nanosecond resolution.
    private static byte @Unmodifiable [] ntfsField() {
        byte[] payload = new byte[32];
        ByteArrayAccess.writeShortLittleEndian(payload, 4, (short) 1);
        ByteArrayAccess.writeShortLittleEndian(payload, 6, (short) 24);
        FileTime[] times = {MODIFIED, ACCESSED, CREATED};
        for (int index = 0; index < times.length; index++) {
            Instant instant = times[index].toInstant();
            long ticks = 116_444_736_000_000_000L + instant.getEpochSecond() * 10_000_000L + instant.getNano() / 100;
            ByteArrayAccess.writeLongLittleEndian(payload, 8 + index * Long.BYTES, ticks);
        }
        return extraField(0x000a, payload);
    }

    /// Encodes one complete extra-field record.
    private static byte @Unmodifiable [] extraField(int id, byte[] payload) {
        byte[] record = new byte[4 + payload.length];
        ByteArrayAccess.writeShortLittleEndian(record, 0, (short) id);
        ByteArrayAccess.writeShortLittleEndian(record, 2, (short) payload.length);
        System.arraycopy(payload, 0, record, 4, payload.length);
        return record;
    }

    /// Concatenates metadata or body fragments without modifying them.
    private static byte @Unmodifiable [] concatenate(byte[]... fragments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] fragment : fragments) {
            output.writeBytes(fragment);
        }
        return output.toByteArray();
    }
}
