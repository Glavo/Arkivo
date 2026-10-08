// Copyright (c) 2026 Glavo
// Portions adapted from CPython's test_tarfile.py; see LICENSES/Python.txt.
// SPDX-License-Identifier: MPL-2.0 AND PSF-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks CPython's TAR fixtures against upstream digests and independently decoded metadata.
@NotNullByDefault
final class CPythonTarCorpusTest {
    /// The ordinary-file digest recorded in CPython's test_tarfile.py.
    private static final String REGULAR_SHA256 = "e09e4bc8b3c9d9177e77256353b36c159f5f040531bbd4b024a8f9b9196c71ce";

    /// The logical sparse-file digest recorded in CPython's test_tarfile.py.
    private static final String SPARSE_SHA256 = "4f05a776071146756345ceee937b33fc5644f5a96b9780d1c7d6a32cdf164d7b";

    /// The Latin-1 names used by the historical TAR producers in the fixture.
    private static final String UMLAUTS = "\u00c4\u00d6\u00dc\u00e4\u00f6\u00fc\u00df";

    /// The long GNU path shared by its data and hard-link records.
    private static final String GNU_LONG = "gnu/" + "123/".repeat(125);

    /// The long PAX path shared by its data and hard-link records.
    private static final String PAX_LONG = "pax/" + "123/".repeat(125);

    /// Decodes legacy fixed-width fields and explicitly binary PAX names as Latin-1.
    private static final TarArchiveOptions.Read OPTIONS = TarArchiveOptions.READ_DEFAULTS
            .withMetadataCharsetDetector(ArchiveMetadataCharsetDetector.fixed(StandardCharsets.ISO_8859_1));

    /// Stores derived archives without modifying the downloaded fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Defines the valid members in physical order; values were checked with CPython independently of Arkivo.
    private static @Unmodifiable List<ExpectedEntry> entries() {
        return List.of(
                entry("ustar/conttype", '7', 7011),
                entry("ustar/regtype", '0', 7011),
                entry("ustar/dirtype/", '5', 0),
                entry("ustar/dirtype-with-size/", '5', 0),
                link("ustar/lnktype", '1', "ustar/regtype"),
                link("ustar/symtype", '2', "regtype"),
                entry("ustar/blktype", '4', 0),
                entry("ustar/chrtype", '3', 0),
                entry("ustar/fifotype", '6', 0),
                entry("ustar/sparse", '0', 86016),
                entry("ustar/umlauts-" + UMLAUTS, '0', 7011),
                entry("ustar" + "/12345".repeat(40) + "67/longname", '0', 7011),
                link("./ustar/linktest2/symtype", '2', "../linktest1/regtype"),
                entry("ustar/linktest1/regtype", '0', 7011),
                link("./ustar/linktest2/lnktype", '1', "./ustar/linktest1/regtype"),
                link("symtype2", '2', "ustar/regtype"),
                entry(GNU_LONG + "longname", '0', 7011),
                link(GNU_LONG + "longlink", '1', GNU_LONG + "longname"),
                entry("gnu/sparse", 'S', 86016),
                entry("gnu/sparse-0.0", '0', 86016),
                entry("gnu/sparse-0.1", '0', 86016),
                entry("gnu/sparse-1.0", '0', 86016),
                entry("gnu/regtype-gnu-uid", '0', 7011),
                entry("misc/regtype-old-v7", 0, 7011),
                entry("misc/regtype-hpux-signed-chksum-" + UMLAUTS, '0', 7011),
                entry("misc/regtype-old-v7-signed-chksum-" + UMLAUTS, 0, 7011),
                entry("misc/dirtype-old-v7/", 0, 0),
                entry("misc/regtype-suntar", '0', 7011),
                entry("misc/regtype-xstar", '0', 7011),
                entry(PAX_LONG + "longname", '0', 7011),
                link(PAX_LONG + "longlink", '1', PAX_LONG + "longname"),
                entry("pax/umlauts-" + UMLAUTS, '0', 7011),
                entry("pax/regtype1", '0', 7011),
                entry("pax/regtype2", '0', 7011),
                entry("pax/regtype3", '0', 7011),
                entry("pax/regtype4", '0', 7011),
                entry("pax/hdrcharset-\u00e4\u00f6\u00fc", '0', 7011),
                entry("misc/eof", '0', 0));
    }

    /// Creates an expected member without a link target.
    private static ExpectedEntry entry(String name, int type, long size) {
        return new ExpectedEntry(name, (byte) type, size, null);
    }

