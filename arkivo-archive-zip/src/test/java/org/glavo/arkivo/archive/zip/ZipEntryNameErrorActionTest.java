// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Tests entry-name recovery through indexed and forward-only ZIP reading.
@NotNullByDefault
final class ZipEntryNameErrorActionTest {
    /// Supplies both known-size and descriptor-delimited bodies with each authoritative Unicode source.
    private static Stream<Arguments> recoveryCases() {
        return Stream.of(ZipEntry.STORED, ZipEntry.DEFLATED).flatMap(method ->
                Stream.of(false, true).flatMap(unicode -> Stream.of(ZipEntryNameErrorAction.values())
                        .map(action -> Arguments.of(method, unicode, action))));
    }

    /// Reports, hides, or replaces a bad first name without losing the following entry or its contents.
    @ParameterizedTest
    @MethodSource("recoveryCases")
    void appliesRecoveryAcrossReaders(int method, boolean unicode, ZipEntryNameErrorAction action,
                                     @TempDir Path directory) throws IOException {
        byte[] bytes = archive(method, unicode, false);
        Path archive = directory.resolve("names.zip");
        Files.write(archive, bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(action);
        if (action == ZipEntryNameErrorAction.REPORT) {
            try (var fs = ZipArkivoFileSystem.open(archive, options)) {
                assertThrows(IOException.class, () -> list(fs));
            }
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
                assertThrows(IOException.class, reader::next);
            }
            return;
        }
        List<String> expected = action == ZipEntryNameErrorAction.SKIP ? List.of("good")
                : List.of(unicode ? "\ufffd" : "\ufffdad", "good");
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertEquals(expected.stream().sorted().toList(), list(fs));
            for (String name : expected) assertEquals("payload", Files.readString(fs.getPath(name)));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            var names = new ArrayList<String>();
            while (reader.next()) {
                names.add(reader.readAttributes().path());
                try (var input = reader.openInputStream()) {
                    assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(expected, names);
        }
    }

    /// Replacement collisions remain invalid indexed paths rather than silently overwriting an entry.
    @Test
    void rejectsReplacementCollisions(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("collision.zip");
        Files.write(archive, archive(ZipEntry.STORED, false, true));
        try (var fs = ZipArkivoFileSystem.open(archive,
                ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(ZipEntryNameErrorAction.REPLACE))) {
            IOException failure = assertThrows(IOException.class, () -> list(fs));
            assertTrue(failure.getMessage().contains("Duplicate ZIP entry path"));
        }
    }

    /// Omitted names still consume the logical entry budget in both reading modes.
    @Test
    void countsSkippedEntries(@TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, false, false);
        Path archive = directory.resolve("limits.zip");
        Files.write(archive, bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(ZipEntryNameErrorAction.SKIP);
        options = options.withCommon(options.common().withLimits(
                ArchiveReadLimits.builder().maximumEntryCount(1).build()));
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertThrows(ArkivoReadLimitException.class, () -> list(fs));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertThrows(ArkivoReadLimitException.class, reader::next);
        }
    }

