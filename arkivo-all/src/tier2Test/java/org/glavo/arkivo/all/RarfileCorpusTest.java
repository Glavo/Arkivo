// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.RarArchiveOptions;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.archive.rar.RarArkivoFileSystem;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies real rarfile regression archives against upstream metadata and independently generated plaintext.
@NotNullByDefault
final class RarfileCorpusTest {
    /// Returns archives containing the numbered text used by upstream seek and parallel-read tests.
    private static Stream<String> numberedArchives() {
        return Stream.of("seektest.rar", "rar3-seektest.sfx", "rar3-solid.rar", "rar5-crc.rar", "rar5-crc.sfx",
                "rar5-blake.rar", "rar5-psw.rar", "rar5-psw-blake.rar", "rar5-hpsw.rar", "rar5-solid.rar",
                "rar5-solid-qo.rar", "rar5-quick-open.rar", "rar5-hlink.rar", "rar5-dups.rar", "rar5-times.rar");
    }

    /// Reads every numbered entry, including solid history, encrypted checksums, and redirected file bodies.
    @ParameterizedTest(name = "{0}")
    @MethodSource("numberedArchives")
    void readsNumberedContents(String name) throws IOException {
        byte @Unmodifiable [] expected = numberedText(0, 512, 3, '0');
        CRC32 crc = new CRC32();
        crc.update(expected);
        assertEquals(0xc5b7e6a2L, crc.getValue());
        var names = expectedNames(name);
        var options = passwordOptions(name, "password");
        List<String> seen = new ArrayList<>();
        try (var reader = RarArkivoStreamingReader.open(fixture(name), options)) {
            while (reader.next()) {
                var attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
                assertTrue(attributes.isRegularFile(), attributes.path());
                seen.add(attributes.path().replace('\\', '/'));
                assertEquals(expected.length, attributes.size());
                try (var input = reader.openInputStream()) {
                    if (attributes.redirectionType() == RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK
                            || attributes.redirectionType() == RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY) {
                        assertEquals("stest1.txt", attributes.redirectionTarget());
                        assertArrayEquals(new byte[0], input.readAllBytes(), attributes.path());
                    } else {
                        assertArrayEquals(expected, input.readAllBytes(), attributes.path());
                    }
                    assertEquals(-1, input.read());
                }
            }
        }
        assertEquals(names, seen);
        try (var fileSystem = RarArkivoFileSystem.open(fixture(name), options)) {
            // Reading in reverse order also exercises reconstruction of preceding solid history.
            for (int index = names.size() - 1; index >= 0; index--) {
                Path path = fileSystem.getPath(names.get(index));
                assertArrayEquals(expected, Files.readAllBytes(path), path.toString());
                try (var first = Files.newByteChannel(path); var second = Files.newByteChannel(path)) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(7);
                    for (int offset : new int[]{1023, 0, 2041, 3, 1024}) {
                        first.position(offset);
                        buffer.clear();
                        while (buffer.hasRemaining()) assertTrue(first.read(buffer) > 0);
                        buffer.flip();
                        byte[] actual = new byte[buffer.remaining()];
                        buffer.get(actual);
                        assertArrayEquals(Arrays.copyOfRange(expected, offset, offset + 7), actual);
                        assertEquals(0, second.position());
                    }
                    second.position(expected.length);
                    assertEquals(-1, second.read(ByteBuffer.allocate(1)));
                }
            }
        }
    }

    /// Returns automatic and explicit volume discovery for both RAR generations and old-style names.
    private static Stream<Arguments> splitArchives() {
        return Stream.of("rar3-old.rar", "rar3-vols.part1.rar", "rar5-vols.part1.rar")
                .flatMap(name -> Stream.of(false, true).map(explicit -> Arguments.of(name, explicit)));
    }

    /// Reads complete split bodies and checks upstream CRCs, sizes, and the entry following the split file.
    @ParameterizedTest(name = "{0}, explicit={1}")
    @MethodSource("splitArchives")
    void readsAllVolumes(String name, boolean explicit) throws IOException {
        List<Path> volumes = name.equals("rar3-old.rar")
                ? List.of(fixture(name), fixture("rar3-old.r00"), fixture("rar3-old.r01"))
                : List.of(fixture(name), fixture(name.replace("part1", "part2")),
                        fixture(name.replace("part1", "part3")));
        List<String> names = List.of("vols/bigfile.txt", "vols/smallfile.txt");
        List<byte[]> bodies = List.of(numberedText(1, 5000, 40, ' '), numberedText(1, 50, 40, ' '));
        long[] crcs = {0x509ad74cL, 0xd08a1f86L};
        for (int index = 0; index < bodies.size(); index++) {
            CRC32 crc = new CRC32();
            crc.update(bodies.get(index));
            assertEquals(crcs[index], crc.getValue());
        }
        try (var reader = explicit ? RarArkivoStreamingReader.open(ArkivoVolumeSource.of(volumes))
                : RarArkivoStreamingReader.open(fixture(name))) {
            for (int index = 0; index < names.size(); index++) {
                assertTrue(reader.next());
                assertEquals(names.get(index), reader.readAttributes().path().replace('\\', '/'));
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(bodies.get(index), input.readAllBytes());
                }
            }
            assertFalse(reader.next());
        }
        try (var fileSystem = explicit ? RarArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))
                : RarArkivoFileSystem.open(fixture(name))) {
            for (int index = names.size() - 1; index >= 0; index--) {
                assertArrayEquals(bodies.get(index), Files.readAllBytes(fileSystem.getPath(names.get(index))));
            }
        }
    }

    /// Rejects an attempt to start reading from the middle of a real volume sequence.
    @ParameterizedTest
    @ValueSource(strings = {"rar3-old.r00", "rar3-vols.part2.rar", "rar5-vols.part2.rar"})
    void rejectsMissingFirstVolume(String name) {
        assertThrows(IOException.class, () -> {
            try (var reader = RarArkivoStreamingReader.open(fixture(name))) {
                while (reader.next()) {
                    try (var input = reader.openInputStream()) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
            }
        });
    }

    /// Rejects wrong passwords for both encrypted headers and encrypted entry data.
    @ParameterizedTest
    @ValueSource(strings = {"rar202-comment-psw.rar", "rar3-comment-hpsw.rar",
            "rar5-psw.rar", "rar5-psw-blake.rar", "rar5-hpsw.rar"})
    void rejectsWrongPasswords(String name) {
        assertThrows(IOException.class, () -> {
            try (var reader = RarArkivoStreamingReader.open(fixture(name), passwordOptions(name, "wrong"))) {
                while (reader.next()) {
                    try (var input = reader.openInputStream()) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
            }
        });
    }

    /// Reads encrypted and plain empty RAR3 members; their zero CRC is not a password authenticator.
    @ParameterizedTest
    @ValueSource(strings = {"rar3-comment-plain.rar", "rar3-comment-psw.rar", "rar3-comment-hpsw.rar"})
    void readsEmptyCommentedMembers(String name) throws IOException {
        var names = expectedNames(name);
        try (var reader = RarArkivoStreamingReader.open(fixture(name), passwordOptions(name, "password"))) {
            for (String entry : names) {
                assertTrue(reader.next());
                assertEquals(entry, reader.readAttributes().path());
                assertEquals(0, reader.readAttributes().size());
                try (var input = reader.openInputStream()) {
                    assertEquals(-1, input.read());
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Returns the nanosecond creation times from `test_format.py`, including the modification-time fallback.
    private static Stream<Arguments> legacyTimes() {
        return Stream.of(
                Arguments.of(0, "2011-05-10T21:28:47.899345100Z"),
                Arguments.of(1, "2011-05-10T21:28:47Z"),
                Arguments.of(2, "2011-05-10T21:28:47.897843200Z"),
                Arguments.of(3, "2011-05-10T21:28:47.899328Z"),
                Arguments.of(4, "2011-05-10T21:28:47.899345100Z"));
    }

    /// Preserves RAR4 extended-time precision through both metadata access paths.
    @ParameterizedTest
    @MethodSource("legacyTimes")
    void readsLegacyExtendedTimes(int index, String created) throws IOException {
        Path archive = fixture("ctime" + index + ".rar");
        try (var reader = RarArkivoStreamingReader.open(archive)) {
            assertTrue(reader.next());
            var attributes = reader.readAttributes();
            assertEquals(time("2011-05-10T21:28:47.899345100Z"), attributes.lastModifiedTime());
            assertEquals(time(created), attributes.creationTime());
            assertFalse(reader.next());
        }
        try (var fileSystem = RarArkivoFileSystem.open(archive)) {
            var attributes = Files.readAttributes(fileSystem.getPath("/afile.txt"), RarArkivoEntryAttributes.class);
            assertEquals(time("2011-05-10T21:28:47.899345100Z"), attributes.lastModifiedTime());
            assertEquals(time(created), attributes.creationTime());
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/afile.txt")));
        }
    }

    /// Preserves all three nanosecond timestamps and both forms of Unix ownership from real RAR5 headers.
    @Test
    void readsRar5TimesAndOwners() throws IOException {
        try (var fileSystem = RarArkivoFileSystem.open(fixture("ctime5.rar"))) {
            var attributes = Files.readAttributes(fileSystem.getPath("/timed.txt"), RarArkivoEntryAttributes.class);
            assertEquals(time("2020-07-30T20:26:59.677675904Z"), attributes.lastModifiedTime());
            assertEquals(time("2020-07-30T20:28:19.398867888Z"), attributes.creationTime());
            assertEquals(time("2020-07-30T20:27:10.121196721Z"), attributes.lastAccessTime());
        }
        try (var fileSystem = RarArkivoFileSystem.open(fixture("rar5-owner.rar"))) {
            var first = Files.readAttributes(fileSystem.getPath("/owner1.txt"), RarArkivoEntryAttributes.class);
            assertEquals("bin", first.userName());
            assertEquals("sys", first.groupName());
            assertEquals(RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE, first.userId());
            assertEquals(RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE, first.groupId());
            var second = Files.readAttributes(fileSystem.getPath("/owner2.txt"), RarArkivoEntryAttributes.class);
            assertNull(second.userName());
            assertNull(second.groupName());
            assertEquals(400, second.userId());
            assertEquals(500, second.groupId());
            assertArrayEquals("1\n".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(fileSystem.getPath("/owner1.txt")));
            assertArrayEquals("2\n".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(fileSystem.getPath("/owner2.txt")));
        }
    }

    /// Resolves the physical entry list recorded independently by the upstream archive dumper.
    private static @Unmodifiable List<String> expectedNames(String name) throws IOException {
        String reference = name.equals("rar3-seektest.sfx") ? "seektest.rar"
                : name.equals("rar5-crc.sfx") ? "rar5-crc.rar" : name;
        try (var lines = Files.lines(fixture(reference + ".exp"), StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith("  name=")).map(line -> line.substring(7)).toList();
        }
    }

    /// Generates the decimal lines used by the upstream seek and volume fixtures without decoding an archive.
    private static byte @Unmodifiable [] numberedText(int first, int count, int width, char padding) {
        StringBuilder text = new StringBuilder(count * (width + 1));
        for (int value = first; value < first + count; value++) {
            String digits = Integer.toString(value);
            text.append(String.valueOf(padding).repeat(width - digits.length())).append(digits).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /// Encodes the upstream password according to each RAR encryption generation.
    private static RarArchiveOptions passwordOptions(String name, String password) {
        return RarArchiveOptions.DEFAULT.withPasswordProvider(ArkivoPasswordProvider.fixed(
                password.getBytes(name.startsWith("rar3-") ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8)));
    }

    /// Parses an expected UTC timestamp without depending on the machine's default time zone.
    private static FileTime time(String value) {
        return FileTime.from(Instant.parse(value));
    }

    /// Resolves a fixture or expected dump prepared by the verified Gradle download.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.rarfile.testDataDirectory")))
                .resolve("test/files").resolve(name);
    }
}
