// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads pinned py7zr archives through independent decoders and seekable entry channels.
@NotNullByDefault
final class Py7zrCorpusTest {
    /// Bounds decoded fixture data independently of the compressed archive size.
    private static final SevenZipArchiveOptions.Read OPTIONS = SevenZipArchiveOptions.READ_DEFAULTS.withCommon(
            SevenZipArchiveOptions.READ_DEFAULTS.common().withLimits(ArchiveReadLimits.builder()
                    .maximumEntrySize(64L * 1024 * 1024)
                    .maximumTotalEntrySize(128L * 1024 * 1024)
                    .maximumDecoderMemorySize(256L * 1024 * 1024).build()));

    /// Archives requiring a password, a different reference decoder, or a rejection assertion.
    private static final @Unmodifiable Set<String> SPECIALIZED = Set.of(
            "crc_corrupted.7z", "encrypted_1.7z", "encrypted_2.7z", "encrypted_3.7z",
            "encrypted_4.7z", "encrypted_5.7z", "encrypted_6.7z", "filename_encryption.7z",
            "lzma_bcj2_1.7z", "lzma2bcj2.7z", "lzma2bcj2_2.7z", "test_lzma2bcj2.7z",
            "ppmd.7z", "testdata-x5-ppmd.7z", "zstd.7z", "p7zip-zstd.7z", "zstdmt-brotli.7z", "lz4.7z",
            "copy_2.7z", "empty.7z", "github_14.7z", "github_14_multi.7z", "root_path_arcname.7z");

    /// Checks names, entry types, sizes, and full decoded contents against Commons Compress.
    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryArchives")
    void readsOrdinaryArchives(String name) throws IOException {
        assertReadable(name, null);
    }

    /// Checks body encryption and encrypted headers with the upstream fixture passwords.
    @ParameterizedTest
    @ValueSource(strings = {"encrypted_1.7z", "encrypted_2.7z", "encrypted_3.7z", "filename_encryption.7z"})
    void readsEncryptedArchives(String name) throws IOException {
        assertReadable(name, name.equals("filename_encryption.7z") ? "hello" : "secret");
    }

    /// Requires a password or rejects an incorrect password before returning complete plaintext.
    @ParameterizedTest
    @ValueSource(strings = {"encrypted_1.7z", "encrypted_3.7z", "encrypted_5.7z",
            "encrypted_6.7z", "filename_encryption.7z"})
    void rejectsMissingAndIncorrectPasswords(String name) {
        for (@Nullable String password : new @Nullable String[]{null, "incorrect"}) {
            assertThrows(IOException.class, () -> {
                try (var fileSystem = SevenZipArkivoFileSystem.open(resource(name), options(password))) {
                    ArchiveCorpusAssertions.readFileSystem(fileSystem);
                }
            });
        }
    }

