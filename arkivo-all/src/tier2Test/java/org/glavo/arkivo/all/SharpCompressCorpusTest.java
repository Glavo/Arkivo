// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares independently produced archives with the original files in the pinned SharpCompress release.
@NotNullByDefault
final class SharpCompressCorpusTest {
    /// Archive variants for which upstream extraction tests use the three original files.
    private static final @Unmodifiable List<String> ARCHIVES = List.of(
            "7Zip.ARM.7z", "7Zip.ARM64.7z", "7Zip.ARMT.7z", "7Zip.BCJ.7z", "7Zip.BCJ2.7z",
            "7Zip.BZip2.7z", "7Zip.Copy.7z", "7Zip.Filters.7z", "7Zip.IA64.7z", "7Zip.LZMA.7z",
            "7Zip.LZMA2.7z", "7Zip.PPC.7z", "7Zip.PPMd.7z", "7Zip.RISCV.7z", "7Zip.SPARC.7z",
            "7Zip.ZSTD.7z", "7Zip.delta.7z", "7Zip.eos.7z", "7Zip.nonsolid.7z", "7Zip.solid.7z",
            "Rar15.rar", "Rar2.rar", "Rar4.rar", "Rar.rar", "Rar.none.rar", "Rar.solid.rar",
            "Rar.comment.rar", "Rar5.rar", "Rar5.none.rar", "Rar5.solid.rar", "Rar5.comment.rar",
            "Rar5.crc_blake2.rar",
            "Tar.tar", "Tar.tar.Z", "Tar.tar.bz2", "Tar.tar.gz", "Tar.tar.lz", "Tar.tar.xz", "Tar.tar.zst",
            "Tar.noEmptyDirs.tar", "Tar.noEmptyDirs.tar.bz2", "Tar.noEmptyDirs.tar.lz", "Tar.oldgnu.tar.gz",
            "Zip.bzip2.dd.zip", "Zip.bzip2.noEmptyDirs.zip", "Zip.bzip2.zip",
            "Zip.deflate.dd-.zip", "Zip.deflate.dd.zip", "Zip.deflate.noEmptyDirs.zip", "Zip.deflate.zip",
            "Zip.deflate64.zip", "Zip.lzma.dd.zip", "Zip.lzma.noEmptyDirs.zip", "Zip.lzma.zip",
            "Zip.none.noEmptyDirs.zip", "Zip.none.zip", "Zip.zip64.zip", "Zip.zipx", "WinZip26.zip", "WinZip26_BZip2.zipx",
            "WinZip26_LZMA.zipx", "WinZip27_XZ.zipx",
            "7Zip.LZMA.Aes.7z", "7Zip.LZMA2.Aes.7z",
            "Rar.encrypted_filesAndHeader.rar", "Rar.encrypted_filesOnly.rar", "Rar.Encrypted.rar",
            "Rar5.encrypted_filesAndHeader.rar", "Rar5.encrypted_filesOnly.rar",
            "Zip.deflate.WinzipAES.zip", "Zip.deflate.WinzipAES2.zip", "Zip.lzma.WinzipAES.zip",
            "Zip.bzip2.pkware.zip");

    /// ZIP method 98 uses a PPMd variant not implemented by the ZIP reader.
    private static Stream<String> unsupportedZipArchives() {
        return Stream.of("Zip.ppmd.dd.zip", "Zip.ppmd.noEmptyDirs.zip", "Zip.ppmd.zip");
    }

    /// Returns the fixed inventory of ordinary archive variants.
    private static Stream<String> archives() {
        return ARCHIVES.stream();
    }

    /// Returns formats that expose sequential entry access, including compressed TAR wrappers.
    private static Stream<String> streamingArchives() {
        return archives().filter(name -> !name.startsWith("7Zip."));
    }

    /// Checks complete entry bodies through the public file-system detection path.
    @ParameterizedTest(name = "{0}")
    @MethodSource("archives")
    void readsOriginalFilesThroughFileSystem(String name) throws IOException {
        try (var fileSystem = ArkivoFormats.openFileSystem(archive(name), options(name))) {
            assertOriginalFiles(fileSystem);
        }
    }