    /// Creates an expected link whose physical record contains no body.
    private static ExpectedEntry link(String name, int type, String target) {
        return new ExpectedEntry(name, (byte) type, 0, target);
    }

    /// Reads all valid members with fragmented input and checks exact metadata and complete body digests.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 511, 512, 8192, 65536})
    void readsFragmentedArchive(int chunkSize) throws IOException {
        try (var source = new FragmentedInputStream(validArchive(), chunkSize);
             var reader = TarArkivoStreamingReader.open(source, OPTIONS)) {
            for (ExpectedEntry expected : entries()) {
                assertTrue(reader.next(), expected.name());
                assertMetadata(expected, reader.readAttributes(TarArkivoEntryAttributes.class), false);
                if (expected.hasBody()) {
                    try (var input = reader.openInputStream()) {
                        assertBody(expected, input);
                    }
                }
            }
            assertFalse(reader.next());
            assertFalse(reader.next());
        }
    }

    /// Reads every indexed member, including link targets, with no native filesystem links required.
    @Test
    void readsIndexedMetadataAndBodies() throws IOException {
        Path archive = temporaryDirectory.resolve("valid.tar");
        Files.write(archive, validArchive());
        try (var fileSystem = TarArkivoFileSystem.open(archive, OPTIONS)) {
            for (ExpectedEntry expected : entries()) {
                Path path = fileSystem.getPath("/" + expected.name());
                var attributes = Files.readAttributes(path, TarArkivoEntryAttributes.class, LinkOption.NOFOLLOW_LINKS);
                assertMetadata(expected, attributes, true);
                if (expected.type() == '2') {
                    assertEquals(expected.target(), Files.readSymbolicLink(path).toString());
                }
                if (expected.hasBody() || expected.target() != null) {
                    try (var input = Files.newInputStream(path)) {
                        if (expected.target() != null) {
                            assertEquals(REGULAR_SHA256, digest(input, 7011));
                        } else {
                            assertBody(expected, input);
                        }
                    }
                }
            }
        }
    }

