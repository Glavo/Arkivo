// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises libzip regression archives with independent content digests and upstream operation expectations.
@NotNullByDefault
final class LibzipCorpusTest {
    /// Returns ordinary archives whose complete contents can be read independently by Commons Compress.
    private static Stream<String> readableArchives() {
        return Stream.of("test.zip", "test2.zip", "testdeflated.zip", "testdeflated2.zip", "teststored.zip",
                "testbzip2.zip", "testempty.zip", "testdir.zip", "testcomment.zip", "testcomment13.zip",
                "testcommentremoved.zip", "testbuffer.zip", "testbuffer_reopen.zip", "teststdin.zip",
                "testfile.zip", "testfile0.zip", "testfile2014.zip", "testfile-stored-dos.zip",
                "testfile-ef.zip", "testfile-plus-extra.zip", "testfile-long-comment.zip",
                "testfile-cp437.zip", "testfile-UTF8.zip", "test-cp437.zip", "test-cp437-fc.zip",
                "test-cp437-comment-utf-8.zip", "test-cp437-fc-utf-8-filename.zip", "test-utf8.zip",
                "zip64.zip", "zip64-3mf.zip", "streamed.zip", "streamed-zip64.zip", "fileorder.zip",
                "firstsecond.zip", "test_open_multiple.zip", "aaaaaaaa-stored.zip", "foo-stored.zip",
                "mtime-default.zip", "mtime-dstpoint.zip", "mtime-pre-dstpoint.zip", "mtime-post-dstpoint.zip",
                "mtime-dstpoint-deflated.zip", "mtime-pre-dstpoint-deflated.zip", "mtime-post-dstpoint-deflated.zip",
                "rename_ok.zip", "extra_field_align_1-0.zip", "extra_field_align_2-0.zip",
                "extra_field_align_3-0.zip", "extra_field_align_4-ff.zip", "extra_field_align_1-ef_00.zip",
                "extra_field_align_2-ef_00.zip", "extra_field_align_3-ef_00.zip");
    }

    /// Returns inconsistent records rejected by libzip's strict-open regression suite.
    private static Stream<String> inconsistentArchives() {
        return Stream.of("incons-cdoffset.zip", "incons-cdsize-large.zip", "incons-cdsize-small.zip",
                "incons-central-compression-method.zip", "incons-central-compsize-larger-toolarge.zip",
                "incons-central-compsize-larger.zip", "incons-central-compsize-smaller.zip",
                "incons-central-crc.zip", "incons-central-file-comment-longer.zip",
                "incons-central-file-comment-shorter.zip", "incons-central-magic-bad.zip",
                "incons-central-magic-bad2.zip", "incons-central-size-larger.zip",
                "incons-ef-local-id-size.zip", "incons-ef-local-size.zip",
                "incons-eocd-magic-bad.zip", "incons-file-count-high.zip", "incons-file-count-low.zip",
                "incons-file-count-overflow.zip", "incons-local-compression-method.zip",
                "incons-local-compsize-larger.zip", "incons-local-compsize-smaller.zip", "incons-local-crc.zip",
                "incons-local-filename-long.zip", "incons-local-filename-missing.zip",
                "incons-local-filename-short.zip", "incons-local-filename.zip", "incons-local-magic-bad.zip",
                "incons-local-size-larger.zip", "incons-stored-size.zip", "incons-streamed.zip",
                "incons-streamed-2.zip", "extra_field_align_1-ff.zip", "extra_field_align_2-ff.zip",
                "extra_field_align_3-ff.zip", "extra_field_align_1-ef_ff.zip", "extra_field_align_2-ef_ff.zip",
                "extra_field_align_3-ef_ff.zip");
    }

