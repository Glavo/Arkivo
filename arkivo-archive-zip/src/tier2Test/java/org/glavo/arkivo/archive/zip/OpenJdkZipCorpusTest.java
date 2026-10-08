// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads original OpenJDK ZIP regression archives and verifies their contents before and after updates.
///
/// Covers source-embedded inputs from JDK-8313765, JDK-8314891, JDK-8280404, and JDK-8358456.
@NotNullByDefault
final class OpenJdkZipCorpusTest {
    /// Isolated original archives and update storage for each parameter invocation.
    @TempDir
    private Path directory;

    /// Enumerates unchanged archive literals with independently measured SHA-256 digests.
    private static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("ReadNonStandardExtraHeadersTest", "VALID_APK_FILE",
                        "6d18f334e76d320eb15d08eae5b00b4384812d45320638eb22c97317f5773fff"),
                new Fixture("ReadNonStandardExtraHeadersTest", "COMMONS_COMPRESS_JAR",
                        "11cb13036a6eadcd05c96e15dc4cffe4fb142e37f72071cb79887a16d9e0141c"),
                new Fixture("ReadNonStandardExtraHeadersTest", "ANT_ZIP64_UNICODE_EXTRA_JAR",
                        "907d94db5f8295bf58d2adfb2782fb22a8862cb271921b8f65c84a6686d42738"),
                new Fixture("ReadNonStandardExtraHeadersTest", "ANT_ZIP64_UNICODE_EXTRA_ZIP",
                        "8c0cdb185c5ff4427b7e3cdc9bb2c0bd45164b9d024fce49f5501234dd55cf08"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_ZIP64_EXTRAHDR_SIZE_ONLY_BYTEARRAY",
                        "3a0101df56cf2bd04049555bde921ee373849273f3b38639c7d7b1855a2266a1"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_NO_EXTRA_LEN_BYTEARRAY",
                        "0023d1a1dd433d11f6f028c5982a50f511543d2a1affbe25a0b459907575ca48"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_ZIP64_EXTRAHDR_LOC_ONLY_BYTEARRAY",
                        "1a214cd2b6c25eb9f971408642ece7e5c266cfb26674ae5714024f710bc81f45"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_ZIP64_EXTRAHDR_CSIZE_ONLY_BYTEARRAY",
                        "50e2ed6e2c126238ffed39a893c3a78f38c5468e76e3e0e4eae29759d8067e8a"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_ZEROLEN_ZIP64_EXTRAHDR_BYTEARRAY",
                        "f7c8bad9372f5afc8b80dd51d8da4095a8a4799bf093742d0f1e1f640d9859c5"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_TWO_ZIP64_HEADER_ENTRIES_BYTEARRAY",
                        "6c5dc784056892160e206dee8c98ea0e94a19491a7b70c028b0cf46d4d3f2e08"),
                new Fixture("MissingZIP64EntriesTest", "ZIP_WITH_ZIP64_EXTRAHDR_ALL_BYTEARRAY",
                        "2b1beaa4d83039e79121c3992f601e1f76244dd0439d637bab62c9dfc54d9e01"),
                new Fixture("InvalidCommentLengthTest", "VALID_ZIP_WITH_NO_COMMENTS_BYTES",
                        "2c9f9a466448f374f2e1b7900d34f35322fe3b4f75d3a8a99a935c9746920994"));
    }

    /// Combines the original byte arrays with single-byte, short, and unrestricted source reads.
    private static Stream<Arguments> reads() {
        return fixtures().flatMap(fixture -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(fixture, chunk)));
    }

    /// Verifies complete bodies through indexed paths, channels, and short-read streams.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("reads")
    void readsOriginalArchives(Fixture fixture, int chunk) throws IOException {
        byte[] bytes = fixture.bytes();
        Path archive = Files.write(directory.resolve("original.zip"), bytes);
        Map<String, byte[]> expected = referenceContents(archive);
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertContents(fileSystem, expected);
        }
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
            assertContents(fileSystem, expected);
        }
        assertStreaming(bytes, expected, chunk);
    }

    /// Exercises relocation with both in-memory and temporary-file editing storage.
    private static Stream<Arguments> updates() {
        return fixtures().flatMap(fixture -> Stream.of(false, true).map(memory -> Arguments.of(fixture, memory)));
    }

    /// Renames one original record and adds an entry without dropping unrelated payloads or directories.
    @ParameterizedTest(name = "{0}, memory={1}")
    @MethodSource("updates")
    void updatesOriginalArchives(Fixture fixture, boolean memory) throws IOException {
        Path archive = Files.write(directory.resolve("update.zip"), fixture.bytes());
        Map<String, byte[]> expected = referenceContents(archive);
        String original = firstFile(expected);
        String renamed = "renamed.bin";
        Path storage = Files.createDirectory(directory.resolve("storage"));
        var defaults = ZipArchiveOptions.UPDATE_DEFAULTS;
        var options = defaults.withCommon(defaults.common().withEditStorageFactory(memory
                ? ArkivoEditStorageFactory.memory() : ArkivoEditStorageFactory.temporaryFiles(storage)));
        try (var fileSystem = ZipArkivoFileSystem.update(archive, options)) {
            assertContents(fileSystem, expected);
            Files.move(fileSystem.getPath("/" + original), fileSystem.getPath("/" + renamed));
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{0, 1, 2, (byte) 0xff});
            byte @Nullable [] body = expected.remove(original);
            assertNotNull(body);
            expected.put(renamed, body);
            expected.put("added.bin", new byte[]{0, 1, 2, (byte) 0xff});
            assertContents(fileSystem, expected);
        }
        Map<String, byte[]> actual = referenceContents(archive);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, content) -> assertArrayEquals(content, actual.get(name), name));
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertContents(fileSystem, expected);
        }
        assertStreaming(Files.readAllBytes(archive), expected, 1);
        try (var files = Files.list(storage)) {
            assertEquals(0, files.count());
        }
    }

    /// Enumerates the upstream mutations that demand ZIP64 fields absent from the corresponding extra record.
    private static Stream<Arguments> missingFields() {
        return Stream.of(
                Arguments.of("SIZE_ONLY", 0x61, false), Arguments.of("SIZE_ONLY", 0x77, false),
                Arguments.of("TWO", 0x77, false), Arguments.of("NO_EXTRA", 0x43, false),
                Arguments.of("NO_EXTRA", 0x47, false), Arguments.of("NO_EXTRA", 0x51, true),
                Arguments.of("NO_EXTRA", 0x59, false), Arguments.of("ZERO", 0x57, false),
                Arguments.of("ZERO", 0x5b, false), Arguments.of("ZERO", 0x6d, false));
    }

    /// Rejects missing required ZIP64 values before an update can publish a replacement archive.
    @ParameterizedTest(name = "{0}, field={1}")
    @MethodSource("missingFields")
    void rejectsMissingZip64Fields(String kind, int offset, boolean disk) throws IOException {
        String constant = switch (kind) {
            case "SIZE_ONLY" -> "ZIP_WITH_ZIP64_EXTRAHDR_SIZE_ONLY_BYTEARRAY";
            case "TWO" -> "ZIP_WITH_TWO_ZIP64_HEADER_ENTRIES_BYTEARRAY";
            case "NO_EXTRA" -> "ZIP_WITH_NO_EXTRA_LEN_BYTEARRAY";
            case "ZERO" -> "ZIP_WITH_ZEROLEN_ZIP64_EXTRAHDR_BYTEARRAY";
            default -> throw new AssertionError(kind);
        };
        byte[] bytes = fixture(constant).bytes();
        if (disk) ByteArrayAccess.writeShortLittleEndian(bytes, offset, (short) 0xffff);
        else ByteArrayAccess.writeIntLittleEndian(bytes, offset, -1);
        assertRejectedWithoutReplacement(bytes);
    }

    /// Prevents a central comment from consuming the next entry's fixed header.
    @ParameterizedTest
    @ValueSource(ints = {1, 55, 65535})
    void rejectsCommentLengthOverlappingNextRecord(int length) throws IOException {
        byte[] bytes = fixture("VALID_ZIP_WITH_NO_COMMENTS_BYTES").bytes();
        ByteArrayAccess.writeShortLittleEndian(bytes, 536, (short) length);
        assertRejectedWithoutReplacement(bytes);
    }

    /// Rejects an invalid compressed size with IOException rather than an unchecked allocation failure.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 5, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void rejectsInvalidCompressedSize(int size) throws IOException {
        String source = source("InvalidCompressedSizeTest");
        var literal = Pattern.compile("ZIP_CONTENT_HEX\\s*=\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(source);
        assertTrue(literal.find());
        byte[] bytes = HexFormat.of().parseHex(literal.group(1).replaceAll("\\s", ""));
        assertEquals(4, ByteArrayAccess.readIntLittleEndian(bytes, 0x4d));
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
            assertArrayEquals(new byte[]{0x42, 0x42}, Files.readAllBytes(fileSystem.getPath("/foo-bar")));
        }
        ByteArrayAccess.writeIntLittleEndian(bytes, 0x4d, size);
        assertRejectedWithoutReplacement(bytes);
    }

    /// Requires checked failures through both indexed inputs and verifies failed updates leave the source unchanged.
    private void assertRejectedWithoutReplacement(byte[] bytes) throws IOException {
        Path archive = Files.write(directory.resolve("invalid.zip"), bytes);
        for (int mode = 0; mode < 3; mode++) {
            int selected = mode;
            assertThrows(IOException.class, () -> {
                try (var fileSystem = selected == 0 ? ZipArkivoFileSystem.open(archive)
                        : selected == 1 ? ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))
                        : ZipArkivoFileSystem.update(archive)) {
                    readTree(fileSystem.getPath("/"));
                }
            });
            assertArrayEquals(bytes, Files.readAllBytes(archive));
        }
    }

    /// Uses checked NIO operations so Files.walk cannot wrap the expected failure in UncheckedIOException.
    private static void readTree(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) readTree(child);
            }
        } else {
            try (var input = Files.newInputStream(path)) {
                assertTrue(input.readNBytes(65_537).length <= 65_536, "unexpected expansion");
            }
        }
    }

    /// Reads physical entry names and bounded bodies with the independent JDK implementation.
    private static Map<String, byte[]> referenceContents(Path archive) throws IOException {
        Map<String, byte[]> contents = new TreeMap<>();
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                assertTrue(entry.getSize() >= 0 && entry.getSize() <= 65_536);
                try (var input = zip.getInputStream(entry)) {
                    byte[] body = input.readNBytes(65_537);
                    assertEquals(entry.getSize(), body.length);
                    assertFalse(contents.containsKey(entry.getName()));
                    contents.put(entry.getName(), body);
                }
            }
            assertEquals(zip.size(), contents.size());
        }
        assertFalse(contents.isEmpty());
        return contents;
    }

    /// Checks indexed metadata and all file contents without treating synthesized parent directories as entries.
    private static void assertContents(FileSystem fileSystem, Map<String, byte[]> expected) throws IOException {
        for (var entry : expected.entrySet()) {
            Path path = fileSystem.getPath("/" + entry.getKey());
            var attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            if (entry.getKey().endsWith("/")) {
                assertTrue(attributes.isDirectory());
            } else {
                assertTrue(attributes.isRegularFile());
                assertEquals(entry.getValue().length, attributes.size());
                assertArrayEquals(entry.getValue(), Files.readAllBytes(path), entry.getKey());
            }
        }
        try (var paths = Files.walk(fileSystem.getPath("/"))) {
            assertEquals(expected.keySet().stream().filter(name -> !name.endsWith("/")).count(),
                    paths.filter(Files::isRegularFile).count());
        }
    }

    /// Compares physical local entries with the independently decoded central-directory view.
    private static void assertStreaming(byte[] bytes, Map<String, byte[]> expected, int chunk) throws IOException {
        var seen = new HashSet<String>();
        try (var reader = ZipArkivoStreamingReader.open(new ShortInput(bytes, chunk))) {
            while (reader.next()) {
                var attributes = reader.readAttributes();
                String name = attributes.path();
                assertTrue(seen.add(name), "duplicate local entry: " + name);
                assertTrue(expected.containsKey(name), name);
                if (attributes.isDirectory()) {
                    assertEquals(0, expected.get(name).length);
                } else {
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(expected.get(name), input.readNBytes(65_537), name);
                    }
                }
            }
        }
        assertEquals(expected.keySet(), seen);
    }

    /// Locates a named, digest-pinned source literal.
    private static Fixture fixture(String constant) {
        for (Fixture fixture : fixtures().toList()) {
            if (fixture.constant().equals(constant)) return fixture;
        }
        throw new AssertionError("Unknown fixture: " + constant);
    }

    /// Returns a file to rename without depending on directory-entry order.
    private static String firstFile(Map<String, byte[]> contents) {
        for (String name : contents.keySet()) {
            if (!name.endsWith("/")) return name;
        }
        throw new AssertionError("Archive contains no files");
    }

    /// Reads source text as data, without compiling or executing upstream test code.
    private static String source(String name) throws IOException {
        return Files.readString(Path.of(Objects.requireNonNull(System.getProperty("arkivo.openjdkZip.testDataDirectory")))
                .resolve(name + ".java"));
    }

    /// Describes one original source-embedded archive.
    ///
    /// @param source class containing the byte-array literal
    /// @param constant name of that literal
    /// @param sha256 independently measured digest of the archive bytes
    @NotNullByDefault
    private record Fixture(String source, String constant, String sha256) {
        /// Parses only hexadecimal byte casts and verifies that no other expression was silently skipped.
        private byte[] bytes() throws IOException {
            var declaration = Pattern.compile("public static byte\\[\\]\\s+" + Pattern.quote(constant)
                    + "\\s*=\\s*\\{(.*?)\\};", Pattern.DOTALL).matcher(OpenJdkZipCorpusTest.source(source));
            assertTrue(declaration.find(), constant);
            String body = declaration.group(1);
            var literals = Pattern.compile("\\(byte\\)\\s*0x([0-9a-fA-F]{1,2})\\s*,?").matcher(body);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int end = 0;
            while (literals.find()) {
                assertTrue(body.substring(end, literals.start()).isBlank(), constant);
                output.write(Integer.parseInt(literals.group(1), 16));
                end = literals.end();
            }
            assertTrue(body.substring(end).isBlank(), constant);
            byte[] bytes = output.toByteArray();
            try {
                assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (NoSuchAlgorithmException failure) {
                throw new AssertionError(failure);
            }
            return bytes;
        }

        /// Uses the upstream literal name in parameterized-test reports.
        @Override
        public String toString() {
            return constant;
        }
    }

    /// Supplies short reads even when the consumer sees no available-byte estimate.
    @NotNullByDefault
    private static final class ShortInput extends ByteArrayInputStream {
        /// Maximum bytes returned from a bulk read.
        private final int chunk;

        /// Wraps the original archive with the requested bulk-read bound.
        private ShortInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        /// Restricts reads without changing EOF semantics.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(chunk, length));
        }

        /// Returns no readiness estimate, independently of remaining content.
        @Override
        public synchronized int available() {
            return 0;
        }
    }
}
