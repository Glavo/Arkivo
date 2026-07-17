// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies split and encrypted archive interoperability against Commons Compress.
///
/// Every physical volume is generated in a temporary directory during the test run.
@NotNullByDefault
final class ArchiveVolumeInteroperabilityTest {
    /// Minimum standards-compliant ZIP split size and common test split size.
    private static final long SPLIT_SIZE = 64L * 1024L;

    /// Logical entry path shared by every generated archive.
    private static final String ENTRY_PATH = "directory/volume-资料.bin";

    /// Password text shared by Commons Compress and Arkivo 7z implementations.
    private static final String PASSWORD_TEXT = "volume-päss-密码";

    /// UTF-16LE password bytes required by Arkivo's 7z password provider.
    private static final byte @Unmodifiable [] PASSWORD_BYTES =
            PASSWORD_TEXT.getBytes(StandardCharsets.UTF_16LE);

    /// Deterministic incompressible content large enough to cross multiple physical volumes.
    private static final byte @Unmodifiable [] CONTENT = deterministicContent(192 * 1024);

    /// Verifies Arkivo random-access and streaming readers consume a split ZIP produced independently.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readsCommonsCompressSplitZip() throws IOException {
        Path directory = Files.createTempDirectory("arkivo-interop-split-zip-read-");
        Path archivePath = directory.resolve("independent.zip");
        try {
            CRC32 crc32 = new CRC32();
            crc32.update(CONTENT);
            try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archivePath, SPLIT_SIZE)) {
                ZipArchiveEntry entry = new ZipArchiveEntry(ENTRY_PATH);
                entry.setMethod(ZipArchiveOutputStream.STORED);
                entry.setSize(CONTENT.length);
                entry.setCrc(crc32.getValue());
                output.putArchiveEntry(entry);
                output.write(CONTENT);
                output.closeArchiveEntry();
            }

            @Unmodifiable List<Path> volumes = zipVolumePaths(archivePath);
            assertBoundedVolumes(volumes);
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archivePath)) {
                assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
            }
            assertArrayEquals(CONTENT, readStreamingBody(archivePath, ArchiveReadOptions.DEFAULT));
        } finally {
            deleteTemporaryDirectory(directory);
        }
    }

    /// Verifies Commons Compress consumes a standards-sized split ZIP produced by Arkivo.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void writesCommonsCompressReadableSplitZip() throws IOException {
        Path directory = Files.createTempDirectory("arkivo-interop-split-zip-write-");
        Path archivePath = directory.resolve("arkivo.zip");
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(
                    new ArkivoPathVolumeTarget(new ZipVolumeLayout(archivePath)),
                    SPLIT_SIZE
            )) {
                var writerEntry119 = writer.beginFile(ENTRY_PATH);
                ZipArkivoEntryAttributeView attributes = Objects.requireNonNull(
                        writerEntry119.attributeView(ZipArkivoEntryAttributeView.class)
                );
                attributes.setMethod(ZipMethod.stored());
                try (OutputStream body = writerEntry119.openOutputStream()) {
                    body.write(CONTENT);
                }
            }

            @Unmodifiable List<Path> volumes = zipVolumePaths(archivePath);
            assertBoundedVolumes(volumes);
            try (SeekableByteChannel source =
                         ZipSplitReadOnlySeekableByteChannel.buildFromLastSplitSegment(archivePath);
                 ZipFile zipFile = ZipFile.builder()
                         .setSeekableByteChannel(source)
                         .setMaxNumberOfDisks(16L)
                         .get()) {
                ZipArchiveEntry entry = Objects.requireNonNull(zipFile.getEntry(ENTRY_PATH));
                assertEquals(ZipArchiveOutputStream.STORED, entry.getMethod());
                try (InputStream body = zipFile.getInputStream(entry)) {
                    assertArrayEquals(CONTENT, body.readAllBytes());
                }
            }
        } finally {
            deleteTemporaryDirectory(directory);
        }
    }

    /// Verifies Arkivo reads an AES-encrypted 7z produced by Commons Compress after physical splitting.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readsCommonsCompressEncryptedSplitSevenZip() throws IOException {
        Path directory = Files.createTempDirectory("arkivo-interop-split-7z-read-");
        Path wholeArchive = directory.resolve("independent.7z");
        Path firstVolume = directory.resolve("independent.7z.001");
        char[] password = PASSWORD_TEXT.toCharArray();
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    wholeArchive,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ); SevenZOutputFile output = new SevenZOutputFile(channel, password)) {
                output.setContentCompression(SevenZMethod.LZMA2);
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(ENTRY_PATH);
                entry.setDirectory(false);
                output.putArchiveEntry(entry);
                output.write(CONTENT);
                output.closeArchiveEntry();
            }

            byte[] archive = Files.readAllBytes(wholeArchive);
            Files.delete(wholeArchive);
            @Unmodifiable List<Path> volumes = writeSevenZipVolumes(firstVolume, archive);
            assertBoundedVolumes(volumes);

            SevenZipArchiveOptions.Read options = SevenZipArchiveOptions.READ_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD_BYTES));
            try (ArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume, options)) {
                assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
            }
        } finally {
            Arrays.fill(password, '\0');
            deleteTemporaryDirectory(directory);
        }
    }

    /// Verifies Commons Compress reads Arkivo's encrypted-header LZMA2 output across numbered 7z volumes.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void writesCommonsCompressReadableEncryptedSplitSevenZip() throws IOException {
        Path directory = Files.createTempDirectory("arkivo-interop-split-7z-write-");
        Path firstVolume = directory.resolve("arkivo.7z.001");
        char[] password = PASSWORD_TEXT.toCharArray();
        try {
            SevenZipArchiveOptions.Create options = SevenZipArchiveOptions.CREATE_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD_BYTES))
                    .withEncryptHeaders(true)
                    .withCompression(SevenZipCompression.lzma2(1 << 20));
            try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(
                    new ArkivoPathVolumeTarget(new SevenZipVolumeLayout(firstVolume)),
                    SPLIT_SIZE,
                    options
            )) {
                var writerEntry213 = writer.beginFile(ENTRY_PATH);
                try (OutputStream body = writerEntry213.openOutputStream()) {
                    body.write(CONTENT);
                }
            }

            @Unmodifiable List<Path> volumes = sevenZipVolumePaths(firstVolume);
            assertBoundedVolumes(volumes);
            try (SeekableByteChannel source = MultiReadOnlySeekableByteChannel.forPaths(
                    volumes.toArray(Path[]::new)
            ); SevenZFile sevenZFile = SevenZFile.builder()
                    .setSeekableByteChannel(source)
                    .setPassword(password)
                    .get()) {
                SevenZArchiveEntry entry = Objects.requireNonNull(sevenZFile.getNextEntry());
                assertEquals(ENTRY_PATH, entry.getName());
                assertTrue(contentMethods(entry).contains(SevenZMethod.AES256SHA256));
                assertTrue(contentMethods(entry).contains(SevenZMethod.LZMA2));
                try (InputStream body = sevenZFile.getInputStream(entry)) {
                    assertArrayEquals(CONTENT, body.readAllBytes());
                }
                assertNull(sevenZFile.getNextEntry());
            }
        } finally {
            Arrays.fill(password, '\0');
            deleteTemporaryDirectory(directory);
        }
    }

    /// Reads the only regular body from a path-backed streaming archive reader.
    private static byte[] readStreamingBody(
            Path archivePath,
            ArchiveReadOptions options
    ) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int regularEntryCount = 0;
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(archivePath, options)) {
            while (reader.next()) {
                BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    regularEntryCount++;
                    try (InputStream input = reader.openInputStream()) {
                        input.transferTo(body);
                    }
                }
            }
        }
        assertEquals(1, regularEntryCount);
        return body.toByteArray();
    }

    /// Returns all conventional split ZIP paths in logical disk order.
    private static @Unmodifiable List<Path> zipVolumePaths(Path archivePath) {
        ArrayList<Path> paths = new ArrayList<>();
        String fileName = archivePath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".zip".length());
        for (int disk = 1; disk <= 99; disk++) {
            Path candidate = archivePath.resolveSibling(
                    baseName + ".z" + String.format(Locale.ROOT, "%02d", disk)
            );
            if (!Files.exists(candidate)) {
                break;
            }
            paths.add(candidate);
        }
        paths.add(archivePath);
        return List.copyOf(paths);
    }

    /// Splits one complete 7z byte sequence into conventional numbered physical files.
    private static @Unmodifiable List<Path> writeSevenZipVolumes(
            Path firstVolume,
            byte[] archive
    ) throws IOException {
        int splitSize = Math.toIntExact(SPLIT_SIZE);
        int volumeCount = (archive.length + splitSize - 1) / splitSize;
        ArrayList<Path> paths = new ArrayList<>(volumeCount);
        for (int volume = 1; volume <= volumeCount; volume++) {
            Path path = sevenZipVolumePath(firstVolume, volume);
            int start = (volume - 1) * splitSize;
            int end = Math.min(archive.length, start + splitSize);
            Files.write(path, Arrays.copyOfRange(archive, start, end));
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    /// Returns all existing conventional 7z volume paths from the first physical path.
    private static @Unmodifiable List<Path> sevenZipVolumePaths(Path firstVolume) {
        ArrayList<Path> paths = new ArrayList<>();
        for (int volume = 1; volume <= 999; volume++) {
            Path candidate = sevenZipVolumePath(firstVolume, volume);
            if (!Files.exists(candidate)) {
                break;
            }
            paths.add(candidate);
        }
        return List.copyOf(paths);
    }

    /// Returns one three-digit 7z physical volume path.
    private static Path sevenZipVolumePath(Path firstVolume, int volume) {
        String firstName = firstVolume.getFileName().toString();
        String prefix = firstName.substring(0, firstName.length() - 3);
        return firstVolume.resolveSibling(prefix + String.format(Locale.ROOT, "%03d", volume));
    }

    /// Requires multiple non-empty physical volumes within the common split bound.
    private static void assertBoundedVolumes(@Unmodifiable List<Path> volumes) throws IOException {
        assertTrue(volumes.size() > 1);
        for (int index = 0; index < volumes.size(); index++) {
            long size = Files.size(volumes.get(index));
            assertTrue(size > 0L);
            assertTrue(size <= SPLIT_SIZE);
        }
    }

    /// Returns the declared Commons Compress coder methods in pipeline order.
    private static @Unmodifiable List<SevenZMethod> contentMethods(SevenZArchiveEntry entry) {
        ArrayList<SevenZMethod> methods = new ArrayList<>();
        entry.getContentMethods().forEach(configuration -> methods.add(configuration.getMethod()));
        return List.copyOf(methods);
    }

    /// Generates deterministic pseudorandom bytes without storing a binary fixture.
    private static byte[] deterministicContent(int size) {
        byte[] content = new byte[size];
        new Random(0x41524b49564fL).nextBytes(content);
        return content;
    }

    /// Deletes every temporary volume and then its containing directory.
    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(directory);
    }

    /// Maps split ZIP volumes to conventional `.zNN` paths and the final `.zip` path.
    ///
    /// @param archivePath the final ZIP path
    private record ZipVolumeLayout(Path archivePath) implements ArkivoVolumePathLayout {
        /// Returns the staging directory.
        @Override
        public Path outputDirectory() {
            return Objects.requireNonNull(archivePath.toAbsolutePath().getParent());
        }

        /// Returns the conventional path for one ZIP volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            if (index == finalVolumeIndex) {
                return archivePath;
            }
            String fileName = archivePath.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - ".zip".length());
            return archivePath.resolveSibling(baseName + ".z" + String.format(Locale.ROOT, "%02d", index + 1L));
        }

        /// Returns currently published ZIP volume paths.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            return zipVolumePaths(archivePath).stream().filter(Files::exists).toList();
        }
    }

    /// Maps numbered 7z volumes to conventional `.001`, `.002`, and subsequent paths.
    ///
    /// @param firstVolume the first numbered volume path
    private record SevenZipVolumeLayout(Path firstVolume) implements ArkivoVolumePathLayout {
        /// Returns the staging directory.
        @Override
        public Path outputDirectory() {
            return Objects.requireNonNull(firstVolume.toAbsolutePath().getParent());
        }

        /// Returns the conventional path for one numbered volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            return sevenZipVolumePath(firstVolume, Math.toIntExact(index + 1L));
        }

        /// Returns currently published numbered volume paths.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            return sevenZipVolumePaths(firstVolume);
        }
    }
}