    /// Seeks across every sparse hole/data boundary using heap and direct output buffers.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void seeksAcrossSparseBoundaries(boolean direct) throws IOException {
        Path archive = temporaryDirectory.resolve("sparse.tar");
        Files.write(archive, validArchive());
        try (var fileSystem = TarArkivoFileSystem.open(archive, OPTIONS)) {
            for (String name : List.of("ustar/sparse", "gnu/sparse", "gnu/sparse-0.0",
                    "gnu/sparse-0.1", "gnu/sparse-1.0")) {
                try (var channel = Files.newByteChannel(fileSystem.getPath("/" + name))) {
                    assertEquals(86016, channel.size());
                    ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(19) : ByteBuffer.allocate(19);
                    // Reverse traversal also exercises rewinding an already-read sparse extent.
                    for (int boundary = 86016; boundary >= 0; boundary -= 4096) {
                        int start = Math.max(0, boundary - 7);
                        channel.position(start);
                        buffer.clear().limit(Math.min(buffer.capacity(), 86016 - start));
                        while (buffer.hasRemaining()) {
                            assertTrue(channel.read(buffer) > 0, name);
                        }
                        buffer.flip();
                        for (int offset = start; buffer.hasRemaining(); offset++) {
                            int expected = (offset / 4096 & 1) == 0 ? 0
                                    : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".charAt(offset % 64);
                            assertEquals(expected, Byte.toUnsignedInt(buffer.get()),
                                    name + " at " + offset);
                        }
                    }
                    channel.position(86016);
                    assertEquals(-1, channel.read(buffer.clear()));
                }
            }
        }
    }

    /// Rejects invalid UTF-8 in ordinary PAX text even when legacy headers use Latin-1.
    @Test
    void rejectsUndeclaredLegacyPaxEncoding() throws IOException {
        byte[] original = Files.readAllBytes(fixture("testtar.tar"));
        // CPython deliberately tolerates this record; Arkivo requires UTF-8 unless hdrcharset is BINARY.
        byte[] malformed = Arrays.copyOfRange(original, 416256, 424960);
        assertRejected(malformed);
    }

    /// Rejects CPython's zero-length PAX regression without recursion or unbounded parsing.
    @Test
    void rejectsZeroLengthPaxRecord() throws IOException {
        assertRejected(Files.readAllBytes(fixture("recursion.tar")));
    }

    /// Rejects the malformed PAX record-length encodings enumerated by CPython.
    @ParameterizedTest
    @ValueSource(strings = {" foo=bar\n", "0 \n", "1 \n", "2 \n", "3 =\n", "4 =a\n",
            "1000000 foo=bar\n", "0 foo=bar\n", "-12 foo=bar\n", "000000000000000000000000036 foo=bar\n"})
    void rejectsMalformedPaxLengths(String record) throws IOException {
        byte[] original = Files.readAllBytes(fixture("testtar.tar"));
        byte[] archive = Arrays.copyOfRange(original, 407552, 416256);
        // Keep the original header, its checksum, and the following body intact.
        // Replace only the first local PAX record, leaving the later records to expose desynchronization.
        byte[] replacement = record.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(replacement, 0, archive, 512, replacement.length);
        assertRejected(archive);
    }

    /// Advances after unread or partially read members without losing the following header.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 511, 4097})
    void skipsUnreadBodies(int prefixSize) throws IOException {
        try (var reader = TarArkivoStreamingReader.open(new FragmentedInputStream(validArchive(), 7), OPTIONS)) {
            for (ExpectedEntry expected : entries()) {
                assertTrue(reader.next(), expected.name());
                assertMetadata(expected, reader.readAttributes(TarArkivoEntryAttributes.class), false);
                if (prefixSize != 0 && expected.hasBody()) {
                    try (var input = reader.openInputStream()) {
                        assertEquals(Math.min(prefixSize, expected.size()), input.readNBytes(prefixSize).length);
                    }
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Rejects truncated bodies both when the caller reads them and when advancing skips them.
    @ParameterizedTest
    @ValueSource(ints = {512, 600, 1024, 1200, 7522})
    void rejectsTruncatedRegularBody(int length) throws IOException {
        byte[] original = Files.readAllBytes(fixture("testtar.tar"));
        assertRejected(Arrays.copyOfRange(original, 7680, 7680 + length));
    }

    /// Rejects CPython's long-name records cut off before their following ordinary header.
    @ParameterizedTest
    @ValueSource(ints = {130048, 360960})
    void rejectsTruncatedLongName(int offset) throws IOException {
        byte[] original = Files.readAllBytes(fixture("testtar.tar"));
        assertRejected(Arrays.copyOfRange(original, offset, offset + 3 * 512));
    }

    /// Reads the separate XZ-wrapped empty-file fixture through both archive entry points.
    @Test
    void readsXzWrappedFixture() throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture("testtar.tar.xz")));
             var fileSystem = TarArkivoFileSystem.open(fixture("testtar.tar.xz"))) {
            assertTrue(reader.next());
            assertEquals("test.txt", reader.readAttributes().path());
            assertEquals(0, reader.readAttributes().size());
            try (var input = reader.openInputStream()) {
                assertEquals(-1, input.read());
            }
            assertFalse(reader.next());
            assertEquals(0, Files.readAllBytes(fileSystem.getPath("/test.txt")).length);
        }
    }

    /// Verifies streaming read, streaming skip, and indexed access all report malformed data as I/O failures.
    private void assertRejected(byte[] archive) throws IOException {
        for (boolean readBody : new boolean[]{false, true}) {
            assertThrows(IOException.class, () -> {
                try (var reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive), OPTIONS)) {
                    while (reader.next()) {
                        if (readBody) {
                            try (var input = reader.openInputStream()) {
                                input.transferTo(java.io.OutputStream.nullOutputStream());
                            }
                        }
                    }
                }
            });
        }
        Path path = temporaryDirectory.resolve("malformed.tar");
        Files.write(path, archive);
        assertThrows(IOException.class, () -> {
            try (var fileSystem = TarArkivoFileSystem.open(path, OPTIONS);
                 var paths = Files.walk(fileSystem.getPath("/"))) {
                paths.toList();
            }
        });
    }

    /// Checks independently recorded fields, accounting for NIO's normalized paths and resolved hard-link sizes.
    private static void assertMetadata(ExpectedEntry expected, TarArkivoEntryAttributes actual, boolean indexed) {
        String name = expected.name();
        assertEquals(indexed ? normalized(name) : name, actual.path());
        assertEquals(expected.type(), actual.typeFlag(), name);
        assertEquals(indexed && expected.type() == '1' ? 7011 : expected.size(), actual.size(), name);
        assertEquals(expected.directory(), actual.isDirectory(), name);
        assertEquals(expected.hasBody() || expected.type() == '1', actual.isRegularFile(), name);
        assertEquals(expected.type() == '2', actual.isSymbolicLink(), name);
        assertEquals(expected.target(), actual.linkName(), name);
        int mode = expected.directory() ? 0755 : expected.type() == '2' ? 0777
                : expected.type() == '3' ? 0666 : expected.type() == '4' ? 0660 : 0644;
        if (name.equals("misc/dirtype-old-v7/")) {
            mode |= 040000;
        } else if (name.equals("misc/regtype-suntar")) {
            mode |= 0100000;
        }
        assertEquals(mode, actual.mode(), name);
        long uid = name.equals("gnu/regtype-gnu-uid") ? 0xffff_ffffL
                : name.equals("pax/regtype4") ? 123 : name.startsWith("pax/hdrcharset-") ? 0 : 1000;
        long gid = uid == 1000 ? 100 : uid;
        assertEquals(uid, actual.userId(), name);
        assertEquals(gid, actual.groupId(), name);
        // An empty global PAX value deletes its override; the fixed header supplies regtype2's user name.
        // CPython instead retains the empty global value in its effective metadata.
        @Nullable String user = name.contains("old-v7") ? null
                : name.equals("pax/regtype1") ? "foo" : name.equals("misc/regtype-xstar") ? "lars" : "tarfile";
        @Nullable String group = name.contains("old-v7") ? null
                : name.equals("pax/regtype1") || name.equals("pax/regtype2") ? "bar"
                : name.equals("misc/regtype-xstar") ? "users" : "tarfile";
        assertEquals(user, actual.userName(), name);
        assertEquals(group, actual.groupName(), name);
        assertEquals(Instant.ofEpochSecond(1041808783), actual.lastModifiedTime().toInstant(), name);
        if (name.equals("pax/regtype4")) {
            assertEquals(actual.lastModifiedTime(), actual.recordedLastAccessTime());
            assertEquals(actual.lastModifiedTime(), actual.recordedStatusChangeTime());
        }
        assertNull(actual.recordedCreationTime(), name);
    }

    /// Removes archive spelling that is not retained in indexed paths.
    private static String normalized(String name) {
        String value = name.startsWith("./") ? name.substring(2) : name;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /// Checks complete data rather than successful parsing alone.
    private static void assertBody(ExpectedEntry expected, InputStream input) throws IOException {
        String actual = digest(input, expected.size());
        String expectedDigest = expected.size() == 0
                ? "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                : expected.name().contains("sparse") ? SPARSE_SHA256 : REGULAR_SHA256;
        assertEquals(expectedDigest, actual, expected.name());
    }

    /// Hashes all output with a small buffer and verifies length and repeated EOF.
    private static String digest(InputStream input, long expectedSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        byte[] buffer = new byte[37];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            assertTrue(count > 0);
            total += count;
            assertTrue(total <= expectedSize);
            digest.update(buffer, 0, count);
        }
        assertEquals(expectedSize, total);
        assertEquals(-1, input.read());
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Removes only the deliberately non-UTF-8 ordinary PAX member, tested separately as malformed input.
    private static byte[] validArchive() throws IOException {
        byte[] source = Files.readAllBytes(fixture("testtar.tar"));
        assertEquals(435200, source.length);
        byte[] result = Arrays.copyOf(source, source.length - (424960 - 416256));
        System.arraycopy(source, 424960, result, 416256, source.length - 424960);
        return result;
    }

    /// Locates an unmodified fixture extracted by Gradle from the pinned source release.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.cpython.testDataDirectory")))
                .resolve("Lib/test/archivetestdata").resolve(name);
    }

    /// Stores the physical member fields used by both access-path assertions.
    ///
    /// @param name the original header or extended-header path
    /// @param type the raw header type byte
    /// @param size the logical body size, or zero for non-data members
    /// @param target the stored link target, or null
    @NotNullByDefault
    private record ExpectedEntry(String name, byte type, long size, @Nullable String target) {
        /// Returns whether the member is a directory, including the V7 trailing-slash representation.
        boolean directory() {
            return type == '5' || type == 0 && name.endsWith("/");
        }

        /// Returns whether the member stores regular or sparse file data.
        boolean hasBody() {
            return !directory() && (type == '0' || type == 0 || type == '7' || type == 'S');
        }
    }

    /// Bounds individual source reads to expose headers and bodies split at arbitrary byte positions.
    @NotNullByDefault
    private static final class FragmentedInputStream extends ByteArrayInputStream {
        /// The maximum bytes returned by one bulk read.
        private final int chunkSize;

        /// Creates a stream over the supplied archive bytes.
        FragmentedInputStream(byte[] bytes, int chunkSize) {
            super(bytes);
            this.chunkSize = chunkSize;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, chunkSize));
        }
    }
}
