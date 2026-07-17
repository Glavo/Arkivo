// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoFormat;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.rar.RarArkivoFormat;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.TarArkivoFormat;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.glavo.arkivo.archive.zip.ZipArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.spi.FileSystemProvider;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies runtime discovery through the all-in-one aggregate module.
@NotNullByDefault
final class AllAggregationTest {
    /// Verifies that all aggregated archive and compression formats are visible.
    @Test
    void discoversAllAggregatedFormats() {
        Set<String> archiveFormatNames = ArkivoFormats.installed()
                .stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> compressionFormatNames = CompressionFormats.installed()
                .stream()
                .map(CompressionFormat::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("7z", "ar", "rar", "tar", "zip"), archiveFormatNames);
        assertTrue(ArkivoFormats.installed().stream().allMatch(ArkivoFileSystemFormat.class::isInstance));
        assertEquals(
                Set.of(
                        "bzip2",
                        "deflate",
                        "deflate64",
                        "gzip",
                        "lzma",
                        "lzma-raw",
                        "lzma2",
                        "ppmd",
                        "xz",
                        "zlib",
                        "zstd"
                ),
                compressionFormatNames
        );
    }

    /// Verifies every installed archive provider through the standard JDK URI and path entry points.
    @Test
    @SuppressWarnings("resource")
    void opensInstalledFileSystemProviders(@TempDir Path temporaryDirectory) throws IOException {
        byte[] content = "value".getBytes(StandardCharsets.UTF_8);
        Path arArchive = temporaryDirectory.resolve("sample.ar");
        Path tarArchive = temporaryDirectory.resolve("sample.tar");
        Path zipArchive = temporaryDirectory.resolve("sample.zip");
        Path sevenZipArchive = temporaryDirectory.resolve("sample.7z");
        Path rarArchive = temporaryDirectory.resolve("sample.rar");
        createArFixture(arArchive, content);
        createTarFixture(tarArchive, content);
        createZipFixture(zipArchive, content);
        createSevenZipFixture(sevenZipArchive, content);
        createRarFixture(rarArchive, content);

        Map<String, Path> archives = Map.of(
                ArArkivoFormat.instance().uriScheme(), arArchive,
                TarArkivoFormat.instance().uriScheme(), tarArchive,
                ZipArkivoFormat.instance().uriScheme(), zipArchive,
                SevenZipArkivoFormat.instance().uriScheme(), sevenZipArchive,
                RarArkivoFormat.instance().uriScheme(), rarArchive
        );
        Set<String> installedSchemes = FileSystemProvider.installedProviders()
                .stream()
                .map(FileSystemProvider::getScheme)
                .filter(scheme -> scheme.startsWith("arkivo+"))
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(archives.keySet(), installedSchemes);

        for (Map.Entry<String, Path> archive : archives.entrySet()) {
            URI fileSystemUri = URI.create(archive.getKey() + ":" + archive.getValue().toUri().toASCIIString());
            URI entryUri = URI.create(fileSystemUri + "!/value.txt");
            try (FileSystem fileSystem = FileSystems.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, FileSystems.getFileSystem(fileSystemUri));
                Path entry = Path.of(entryUri);
                assertEquals(entryUri, entry.toUri());
                assertArrayEquals(content, Files.readAllBytes(entry), archive.getKey());
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> FileSystems.newFileSystem(fileSystemUri, Map.of()),
                        archive.getKey()
                );
            }
            assertThrows(
                    FileSystemNotFoundException.class,
                    () -> FileSystems.getFileSystem(fileSystemUri),
                    archive.getKey()
            );
        }

        for (Map.Entry<String, Path> archive : archives.entrySet()) {
            assertPathFileSystem(archive.getValue(), archive.getKey(), content);

            Path extensionlessArchive = temporaryDirectory.resolve(
                    "extensionless-" + archive.getKey().substring("arkivo+".length())
            );
            Files.copy(archive.getValue(), extensionlessArchive);
            assertPathFileSystem(extensionlessArchive, archive.getKey(), content);
        }

        Path misleadingZipArchive = temporaryDirectory.resolve("zip-content.ar");
        Files.copy(zipArchive, misleadingZipArchive);
        assertPathFileSystem(misleadingZipArchive, ZipArkivoFormat.instance().uriScheme(), content);

        Path compressedTarArchive = temporaryDirectory.resolve("sample.tar.gz");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(compressedTarArchive))) {
            output.write(Files.readAllBytes(tarArchive));
        }
        assertPathFileSystem(compressedTarArchive, TarArkivoFormat.instance().uriScheme(), content);

        Path unknownArchive = temporaryDirectory.resolve("unknown.bin");
        Files.write(unknownArchive, new byte[]{1, 2, 3, 4});
        assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(unknownArchive));
    }

    /// Verifies unified signature detection and archive format descriptors.
    @Test
    void detectsAllAggregatedArchiveFormats() throws IOException {
        assertDetected("zip", new byte[]{'P', 'K', 3, 4});
        assertDetected("7z", new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        assertDetected("rar", new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00});
        assertDetected("ar", "!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        assertDetected("tar", tarArchive());
        assertDetected("tar", new byte[1024]);

        assertEquals("7z", requireFormat("SeVeNzIp").name());
        assertEquals(List.of("zip", "jar"), requireFormat("zip").fileExtensions());
        assertEquals(
                List.of(
                        "tar",
                        "tar.gz",
                        "tgz",
                        "tar.bz2",
                        "tbz2",
                        "tbz",
                        "tar.xz",
                        "txz",
                        "tar.lzma",
                        "tlz",
                        "tar.zst",
                        "tzst"
                ),
                requireFormat("tar").fileExtensions()
        );
        assertEquals(List.of("7z"), requireFormat("7z").fileExtensions());
        assertEquals(List.of("rar"), requireFormat("rar").fileExtensions());
        assertEquals(List.of("a", "ar", "deb"), requireFormat("ar").fileExtensions());

        byte[] signature = new byte[]{'P', 'K', 5, 6};
        ByteBuffer prefix = ByteBuffer.allocate(signature.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(1);
        prefix.put(signature);
        prefix.flip();
        prefix.position(1);
        prefix.mark();

        assertEquals("zip", requireDetected(ArkivoFormats.detect(prefix)).name());
        assertEquals(1, prefix.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(1, prefix.position());
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
    }

    /// Verifies path detection and position restoration for seekable channels.
    @Test
    void detectsPathsAndRestoresChannelPositions() throws IOException {
        byte[] archive = tarArchive();
        Path path = Files.createTempFile("arkivo-format-probe-", ".bin");
        try {
            Files.write(path, archive);
            assertEquals("tar", requireDetected(ArkivoFormats.detect(path)).name());

            int archiveOffset = 3;
            byte[] embedded = new byte[archiveOffset + archive.length];
            System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
            Files.write(path, embedded);
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(archiveOffset);
                assertEquals("tar", requireDetected(ArkivoFormats.detect(channel)).name());
                assertEquals(archiveOffset, channel.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies TAR checksum rejection and channel restoration after a no-progress failure.
    @Test
    void rejectsInvalidTarChecksumsAndRestoresFailedProbes() throws IOException {
        byte[] invalidTar = tarArchive();
        invalidTar[0] ^= 1;
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(invalidTar)));
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(new byte[512])));

        try (ZeroReadSeekableByteChannel channel = new ZeroReadSeekableByteChannel()) {
            channel.position(7L);
            IOException exception = assertThrows(IOException.class, () -> ArkivoFormats.detect(channel));
            assertEquals("Archive format probe made no progress", exception.getMessage());
            assertEquals(7L, channel.position());

            IOException codecException = assertThrows(IOException.class, () -> CompressionFormats.detect(channel));
            assertEquals("Compression format probe made no progress", codecException.getMessage());
            assertEquals(7L, channel.position());
        }
    }

    /// Verifies ByteBuffer round trips through every aggregated bidirectional compression codec.
    @Test
    void roundTripsBuffersThroughAllAggregatedCodecs() throws IOException {
        byte[] expected = "Arkivo ByteBuffer codec round trip\n"
                .repeat(256)
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();

            ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 4);
            source.position(2);
            source.put(expected);
            source.flip();
            source.position(2);

            ByteBuffer compressed = ByteBuffer.allocateDirect(expected.length * 2 + 4096);
            int compressedStart = 3;
            compressed.position(compressedStart);
            codec.compress(source, compressed);
            assertFalse(source.hasRemaining(), codec.format().name());

            compressed.limit(compressed.position());
            compressed.position(compressedStart);
            ByteBuffer decoded = ByteBuffer.allocateDirect(expected.length + 4);
            int decodedStart = 2;
            decoded.position(decodedStart);
            decoded.limit(decodedStart + expected.length);
            CompressionCodec<?> decoderCodec = codec instanceof PPMdCodec ppmdCodec
                    ? ppmdCodec.withDecodedSize(expected.length)
                    : codec;
            decoderCodec.decompress(compressed, decoded);

            if (!"ppmd".equals(codec.format().name())) {
                assertFalse(compressed.hasRemaining(), codec.format().name());
            }
            assertEquals(decoded.limit(), decoded.position(), codec.format().name());
            assertArrayEquals(expected, bufferBytes(decoded, decodedStart, decoded.position()), codec.format().name());
        }
    }

    /// Verifies unified detection for every codec with a reliable stream signature.
    @Test
    void detectsAllSignatureBearingCompressionFormats() throws IOException {
        Set<String> expectedNames = Set.of("bzip2", "gzip", "xz", "zlib", "zstd");
        Set<String> detectedNames = CompressionFormats.installed()
                .stream()
                .filter(format -> format.probeSize() > 0)
                .map(CompressionFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(expectedNames, detectedNames);

        byte[] content = "Arkivo compression probe".getBytes(StandardCharsets.UTF_8);
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            if (format.probeSize() == 0) {
                continue;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (OutputStream compressor = codec.newOutputStream(output)) {
                compressor.write(content);
            }
            byte[] compressed = output.toByteArray();
            ByteBuffer prefix = ByteBuffer.allocate(compressed.length + 2);
            prefix.position(1);
            prefix.put(compressed);
            prefix.flip();
            prefix.position(1);

            @Nullable CompressionFormat detected = CompressionFormats.detect(prefix);
            assertNotNull(detected, codec.format().name());
            assertSame(format, detected);
            assertEquals(1, prefix.position());
        }
    }

    /// Returns bytes from the given absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Creates a small TAR archive with a checksummed regular-file header.
    private static byte[] tarArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            var writerEntry357 = writer.beginFile("value.txt");
            try (OutputStream body = writerEntry357.openOutputStream()) {
                body.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    /// Creates an AR fixture containing one stored entry.
    private static void createArFixture(Path path, byte[] content) throws IOException {
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(path)) {
            writeStreamingEntry(writer, content);
        }
    }

    /// Creates a TAR fixture containing one stored entry.
    private static void createTarFixture(Path path, byte[] content) throws IOException {
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(path)) {
            writeStreamingEntry(writer, content);
        }
    }

    /// Creates a ZIP fixture containing one Deflate entry.
    private static void createZipFixture(Path path, byte[] content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("value.txt"));
            output.write(content);
            output.closeEntry();
        }
    }

    /// Creates a 7z fixture containing one Copy entry.
    private static void createSevenZipFixture(Path path, byte[] content) throws IOException {
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(path)) {
            writeStreamingEntry(writer, content);
        }
    }

    /// Writes one regular entry through a format-independent streaming writer.
    private static void writeStreamingEntry(ArkivoStreamingWriter writer, byte[] content) throws IOException {
        var writerEntry397 = writer.beginFile("value.txt");
        try (OutputStream body = writerEntry397.openOutputStream()) {
            body.write(content);
        }
    }

    /// Opens an archive through JDK path discovery and verifies its provider and fixture entry.
    private static void assertPathFileSystem(Path archive, String expectedScheme, byte[] expectedContent)
            throws IOException {
        try (FileSystem fileSystem = FileSystems.newFileSystem(archive)) {
            String expectedPathScheme = ZipArkivoFormat.instance().uriScheme().equals(expectedScheme)
                    ? "jar"
                    : expectedScheme;
            assertEquals(expectedPathScheme, fileSystem.provider().getScheme());
            assertArrayEquals(expectedContent, Files.readAllBytes(fileSystem.getPath("/value.txt")), expectedScheme);
        }
    }

    /// Creates a minimal RAR4 fixture containing one stored entry.
    private static void createRarFixture(Path path, byte[] content) throws IOException {
        byte[] name = "value.txt".getBytes(StandardCharsets.UTF_8);
        CRC32 contentCrc32 = new CRC32();
        contentCrc32.update(content);

        ByteArrayOutputStream fileFields = new ByteArrayOutputStream();
        writeUInt32(fileFields, content.length);
        writeUInt32(fileFields, content.length);
        fileFields.write(3);
        writeUInt32(fileFields, contentCrc32.getValue());
        writeUInt32(fileFields, 0L);
        fileFields.write(29);
        fileFields.write(0x30);
        writeUInt16(fileFields, name.length);
        writeUInt32(fileFields, 33_188L);
        fileFields.write(name);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00});
        writeRar4Block(archive, 0x73, 0L, new byte[6], new byte[0]);
        writeRar4Block(archive, 0x74, 0x8000L, fileFields.toByteArray(), content);
        writeRar4Block(archive, 0x7b, 0L, new byte[0], new byte[0]);
        Files.write(path, archive.toByteArray());
    }

    /// Writes one complete RAR4 block.
    private static void writeRar4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields,
            byte[] data
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7L + fields.length);
        headerData.write(fields);

        byte[] headerBytes = headerData.toByteArray();
        CRC32 headerCrc32 = new CRC32();
        headerCrc32.update(headerBytes);
        writeUInt16(output, headerCrc32.getValue());
        output.write(headerBytes);
        output.write(data);
    }

    /// Writes one unsigned 16-bit little-endian value.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    /// Writes one unsigned 32-bit little-endian value.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }

    /// Asserts that a prefix is detected as the expected installed format.
    private static void assertDetected(String expectedName, byte[] prefix) {
        assertEquals(expectedName, requireDetected(ArkivoFormats.detect(ByteBuffer.wrap(prefix))).name());
    }

    /// Returns the installed format with the given name or alias.
    private static ArkivoFormat requireFormat(String name) {
        @Nullable ArkivoFormat format = ArkivoFormats.find(name);
        assertNotNull(format);
        return format;
    }

    /// Returns a detected format after asserting that detection succeeded.
    private static ArkivoFormat requireDetected(@Nullable ArkivoFormat format) {
        assertNotNull(format);
        return format;
    }

    /// Implements a seekable channel whose reads never make progress.
    @NotNullByDefault
    private static final class ZeroReadSeekableByteChannel implements SeekableByteChannel {
        /// The current channel position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a zero-read channel.
        private ZeroReadSeekableByteChannel() {
        }

        /// Reports no read progress.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            return 0;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns a synthetic channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return 1024L;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