    /// Rejects the upstream archive whose declared file CRC does not match its plaintext.
    @Test
    void rejectsCorruptCrc() {
        IOException failure = assertThrows(IOException.class, () -> {
            try (var fileSystem = SevenZipArkivoFileSystem.open(resource("crc_corrupted.7z"), OPTIONS)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertEquals("7z entry data does not match CRC-32", failure.getMessage());
    }

    /// Keeps malformed coder IDs, unsafe paths, and unnamed entries out of the file-system namespace.
    @ParameterizedTest
    @CsvSource({
            "copy_2.7z, Unsupported 7z coder method: []",
            "github_14.7z, 7z file entry is missing a name",
            "github_14_multi.7z, 7z file entry is missing a name",
            "root_path_arcname.7z, 7z entry path must be relative"
    })
    void rejectsUnrepresentableArchives(String name, String message) {
        IOException failure = assertThrows(IOException.class, () -> {
            try (var fileSystem = SevenZipArkivoFileSystem.open(resource(name), OPTIONS)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertEquals(message, failure.getMessage());
    }

    /// Rejects the header CRC mismatch also reported by Commons Compress with the fixture password.
    @Test
    void rejectsEncryptedHeaderCrcMismatch() {
        assertThrows(IOException.class, () -> ArchiveCorpusAssertions.readSevenZipReference(
                resource("encrypted_4.7z"), "secret".toCharArray()));
        IOException failure = assertThrows(IOException.class, () -> {
            try (var fileSystem = SevenZipArkivoFileSystem.open(resource("encrypted_4.7z"), options("secret"))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertEquals("7z encoded header stream does not match CRC-32", failure.getMessage());
    }

    /// Reports unsupported extension coders instead of silently returning an empty or partial file.
    @ParameterizedTest
    @ValueSource(strings = {"lz4.7z", "zstdmt-brotli.7z"})
    void rejectsUnsupportedExtensionCoders(String name) {
        IOException failure = assertThrows(IOException.class, () -> {
            try (var fileSystem = SevenZipArkivoFileSystem.open(resource(name), OPTIONS)) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
        assertTrue(failure.getMessage().startsWith("Unsupported 7z coder method:"));
    }

    /// Accepts the signature-only empty archive even though Commons Compress requires a next header.
    @Test
    void readsEmptyArchive() throws IOException {
        try (var fileSystem = SevenZipArkivoFileSystem.open(resource("empty.7z"), OPTIONS)) {
            assertEquals(List.of(), ArchiveCorpusAssertions.readFileSystem(fileSystem));
        }
    }

    /// Compares BCJ2, PPMd, and Zstandard variants with independently decoded equivalent archives.
    @ParameterizedTest
    @CsvSource({
            "lzma2bcj2.7z, lzma2bcj.7z, '', ''",
            "test_lzma2bcj2.7z, solid.7z, '', ''",
            "lzma_bcj2_1.7z, solid.7z, test1.txt, ''",
            "ppmd.7z, solid.7z, '', ''",
            "zstd.7z, test_1.7z, '', ''",
            "encrypted_5.7z, test_1.7z, '', ''",
            "encrypted_6.7z, test_1.7z, '', src/"
    })
    void readsEquivalentCodecVariants(String name, String referenceName, String selectedPath, String prefix)
            throws IOException {
        var expected = ArchiveCorpusAssertions.readSevenZipReference(resource(referenceName), null).stream()
                .filter(entry -> selectedPath.isEmpty() || entry.path().equals(selectedPath))
                .map(entry -> new ArchiveCorpusAssertions.EntryDigest(prefix + entry.path(), entry.directory(),
                        entry.symbolicLink(), entry.size(), entry.crc32(), entry.sha256())).toList();
        try (var fileSystem = SevenZipArkivoFileSystem.open(resource(name),
                options(name.startsWith("encrypted_") ? "secret" : null))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
    }

    /// Verifies special-coder payloads against hashes independently obtained with bsdtar/libarchive 3.8.8.
    ///
    /// Body hashes cover `tar -xOf archive` output; name hashes cover `tar -tf archive` names joined with LF,
    /// without a final LF or directory trailing slashes. Neither command extracts files to the host.
    @ParameterizedTest
    @CsvSource({
            "lzma2bcj2_2.7z, 3, 135658, c738d93b1cebcb2c9a5b1c85f503b4c15fb2a45fe088bc1f0de02c418ecf13ad, 3964438f0785c80c7b0eb4dce650ba57d0bd798d02348b36714fd90f970317ba",
            "testdata-x5-ppmd.7z, 51, 9492828, a45d3f9afbc528ce2bd83839ba7bedb83c500bcb38878f2462bbd3f933047602, 0edb345f1592449c76652732dc41a78ac0c190087578814a5a7f7bce2c8336a0",
            "p7zip-zstd.7z, 5, 490885, 0c0b2e49c6cef48fe4f784d56778cb910867adb5dc3003739cddcc405e047dc7, bd5630cdc95b8c58a8be581d483255c38607194db1f9aef423837a8d13f91984"
    })
    void readsIndependentlyHashedArchives(String name, int entryCount, long totalSize,
                                        String bodyHash, String nameHash) throws IOException, NoSuchAlgorithmException {
        MessageDigest bodyDigest = MessageDigest.getInstance("SHA-256");
        List<String> names = new ArrayList<>();
        long bytesRead = 0;
        try (var reference = SevenZFile.builder().setPath(resource(name)).setMaxMemoryLimitKiB(256 * 1024).get();
             var fileSystem = SevenZipArkivoFileSystem.open(resource(name), OPTIONS)) {
            byte[] buffer = new byte[8192];
            for (var entry : reference.getEntries()) {
                String entryName = entry.getName();
                names.add(entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName);
                Path path = fileSystem.getPath("/" + entryName);
                assertEquals(entry.isDirectory(), Files.isDirectory(path));
                if (!entry.isDirectory()) {
                    long size = 0;
                    try (var input = Files.newInputStream(path)) {
                        for (int count; (count = input.read(buffer)) != -1; ) {
                            assertTrue(count > 0);
                            bodyDigest.update(buffer, 0, count);
                            size += count;
                        }
                    }
                    assertEquals(entry.getSize(), size, entryName);
                    bytesRead += size;
                }
            }
            try (var paths = Files.walk(fileSystem.getPath("/"))) {
                assertEquals(entryCount, paths.count() - 1);
            }
        }
        assertEquals(entryCount, names.size());
        assertEquals(totalSize, bytesRead);
        assertEquals(bodyHash, HexFormat.of().formatHex(bodyDigest.digest()));
        assertEquals(nameHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", names).getBytes(StandardCharsets.UTF_8))));
    }

    /// Reads both physical volumes and compares all entries with the unsplit archive.
    @Test
    void readsSplitArchive() throws IOException {
        var expected = ArchiveCorpusAssertions.readSevenZipReference(resource("lzma2bcj.7z"), null);
        try (var fileSystem = SevenZipArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(
                resource("archive.7z.001"), resource("archive.7z.002"))), OPTIONS)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
    }

    /// Reads solid files in reverse order and seeks backwards using heap and direct buffers.
    @ParameterizedTest
    @ValueSource(strings = {"solid.7z", "test_1.7z", "mblock_1.7z", "lzma2bcj.7z"})
    void seeksAcrossSolidEntries(String name) throws IOException {
        try (var reference = SevenZFile.builder().setPath(resource(name)).setMaxMemoryLimitKiB(256 * 1024).get();
             var fileSystem = SevenZipArkivoFileSystem.open(resource(name), OPTIONS)) {
            var entries = StreamSupport.stream(reference.getEntries().spliterator(), false)
                    .sorted(Comparator.comparing(SevenZArchiveEntry::getName).reversed()).toList();
            for (var entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] expected;
                try (var input = reference.getInputStream(entry)) {
                    expected = input.readAllBytes();
                }
                Path path = fileSystem.getPath("/" + entry.getName());
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                for (boolean direct : new boolean[]{false, true}) {
                    try (var channel = Files.newByteChannel(path)) {
                        assertEquals(expected.length, channel.size());
                        for (int position : new int[]{expected.length, expected.length / 2, 0}) {
                            channel.position(position);
                            int length = Math.min(257, expected.length - position);
                            ByteBuffer target = direct ? ByteBuffer.allocateDirect(length + 6)
                                    : ByteBuffer.allocate(length + 6);
                            for (int i = 0; i < target.capacity(); i++) {
                                target.put(i, (byte) 0x5a);
                            }
                            target.position(3).limit(3 + length);
                            while (target.hasRemaining()) {
                                assertTrue(channel.read(target) > 0, entry.getName());
                            }
                            assertEquals(position + length, channel.position());
                            target.clear();
                            assertEquals((byte) 0x5a, target.get(2));
                            assertEquals((byte) 0x5a, target.get(3 + length));
                            byte[] actual = new byte[length];
                            target.position(3).get(actual);
                            assertArrayEquals(Arrays.copyOfRange(expected, position, position + length), actual);
                            if (position == expected.length) {
                                assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                            }
                        }
                    }
                }
            }
        }
    }

    /// Compares complete contents, timestamps, raw attributes, and symbolic-link targets without host extraction.
    private static void assertReadable(String name, @Nullable String password) throws IOException {
        var expected = ArchiveCorpusAssertions.readSevenZipReference(resource(name),
                password == null ? null : password.toCharArray()).stream()
                .map(entry -> new ArchiveCorpusAssertions.EntryDigest(entry.path().replace('\\', '/'),
                        entry.directory(), entry.symbolicLink(), entry.size(), entry.crc32(), entry.sha256())).toList();
        try (var fileSystem = SevenZipArkivoFileSystem.open(resource(name), options(password))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
            try (var reference = SevenZFile.builder().setPath(resource(name))
                    .setPassword(password == null ? null : password.toCharArray()).setMaxMemoryLimitKiB(256 * 1024).get()) {
                for (var entry : reference.getEntries()) {
                    Path path = fileSystem.getPath("/" + entry.getName().replace('\\', '/'));
                    assertTrue(Files.exists(path, LinkOption.NOFOLLOW_LINKS), entry.getName());
                    var attributes = Files.readAttributes(path, SevenZipArkivoEntryAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    assertEquals(entry.getSize(), attributes.size(), entry.getName());
                    if (entry.getHasWindowsAttributes()) {
                        assertEquals(entry.getWindowsAttributes(), attributes.windowsAttributes(), entry.getName());
                    }
                    if (entry.getHasLastModifiedDate()) {
                        assertEquals(entry.getLastModifiedTime(), attributes.lastModifiedTime(), entry.getName());
                    }
                    if (entry.getHasCrc()) {
                        assertEquals(entry.getCrcValue(), attributes.crc32(), entry.getName());
                    }
                    if (Files.isSymbolicLink(path)) {
                        try (var input = reference.getInputStream(entry)) {
                            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                                    Files.readSymbolicLink(path).toString());
                        }
                    }
                }
            }
        }
    }

    /// Selects ordinary archives whose methods are supported by the independent reference decoder.
    private static Stream<String> ordinaryArchives() throws IOException {
        try (var files = Files.list(resource(""))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".7z") && !SPECIALIZED.contains(name)).sorted().toList().stream();
        }
    }

    /// Adds a UTF-16LE password to the bounded read options when required by the fixture.
    private static SevenZipArchiveOptions.Read options(@Nullable String password) {
        return password == null ? OPTIONS : OPTIONS.withPasswordProvider(
                ArkivoPasswordProvider.fixed(password.getBytes(StandardCharsets.UTF_16LE)));
    }

    /// Resolves an unmodified fixture from the Gradle-verified source archive.
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.py7zr.testDataDirectory"),
                "py7zr test data directory is not configured")).resolve("tests/data").resolve(name);
    }
}