    /// Checks complete entry bodies through sequential format detection.
    @ParameterizedTest(name = "{0}")
    @MethodSource("streamingArchives")
    void readsOriginalFilesThroughStreamingReader(String name) throws IOException {
        Set<String> seen = new HashSet<>();
        try (var reader = ArkivoFormats.openStreamingReader(archive(name), options(name))) {
            while (reader.next()) {
                var attributes = reader.readAttributes();
                if (attributes.isDirectory()) {
                    continue;
                }
                assertTrue(attributes.isRegularFile(), attributes.path());
                String extension = extension(attributes.path());
                assertTrue(seen.add(extension), attributes.path());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(original(extension), input.readAllBytes(), attributes.path());
                    assertEquals(-1, input.read());
                }
            }
        }
        assertEquals(Set.of("exe", "jpg", "txt"), seen);
    }

    /// Verifies the downloaded release retains the independent originals and its license.
    @Test
    void verifiesOriginalInventory() throws IOException {
        assertTrue(Files.isRegularFile(corpus().resolve("LICENSE.txt")));
        try (var files = Files.walk(corpus().resolve("tests/TestArchives/Original"))) {
            assertEquals(3L, files.filter(Files::isRegularFile).count());
        }
        assertEquals(45056, original("exe").length);
        assertEquals(40372, original("jpg").length);
        assertEquals(15498, original("txt").length);
    }

    /// Checks the metadata-only PKWARE fixture, which does not contain the three original files.
    @Test
    void readsPkwareDeflateMetadata() throws IOException {
        try (var fileSystem = ArkivoFormats.openFileSystem(archive("Zip.deflate.pkware.zip"))) {
            assertTrue(Files.isDirectory(fileSystem.getPath("/Folder")));
            var attributes = Files.readAttributes(fileSystem.getPath("/Folder/File.txt"), ZipArkivoEntryAttributes.class);
            assertTrue(attributes.isRegularFile());
            assertEquals(19L, attributes.size());
            assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
            assertEquals(ZipEncryption.ZIP_CRYPTO, attributes.encryption());
        }
        try (var reader = ArkivoFormats.openStreamingReader(archive("Zip.deflate.pkware.zip"))) {
            assertTrue(reader.next());
            assertTrue(reader.readAttributes().isDirectory());
            assertTrue(reader.next());
            var attributes = (ZipArkivoEntryAttributes) reader.readAttributes();
            assertEquals("Folder/File.txt", attributes.path());
            assertEquals(19L, attributes.size());
            assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
            assertEquals(ZipEncryption.ZIP_CRYPTO, attributes.encryption());
        }
    }

    /// Preserves unsupported-method failures for real PPMd ZIP entries through both reading APIs.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedZipArchives")
    void rejectsUnsupportedZipPpmd(String name) throws IOException {
        try (var fileSystem = ArkivoFormats.openFileSystem(archive(name));
             var paths = Files.walk(fileSystem.getPath("/"))) {
            List<Path> entries = paths.filter(Files::isRegularFile).toList();
            assertEquals(3, entries.size());
            for (Path path : entries) {
                IOException failure = assertThrows(IOException.class, () -> Files.readAllBytes(path));
                assertEquals("Unsupported ZIP compression method: 98", failure.getMessage());
            }
        }
        IOException failure = assertThrows(IOException.class, () -> {
            try (var reader = ArkivoFormats.openStreamingReader(archive(name))) {
                while (reader.next()) {
                    if (reader.readAttributes().isRegularFile()) {
                        try (var input = reader.openInputStream()) {
                            input.readAllBytes();
                        }
                    }
                }
            }
        });
        assertEquals("Unsupported ZIP compression method: 98", failure.getMessage());
    }

    /// Supplies the producer's metadata charset or the password documented by upstream extraction tests.
    private static ArchiveReadOptions options(String name) {
        if (name.contains(".Aes.")) {
            return ArchiveReadOptions.DEFAULT.withPasswordProvider(ArkivoPasswordProvider.fixed(
                    "testpassword".getBytes(StandardCharsets.UTF_16LE)));
        }
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.contains("encrypted") || lowerName.contains("winzipaes") || lowerName.contains("pkware")) {
            Charset encoding = name.startsWith("Rar.") ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
            return ArchiveReadOptions.DEFAULT.withPasswordProvider(
                    ArkivoPasswordProvider.fixed("test".getBytes(encoding)));
        }
        return name.startsWith("Tar.") && !name.equals("Tar.oldgnu.tar.gz") && !name.equals("Tar.tar.Z")
                ? ArchiveReadOptions.DEFAULT.withMetadataDecoder(
                        ArchiveMetadataDecoder.forCharset(Charset.forName("IBM866")))
                : ArchiveReadOptions.DEFAULT;
    }

    /// Verifies all three original bodies, allowing producer-specific names as in the upstream extension-based tests.
    private static void assertOriginalFiles(ArkivoFileSystem fileSystem) throws IOException {
        Set<String> seen = new HashSet<>();
        try (var paths = Files.walk(fileSystem.getPath("/"))) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), path.toString());
                String extension = extension(path.toString());
                assertTrue(seen.add(extension), path.toString());
                assertArrayEquals(original(extension), Files.readAllBytes(path), path.toString());
            }
        }
        assertEquals(Set.of("exe", "jpg", "txt"), seen);
    }

    /// Returns the original file identified by its unique extension within this corpus.
    private static byte[] original(String extension) throws IOException {
        String name = switch (extension) {
            case "exe" -> "exe/test.exe";
            case "jpg" -> "jpg/test.jpg";
            case "txt" -> "\u0442\u0435\u0441\u0442.txt";
            default -> throw new AssertionError("Unexpected original extension: " + extension);
        };
        return Files.readAllBytes(corpus().resolve("tests/TestArchives/Original").resolve(name));
    }

    /// Returns the lower-case extension used to identify a producer's copy of an original file.
    private static String extension(String path) {
        return path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /// Resolves a pinned archive without copying binary resources into the repository.
    private static Path archive(String name) {
        return corpus().resolve("tests/TestArchives/Archives").resolve(name);
    }

    /// Returns the corpus directory populated by the Gradle preparation task.
    private static Path corpus() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.sharpcompress.testDataDirectory")));
    }
}