    /// Draining a skipped descriptor-delimited body still enforces the decoded-output budget.
    @Test
    void limitsSkippedUnknownSizeBodies() throws IOException {
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(ZipEntryNameErrorAction.SKIP);
        options = options.withCommon(options.common().withLimits(
                ArchiveReadLimits.builder().maximumEntrySize(2).build()));
        try (var reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive(ZipEntry.DEFLATED, false, false)), options)) {
            assertThrows(ArkivoReadLimitException.class, reader::next);
        }
    }

    /// Decoder callbacks are not retried or treated as character failures when they throw another I/O error.
    @ParameterizedTest
    @EnumSource(ZipEntryNameErrorAction.class)
    void preservesCustomDecoderFailures(ZipEntryNameErrorAction action, @TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, false, false);
        clearUtf8Flags(bytes);
        Path archive = directory.resolve("custom.zip");
        Files.write(archive, bytes);
        IOException failure = new IOException("Custom decoder failure");
        ArchiveMetadataDecoder decoder = input -> { throw failure; };
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(action).withLegacyMetadataDecoder(decoder);
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertSame(failure, assertThrows(IOException.class, () -> list(fs)));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertSame(failure, assertThrows(IOException.class, reader::next));
        }
    }

    /// A custom decoder's character failures can be skipped without invoking it twice for a field.
    @Test
    void skipsCustomCharacterFailures() throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, false, false);
        clearUtf8Flags(bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(ZipEntryNameErrorAction.SKIP)
                .withLegacyMetadataDecoder(input -> {
                    if (input.get(input.position()) < 0) throw new CharacterCodingException();
                    return StandardCharsets.US_ASCII.decode(input).toString();
                });
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertTrue(reader.next());
            assertEquals("good", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Malformed Unicode extra-field framing is not a recoverable character error.
    @ParameterizedTest
    @EnumSource(ZipEntryNameErrorAction.class)
    void rejectsStructuralDamage(ZipEntryNameErrorAction action, @TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, true, false);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int central = buffer.getInt(bytes.length - 6);
        int local = buffer.getInt(central + 42);
        buffer.putShort(local + 30 + 3 + 2, (short) 100);
        buffer.putShort(central + 46 + 3 + 2, (short) 100);
        Path archive = directory.resolve("structure.zip");
        Files.write(archive, bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(action);
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertThrows(IOException.class, () -> list(fs));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Read recovery is accepted through environment options but cannot enter a write or update session.
    @ParameterizedTest
    @EnumSource(value = ZipEntryNameErrorAction.class, names = {"SKIP", "REPLACE"})
    void restrictsEnvironmentRecoveryToReading(ZipEntryNameErrorAction action) {
        var options = ArchiveOptions.fromEnvironment(Map.of("arkivo.zip.entryNameErrorAction", action));
        assertEquals(action, ZipArkivoFileSystemConfig.fromOptions(options).entryNameErrorAction());
        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromUpdateOptions(options));
        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromWriterOptions(options));
        var readOptions = ArchiveOptions.fromEnvironment(Map.of("arkivo.zip.entryNameErrorAction", action,
                "arkivo.openOptions", Set.of(StandardOpenOption.READ)));
        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromUpdateOptions(readOptions));
        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromWriterOptions(readOptions));
    }

    /// Consecutive omitted entries leave an empty logical archive without changing physical record counts.
    @Test
    void skipsEveryEntry(@TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.DEFLATED, false, true);
        Path archive = directory.resolve("empty-view.zip");
        Files.write(archive, bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(ZipEntryNameErrorAction.SKIP);
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertEquals(List.of(), list(fs));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertFalse(reader.next());
            assertFalse(reader.next());
        }
    }

    /// Replacement does not retry opaque callbacks or substitute a different legacy charset.
    @ParameterizedTest
    @EnumSource(value = ZipEntryNameErrorAction.class, names = {"REPORT", "REPLACE"})
    void doesNotRetryCustomCharacterFailures(ZipEntryNameErrorAction action, @TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, false, false);
        clearUtf8Flags(bytes);
        Path archive = directory.resolve("custom-characters.zip");
        Files.write(archive, bytes);
        var calls = new AtomicInteger();
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(action)
                .withLegacyMetadataDecoder(input -> {
                    calls.incrementAndGet();
                    throw new CharacterCodingException();
                });
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertInstanceOf(CharacterCodingException.class, assertThrows(IOException.class, () -> list(fs)).getCause());
            assertEquals(1, calls.get());
        }
        calls.set(0);
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertInstanceOf(CharacterCodingException.class, assertThrows(IOException.class, reader::next).getCause());
            assertEquals(1, calls.get());
        }
    }

    /// Decodable traversal paths are rejected instead of being hidden or repaired by name recovery.
    @ParameterizedTest
    @EnumSource(ZipEntryNameErrorAction.class)
    void rejectsUnsafeDecodedPaths(ZipEntryNameErrorAction action, @TempDir Path directory) throws IOException {
        byte[] bytes = archive(ZipEntry.STORED, false, false);
        clearUtf8Flags(bytes);
        Path archive = directory.resolve("unsafe.zip");
        Files.write(archive, bytes);
        var options = ZipArchiveOptions.READ_DEFAULTS.withEntryNameErrorAction(action)
                .withLegacyMetadataDecoder(input -> "../escape");
        try (var fs = ZipArkivoFileSystem.open(archive, options)) {
            assertThrows(IOException.class, () -> list(fs));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertThrows(IOException.class, reader::next);
        }
    }

    /// Returns every indexed root name, forcing lazy index construction.
    private static List<String> list(ZipArkivoFileSystem fs) throws IOException {
        try (var paths = Files.list(fs.getPath("/"))) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /// Creates an in-memory archive, then corrupts only authoritative name bytes in both headers.
    private static byte[] archive(int method, boolean unicode, boolean collision) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32();
        crc.update(body);
        try (var zip = new ZipOutputStream(output)) {
            for (String name : List.of("bad", collision ? "cad" : "good")) {
                var entry = new ZipEntry(name);
                entry.setMethod(method);
                if (method == ZipEntry.STORED) {
                    entry.setSize(body.length);
                    entry.setCrc(crc.getValue());
                }
                if (unicode && name.equals("bad")) {
                    byte[] extra = new byte[10];
                    ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x7075).putShort((short) 6);
                    extra[4] = 1;
                    var nameCrc = new CRC32();
                    nameCrc.update(name.getBytes(StandardCharsets.US_ASCII));
                    ByteArrayAccess.writeIntLittleEndian(extra, 5, (int) nameCrc.getValue());
                    extra[9] = (byte) 0xff;
                    entry.setExtra(extra);
                }
                zip.putNextEntry(entry);
                zip.write(body);
                zip.closeEntry();
            }
        }
        byte[] bytes = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int central = buffer.getInt(bytes.length - 6);
        for (int i = 0; i < (collision ? 2 : 1); i++) {
            int local = buffer.getInt(central + 42);
            if (unicode) {
                buffer.putShort(local + 6, (short) (buffer.getShort(local + 6) & ~0x800));
                buffer.putShort(central + 8, (short) (buffer.getShort(central + 8) & ~0x800));
            } else {
                bytes[local + 30] = bytes[central + 46] = (byte) (0xff - i);
            }
            central += 46 + Short.toUnsignedInt(buffer.getShort(central + 28))
                    + Short.toUnsignedInt(buffer.getShort(central + 30))
                    + Short.toUnsignedInt(buffer.getShort(central + 32));
        }
        return bytes;
    }

    /// Removes both headers' UTF-8 flags so a custom legacy decoder receives the names.
    private static void clearUtf8Flags(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int central = buffer.getInt(bytes.length - 6);
        while (buffer.getInt(central) == 0x02014b50) {
            int local = buffer.getInt(central + 42);
            buffer.putShort(local + 6, (short) (buffer.getShort(local + 6) & ~0x800));
            buffer.putShort(central + 8, (short) (buffer.getShort(central + 8) & ~0x800));
            central += 46 + Short.toUnsignedInt(buffer.getShort(central + 28))
                    + Short.toUnsignedInt(buffer.getShort(central + 30))
                    + Short.toUnsignedInt(buffer.getShort(central + 32));
        }
    }
}