    /// Compares every body, path, and entry kind with an independent decoder through both public reading APIs.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsCompleteArchiveContents(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem),
                    normalizeZipPaths(expected));
        }
        try (var reader = ZipArkivoStreamingReader.open(fixture(name))) {
            assertEquals(normalizeZipPaths(expected), ArchiveCorpusAssertions.readStreaming(reader));
        }
    }

    /// Applies Arkivo's ZIP separator policy without changing names from other archive formats.
    private static @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> normalizeZipPaths(
            @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> entries
    ) {
        return entries.stream().map(entry -> new ArchiveCorpusAssertions.EntryDigest(entry.path().replace('\\', '/'),
                entry.directory(), entry.symbolicLink(), entry.size(), entry.crc32(), entry.sha256())).toList();
    }

    /// Rejects the invalid CRC in libzip's cloning fixtures while retaining independently readable entries.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gap.zip", "gap-add.zip", "gap-delete.zip", "gap-replace.zip"})
    void validatesCloningFixtureContents(String name, @TempDir Path directory) throws IOException {
        var expected = ArchiveCorpusAssertions.readZipReference(fixture(name));
        assertEquals("gap", expected.get(0).path());
        assertEquals(17, expected.get(0).size());
        assertEquals(0x935603c1L, expected.get(0).crc32());
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            var path = fileSystem.getPath("/gap");
            assertEquals(0x96d04037L, Files.readAttributes(path, ZipArkivoEntryAttributes.class).crc32());
            assertThrows(IOException.class, () -> Files.readAllBytes(path));
            assertArrayEquals("This is the second file.".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/second")));
        }
        Path archive = directory.resolve(name);
        Files.copy(fixture(name), archive);
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            Files.delete(fileSystem.getPath("/gap"));
        }
        var remaining = expected.stream().filter(entry -> !entry.path().equals("gap")).toList();
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), remaining);
        }
        assertEquals(remaining, ArchiveCorpusAssertions.readZipReference(archive));
    }

    /// Retains an unknown truncated extra field as opaque metadata without weakening recognized-field checks.
    @Test
    void preservesUnknownTrailingExtraField() throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("incons-ef-central-size-wrong.zip"))) {
            Path path = fileSystem.getPath("/testfile.txt");
            var attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            assertEquals("ff3a773731323334353637383930313233343536373839",
                    java.util.HexFormat.of().formatHex(attributes.centralDirectoryExtraData()));
            assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(path));
        }
    }

    /// Replays the independently failing entries from `fread.c` without poisoning other archive readers.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"storedcrcerror", "deflatecrcerror", "deflatezliberror"})
    void rejectsDamagedEntryWithoutClosingOtherReaders(String name) throws IOException, NoSuchAlgorithmException {
        try (var reference = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setPath(fixture("broken.zip")).get();
             var fileSystem = ZipArkivoFileSystem.open(fixture("broken.zip"), passwordOptions("crypt"));
             var healthy = Files.newInputStream(fileSystem.getPath("/deflateok"))) {
            int firstByte = healthy.read();
            assertTrue(firstByte >= 0);
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/" + name)));
            byte[] deflated;
            try (var input = reference.getInputStream(reference.getEntry("deflateok"))) {
                deflated = input.readAllBytes();
            }
            assertEquals(43133, deflated.length);
            assertEquals("7bf44f2806f9ded4df9047e2aa9a94a902fc7c6bb17793c9db4408439e9ea03d",
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deflated)));
            assertEquals(Byte.toUnsignedInt(deflated[0]), firstByte);
            assertArrayEquals(Arrays.copyOfRange(deflated, 1, deflated.length), healthy.readAllBytes());
            assertArrayEquals(deflated, Files.readAllBytes(fileSystem.getPath("/deflateok")));
            assertArrayEquals(deflated, Files.readAllBytes(fileSystem.getPath("/cryptok")));
            try (var input = reference.getInputStream(reference.getEntry("storedok"))) {
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(fileSystem.getPath("/storedok")));
            }
        }
    }

    /// Requires checked rejection when deferred local validation and full entry reads are included.
    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentArchives")
    void rejectsInconsistentRecords(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Returns passwords and plaintexts from libzip's successful decryption regressions.
    private static Stream<Arguments> encryptedArchives() {
        return Stream.of(
                Arguments.of("encrypt.zip", "foo", "encrypted", "foo\n"),
                Arguments.of("encrypt-aes128.zip", "foofoofoo", "encrypted", "encrypted\n"),
                Arguments.of("encrypt-aes192.zip", "foofoofoo", "encrypted", "encrypted\n"),
                Arguments.of("encrypt-aes256.zip", "foofoofoo", "encrypted", "encrypted\n"),
                Arguments.of("empty-pkware.zip", "1", "0", ""));
    }

    /// Verifies plaintext, authentication, empty bodies, and rejection with a wrong password.
    @ParameterizedTest(name = "{0}")
    @MethodSource("encryptedArchives")
    void decryptsUpstreamPlaintext(String name, String password, String entry, String plaintext) throws IOException {
        var options = passwordOptions(password);
        byte[] expected = plaintext.getBytes(StandardCharsets.UTF_8);
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), options)) {
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/" + entry)));
        }
        boolean found = false;
        try (var reader = ZipArkivoStreamingReader.open(fixture(name), options)) {
            while (reader.next()) {
                if (reader.readAttributes().path().equals(entry)) {
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(expected, input.readAllBytes());
                        assertEquals(-1, input.read());
                    }
                    found = true;
                }
            }
        }
        assertTrue(found);
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), passwordOptions("wrong"))) {
            assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/" + entry)));
        }
    }

    /// Checks that a damaged authenticated payload is not accepted as successfully decoded content.
    @Test
    void rejectsDamagedAesAuthentication() throws IOException {
        var options = passwordOptions("1234");
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("hmac-error.zip"), options);
             var paths = Files.walk(fileSystem.getPath("/"))) {
            var files = paths.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size());
            assertThrows(IOException.class, () -> Files.readAllBytes(files.get(0)));
        }
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(fixture("hmac-error.zip"), options)) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Returns local and central payloads from libzip's independent extra-field modification fixtures.
    private static Stream<Arguments> extraFieldArchives() {
        return Stream.of(
                Arguments.of("encrypt_plus_extra.zip", "extrafieldcontent", "extrafieldcontent"),
                Arguments.of("encrypt_plus_extra_modified_l.zip", "Extrafieldcontent", "extrafieldcontent"),
                Arguments.of("encrypt_plus_extra_modified_c.zip", "extrafieldcontent", "Extrafieldcontent"));
    }

    /// Keeps local and central extra fields distinct when producers modify only one header.
    @ParameterizedTest(name = "{0}")
    @MethodSource("extraFieldArchives")
    void preservesLocalAndCentralExtraFields(String name, String local, String central) throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            var attributes = Files.readAttributes(fileSystem.getPath("/encrypted"), ZipArkivoEntryAttributes.class);
            assertArrayEquals(local.getBytes(StandardCharsets.US_ASCII), extraField(attributes.localExtraData(), 2345));
            assertArrayEquals(central.getBytes(StandardCharsets.US_ASCII), extraField(attributes.centralDirectoryExtraData(), 2345));
            assertEquals(9, extraField(attributes.localExtraData(), 0x5455).length);
            assertEquals(5, extraField(attributes.centralDirectoryExtraData(), 0x5455).length);
        }
    }

    /// Replays libzip's interleaved independent readers before and after replacing an entry.
    @Test
    void preservesIndependentReadPositionsAfterReplacement(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("multiple.zip");
        Files.copy(fixture("test_open_multiple.zip"), archive);
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path entry = fileSystem.getPath("/stuff");
            assertInterleavedReads(entry, "abcdefgh");
            Files.writeString(entry, "12345678", StandardCharsets.US_ASCII);
            assertInterleavedReads(entry, "12345678");
        }
        try (var reference = new java.util.zip.ZipFile(archive.toFile());
             var input = reference.getInputStream(reference.getEntry("stuff"))) {
            assertArrayEquals("12345678".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
        }
    }

    /// Replays the upstream rename operation and compares the result with libzip's saved output archive.
    @Test
    void renamesEntryWithoutChangingOtherContents(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("rename.zip");
        Files.copy(fixture("testcomment.zip"), archive);
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            Files.move(fileSystem.getPath("/file2"), fileSystem.getPath("/notfile2"));
            assertFalse(Files.exists(fileSystem.getPath("/file2")));
        }
        var expected = ArchiveCorpusAssertions.readZipReference(fixture("rename_ok.zip"));
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
        ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readZipReference(archive), expected);
    }

    /// Executes the read lengths from `fopen_multiple.test` without sharing stream position.
    private static void assertInterleavedReads(Path path, String content) throws IOException {
        byte[] expected = content.getBytes(StandardCharsets.US_ASCII);
        try (InputStream first = Files.newInputStream(path); InputStream second = Files.newInputStream(path)) {
            assertArrayEquals(Arrays.copyOfRange(expected, 0, 2), first.readNBytes(2));
            assertArrayEquals(Arrays.copyOfRange(expected, 0, 4), second.readNBytes(4));
            assertArrayEquals(Arrays.copyOfRange(expected, 2, 5), first.readNBytes(3));
            assertArrayEquals(Arrays.copyOfRange(expected, 4, 7), second.readNBytes(3));
            assertArrayEquals(Arrays.copyOfRange(expected, 5, 8), first.readNBytes(3));
            assertArrayEquals(Arrays.copyOfRange(expected, 7, 8), second.readNBytes(1));
            assertEquals(-1, first.read());
            assertEquals(-1, second.read());
        }
        try (var channel = Files.newByteChannel(path)) {
            channel.position(2);
            ByteBuffer buffer = ByteBuffer.allocate(3);
            while (buffer.hasRemaining()) assertTrue(channel.read(buffer) > 0);
            assertArrayEquals(Arrays.copyOfRange(expected, 2, 5), buffer.array());
        }
    }

    /// Returns one complete extra-field payload, rejecting truncated records or missing identifiers.
    private static byte[] extraField(byte[] data, int identifier) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < data.length;) {
            assertTrue(offset + 4 <= data.length);
            int id = Short.toUnsignedInt(buffer.getShort(offset));
            int size = Short.toUnsignedInt(buffer.getShort(offset + 2));
            assertTrue(offset + 4 + size <= data.length);
            if (id == identifier) return Arrays.copyOfRange(data, offset + 4, offset + 4 + size);
            offset += 4 + size;
        }
        throw new AssertionError("Missing extra field: " + identifier);
    }

    /// Creates ZIP read options containing the ASCII passwords used by the upstream fixtures.
    private static ZipArchiveOptions.Read passwordOptions(String password) {
        return ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(
                ArkivoPasswordProvider.fixed(password.getBytes(StandardCharsets.US_ASCII)));
    }

    /// Resolves a regression file prepared by the verified Gradle download task.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.libzip.testDataDirectory")))
                .resolve("regress").resolve(name);
    }
}
