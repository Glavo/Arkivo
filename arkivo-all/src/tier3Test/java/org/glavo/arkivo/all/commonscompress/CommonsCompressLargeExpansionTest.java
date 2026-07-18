// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Fully expands the multi-gibibyte TAR and nested ZIP64 fixtures reserved for Tier 3.
@NotNullByDefault
final class CommonsCompressLargeExpansionTest {
    /// The fixed buffer size used for constant-memory body validation.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// The TAR block size used by all large-file fixtures.
    private static final int TAR_BLOCK_SIZE = 512;

    /// The maximum metadata prefix retained before the virtual large TAR member body.
    private static final int MAXIMUM_TAR_PREFIX_SIZE = 1024 * 1024;

    /// A reusable zero block used by the virtual large TAR channel.
    private static final byte @Unmodifiable [] ZERO_BYTES = new byte[BUFFER_SIZE];

    /// The decoded size of each upstream large TAR member.
    private static final long LARGE_TAR_ENTRY_SIZE = 8_200L * 1024L * 1024L;

    /// The decoded size of each upstream large ZIP member.
    private static final long LARGE_ZIP_ENTRY_SIZE = 5_000_000_000L;

    /// The expected single large member path for each five-billion-byte ZIP fixture.
    private static final @Unmodifiable Map<String, String> LARGE_ZIP_ENTRY_PATHS = Map.of(
            "5GB_of_Zeros.zip", "5GB_of_Zeros",
            "5GB_of_Zeros_7ZIP.zip", "5GB_of_Zeros",
            "5GB_of_Zeros_PKZip.zip", "zip6/5GB_of_Zeros",
            "5GB_of_Zeros_WinZip.zip", "5GB_of_Zeros",
            "5GB_of_Zeros_jar.zip", "5GB_of_Zeros"
    );

    /// The Gradle-provided directory containing the extracted Commons Compress source release.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.commonsCompress.testDataDirectory";

    /// Fully consumes one 8.2-GiB zero-filled member from each GNU and POSIX large TAR fixture.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"8.posix.tar.gz", "8.star.tar.gz"})
    void fullyReadsLargeTarMember(String resourceName) throws IOException {
        try (InputStream fileInput = Files.newInputStream(resource(resourceName));
             InputStream gzipInput = new GzipCodec().newInputStream(fileInput);
             TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(gzipInput)) {
            assertTrue(reader.next());
            ArchiveEntryAttributes attributes = reader.readAttributes();
            assertTrue(attributes.isRegularFile());
            assertEquals(LARGE_TAR_ENTRY_SIZE, attributes.size());
            assertEquals(LARGE_TAR_ENTRY_SIZE, consumeZeroBytes(reader.openInputStream()));
            assertFalse(reader.next());
        }
    }

    /// Indexes a real large TAR header through a virtual seekable channel without expanding its 8.2-GiB body.
    @Test
    void readsVirtualLargeTarThroughSeekableFileSystem() throws IOException {
        byte @Unmodifiable [] prefix = readLargeTarPrefix(resource("8.posix.tar.gz"));
        VirtualLargeTarChannel channel = new VirtualLargeTarChannel(prefix, LARGE_TAR_ENTRY_SIZE);
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(channel);
             var paths = Files.walk(fileSystem.getPath("/"))) {
            long entryCount = 0L;
            @Nullable Path entry = null;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path candidate = iterator.next();
                if (candidate.equals(fileSystem.getPath("/"))) {
                    continue;
                }
                entryCount++;
                entry = candidate;
            }

            assertEquals(1L, entryCount);
            if (entry == null) {
                throw new AssertionError("Large TAR file system did not expose its member");
            }
            assertTrue(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS), entry.toString());
            assertEquals(LARGE_TAR_ENTRY_SIZE, Files.size(entry));
        }
        assertFalse(channel.isOpen());
    }

    /// Verifies every upstream 100,000-entry ZIP fixture through the seekable file-system API.
    @ParameterizedTest(name = "{0} file system")
    @ValueSource(strings = {
            "100k_Files.zip",
            "100k_Files_7ZIP.zip",
            "100k_Files_PKZip.zip",
            "100k_Files_WinZIP.zip",
            "100k_Files_WindowsCompressedFolders.zip",
            "100k_Files_jar.zip"
    })
    void fullyReadsHundredThousandEntryArchiveUsingFileSystem(
            String archiveName,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        verifyHundredThousandEntryArchive(extractZip64SupportArchive(archiveName, temporaryDirectory));
    }

    /// Verifies every upstream 100,000-entry ZIP fixture through the streaming API.
    @ParameterizedTest(name = "{0} streaming")
    @ValueSource(strings = {
            "100k_Files.zip",
            "100k_Files_7ZIP.zip",
            "100k_Files_PKZip.zip",
            "100k_Files_WinZIP.zip",
            "100k_Files_WindowsCompressedFolders.zip",
            "100k_Files_jar.zip"
    })
    void fullyReadsHundredThousandEntryArchiveUsingStreamingReader(
            String archiveName,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        verifyHundredThousandEntryArchiveStreaming(extractZip64SupportArchive(archiveName, temporaryDirectory));
    }

    /// Verifies every upstream five-billion-byte ZIP fixture through the seekable file-system API.
    @ParameterizedTest(name = "{0} file system")
    @ValueSource(strings = {
            "5GB_of_Zeros.zip",
            "5GB_of_Zeros_7ZIP.zip",
            "5GB_of_Zeros_PKZip.zip",
            "5GB_of_Zeros_WinZip.zip",
            "5GB_of_Zeros_jar.zip"
    })
    void fullyReadsFiveBillionByteArchiveUsingFileSystem(
            String archiveName,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path archive = extractZip64SupportArchive(archiveName, temporaryDirectory);
        verifyLargeZipArchive(archive, requiredLargeZipEntryPath(archiveName));
    }

    /// Verifies every upstream five-billion-byte ZIP fixture through the streaming API.
    @ParameterizedTest(name = "{0} streaming")
    @ValueSource(strings = {
            "5GB_of_Zeros.zip",
            "5GB_of_Zeros_7ZIP.zip",
            "5GB_of_Zeros_PKZip.zip",
            "5GB_of_Zeros_WinZip.zip",
            "5GB_of_Zeros_jar.zip"
    })
    void fullyReadsFiveBillionByteArchiveUsingStreamingReader(
            String archiveName,
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path archive = extractZip64SupportArchive(archiveName, temporaryDirectory);
        verifyLargeZipArchiveStreaming(archive, requiredLargeZipEntryPath(archiveName));
    }

    /// Extracts one named nested ZIP from the compressed upstream ZIP64 support bundle.
    private static Path extractZip64SupportArchive(String archiveName, Path temporaryDirectory) throws IOException {
        Path nestedArchive = temporaryDirectory.resolve(archiveName);
        try (InputStream fileInput = Files.newInputStream(resource("zip64support.tar.bz2"));
             InputStream bzip2Input = new BZip2Codec().newInputStream(fileInput);
             TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(bzip2Input)) {
            while (reader.next()) {
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (!archiveName.equals(normalizePath(attributes.path()))) {
                    continue;
                }
                assertTrue(attributes.isRegularFile(), attributes.path());
                try (InputStream entryInput = reader.openInputStream()) {
                    Files.copy(entryInput, nestedArchive, StandardCopyOption.REPLACE_EXISTING);
                }
                assertEquals(attributes.size(), Files.size(nestedArchive), attributes.path());
                return nestedArchive;
            }
        }
        throw new IOException("Missing ZIP64 support archive: " + archiveName);
    }

    /// Returns the expected regular entry path for one five-billion-byte ZIP fixture.
    private static String requiredLargeZipEntryPath(String archiveName) {
        @Nullable String expectedEntryPath = LARGE_ZIP_ENTRY_PATHS.get(archiveName);
        if (expectedEntryPath == null) {
            throw new IllegalArgumentException("Unknown five-billion-byte ZIP fixture: " + archiveName);
        }
        return expectedEntryPath;
    }

    /// Verifies a nested ZIP exposes exactly 100,000 empty regular files.
    private static void verifyHundredThousandEntryArchive(Path archive) throws IOException {
        long entryCount = 0L;
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive);
             var paths = Files.walk(fileSystem.getPath("/"))) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(fileSystem.getPath("/"))) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isDirectory()) {
                    continue;
                }
                assertTrue(attributes.isRegularFile(), path.toString());
                assertEquals(0L, attributes.size(), path.toString());
                entryCount++;
            }
        }
        assertEquals(100_000L, entryCount);
    }

    /// Verifies the streaming ZIP API exposes and consumes exactly 100,000 empty regular entries.
    private static void verifyHundredThousandEntryArchiveStreaming(Path archive) throws IOException {
        long entryCount = 0L;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            while (reader.next()) {
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (attributes.isDirectory()) {
                    continue;
                }
                assertTrue(attributes.isRegularFile(), attributes.path());
                assertEquals(0L, attributes.size(), attributes.path());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(-1, input.read(), attributes.path());
                }
                entryCount++;
            }
        }
        assertEquals(100_000L, entryCount);
    }

    /// Verifies and fully consumes the single five-billion-byte zero-filled entry of a nested ZIP.
    private static void verifyLargeZipArchive(Path archive, String expectedEntryPath) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path entry = fileSystem.getPath("/" + expectedEntryPath);
            assertTrue(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS), expectedEntryPath);
            assertEquals(LARGE_ZIP_ENTRY_SIZE, Files.size(entry));
            assertEquals(LARGE_ZIP_ENTRY_SIZE, consumeZeroBytes(Files.newInputStream(entry)));

            long regularEntryCount;
            try (var paths = Files.walk(fileSystem.getPath("/"))) {
                regularEntryCount = paths
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .count();
            }
            assertEquals(1L, regularEntryCount);
        }
    }

    /// Verifies the streaming ZIP API exposes and fully consumes exactly one five-billion-byte entry.
    private static void verifyLargeZipArchiveStreaming(Path archive, String expectedEntryPath) throws IOException {
        long regularEntryCount = 0L;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archive)) {
            while (reader.next()) {
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (attributes.isDirectory()) {
                    continue;
                }
                assertTrue(attributes.isRegularFile(), attributes.path());
                assertEquals(expectedEntryPath, normalizePath(attributes.path()));
                long declaredSize = attributes.size();
                assertTrue(
                        declaredSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE || declaredSize == LARGE_ZIP_ENTRY_SIZE,
                        () -> "Unexpected streaming ZIP entry size: " + declaredSize
                );
                assertEquals(LARGE_ZIP_ENTRY_SIZE, consumeZeroBytes(reader.openInputStream()));
                regularEntryCount++;
            }
        }
        assertEquals(1L, regularEntryCount);
    }

    /// Consumes one stream, verifies every byte is zero, and returns its exact length.
    private static long consumeZeroBytes(InputStream input) throws IOException {
        try (input) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0L;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return size;
                }
                if (read == 0) {
                    throw new IOException("Expanded archive entry made no progress");
                }
                int mismatch = Arrays.mismatch(buffer, 0, read, ZERO_BYTES, 0, read);
                if (mismatch >= 0) {
                    throw new AssertionError("Expected zero byte at expanded offset " + (size + mismatch));
                }
                size = Math.addExact(size, read);
            }
        }
    }

    /// Reads all real metadata blocks through the header of the large regular TAR member.
    private static byte @Unmodifiable [] readLargeTarPrefix(Path archive) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        try (InputStream fileInput = Files.newInputStream(archive);
             InputStream gzipInput = new GzipCodec().newInputStream(fileInput)) {
            while (prefix.size() < MAXIMUM_TAR_PREFIX_SIZE) {
                byte @Unmodifiable [] header = gzipInput.readNBytes(TAR_BLOCK_SIZE);
                if (header.length != TAR_BLOCK_SIZE) {
                    throw new IOException("Large TAR fixture ended before its member header");
                }
                prefix.writeBytes(header);

                long entrySize = parseTarSize(header);
                if (isLargeTarMemberHeader(header, entrySize)) {
                    return prefix.toByteArray();
                }

                long paddedSize = roundUpToTarBlock(entrySize);
                if (paddedSize > MAXIMUM_TAR_PREFIX_SIZE - prefix.size()) {
                    throw new IOException("Large TAR member header was not found within the metadata prefix limit");
                }
                byte @Unmodifiable [] body = gzipInput.readNBytes(Math.toIntExact(paddedSize));
                if (body.length != paddedSize) {
                    throw new IOException("Large TAR fixture ended inside a metadata entry");
                }
                prefix.writeBytes(body);
            }
        }
        throw new IOException("Large TAR member header was not found");
    }

    /// Returns whether a real header introduces the large member, including a size supplied by a preceding PAX header.
    private static boolean isLargeTarMemberHeader(byte @Unmodifiable [] header, long fixedHeaderSize) {
        byte type = header[156];
        if (type != 0 && type != '0') {
            return false;
        }
        if (fixedHeaderSize == LARGE_TAR_ENTRY_SIZE) {
            return true;
        }

        byte @Unmodifiable [] expectedName = {'8', 'G', 'B'};
        for (int index = 0; index < expectedName.length; index++) {
            if (header[index] != expectedName[index]) {
                return false;
            }
        }
        return header[expectedName.length] == 0;
    }

    /// Parses one unsigned TAR size field in octal or base-256 form.
    private static long parseTarSize(byte @Unmodifiable [] header) throws IOException {
        int offset = 124;
        int end = offset + 12;
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int index = offset + 1; index < end; index++) {
                value = Math.addExact(Math.multiplyExact(value, 256L), header[index] & 0xffL);
            }
            return value;
        }

        while (offset < end && (header[offset] == 0 || header[offset] == ' ')) {
            offset++;
        }
        long value = 0L;
        while (offset < end && header[offset] != 0 && header[offset] != ' ') {
            byte digit = header[offset++];
            if (digit < '0' || digit > '7') {
                throw new IOException("Invalid octal TAR size digit");
            }
            value = Math.addExact(Math.multiplyExact(value, 8L), digit - '0');
        }
        return value;
    }

    /// Rounds one non-negative entry size up to a complete TAR block.
    private static long roundUpToTarBlock(long size) {
        return Math.multiplyExact(Math.addExact(size, TAR_BLOCK_SIZE - 1L) / TAR_BLOCK_SIZE, TAR_BLOCK_SIZE);
    }

    /// Removes one provider-specific leading slash from an archive-local path.
    private static String normalizePath(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /// Resolves one resource from the Gradle-prepared Commons Compress source tree.
    private static Path resource(String name) throws IOException {
        @Nullable String directory = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        Path root = Path.of(directory).resolve("src/test/resources").toRealPath();
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Commons Compress resource escapes its source root: " + name);
        }
        Path resource = candidate.toRealPath();
        if (!resource.startsWith(root) || !Files.isRegularFile(resource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Commons Compress resource is not a regular file: " + name);
        }
        return resource;
    }

    /// Provides a sparse logical TAR containing a real prefix, a virtual zero body, and two end blocks.
    private static final class VirtualLargeTarChannel implements SeekableByteChannel {
        /// The real TAR bytes through the large member header.
        private final byte @Unmodifiable [] prefix;

        /// The fixed logical size of this virtual archive.
        private final long size;

        /// The current logical read position.
        private long position;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates one virtual archive around the supplied real TAR prefix.
        private VirtualLargeTarChannel(byte @Unmodifiable [] prefix, long bodySize) {
            this.prefix = prefix.clone();
            this.size = Math.addExact(
                    Math.addExact(this.prefix.length, roundUpToTarBlock(bodySize)),
                    TAR_BLOCK_SIZE * 2L
            );
        }

        /// Reads real prefix bytes or virtual zero-filled bytes at the current position.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }

            int requested = (int) Math.min((long) target.remaining(), size - position);
            int remaining = requested;
            if (position < prefix.length) {
                int prefixCount = (int) Math.min((long) remaining, prefix.length - position);
                target.put(prefix, Math.toIntExact(position), prefixCount);
                position += prefixCount;
                remaining -= prefixCount;
            }
            while (remaining > 0) {
                int zeroCount = Math.min(remaining, ZERO_BYTES.length);
                target.put(ZERO_BYTES, 0, zeroCount);
                position += zeroCount;
                remaining -= zeroCount;
            }
            return requested;
        }

        /// Rejects writes because the virtual fixture is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current logical read position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the logical read position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the fixed logical archive size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Rejects truncation because the virtual fixture is read-only.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Reports whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this virtual channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this virtual channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
