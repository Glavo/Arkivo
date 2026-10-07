// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.glavo.arkivo.all.LibarchiveUuDecoder;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.cpio.CPIOArchiveOptions;
import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributes;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.CPIODialect;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies CPIO dialect and metadata compatibility with the Apache Commons Compress fixture corpus.
@NotNullByDefault
final class CPIOCommonsCompressCorpusTest {
    /// The buffer size used to digest entry bodies without retaining corpus payloads.
    private static final int BUFFER_SIZE = 16 * 1024;

    /// CPIO fixtures intentionally rejected by Arkivo's numeric-field validation.
    private static final @Unmodifiable Set<String> MALFORMED_ARCHIVES = Set.of(
            "org/apache/commons/compress/cpio/bad_long_value.cpio"
    );

    /// Compares every valid upstream CPIO archive with an independently parsed Commons Compress view.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveLikeCommonsCompress(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertEquals(readWithCommonsCompress(archive, StandardCharsets.UTF_8),
                readWithArkivo(archive, StandardCharsets.UTF_8, BUFFER_SIZE), resource);
    }

    /// Checks libarchive dialects, hard-link records, and legacy names with fragmented source reads.
    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({
            "test_compat_cpio_1.cpio.uu, UTF-8",
            "test_read_format_cpio_bin_be.cpio.uu, UTF-8",
            "test_read_format_cpio_bin_le.cpio.uu, UTF-8",
            "test_read_format_cpio_filename_utf8_jp.cpio.uu, UTF-8",
            "test_read_format_cpio_filename_utf8_ru.cpio.uu, UTF-8",
            "test_read_format_cpio_filename_koi8r.cpio.uu, KOI8-R",
            "test_read_format_cpio_filename_eucjp.cpio.uu, EUC-JP",
            "test_read_format_cpio_filename_cp866.cpio.uu, IBM866"
    })
    void comparesLibarchiveEntries(String fixture, String charsetName, @TempDir Path directory) throws IOException {
        Path source = Path.of(System.getProperty("arkivo.libarchive.testDataDirectory"), "fixtures", fixture);
        Path archive = Files.write(directory.resolve("reference.cpio"), LibarchiveUuDecoder.decode(source));
        Charset charset = Charset.forName(charsetName);
        @Unmodifiable List<EntryDigest> expected = readWithCommonsCompress(archive, charset);
        for (int chunk : new int[]{1, 7, 4096}) {
            assertEquals(expected, readWithArkivo(archive, charset, chunk), fixture + " chunk " + chunk);
        }
    }

    /// Rejects the upstream old-ASCII fixture containing a non-numeric fixed-width metadata value.
    @Test
    void rejectsMalformedLongValue() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org", "apache", "commons", "compress", "cpio", "bad_long_value.cpio"
        );
        assertThrows(IOException.class, () -> readWithArkivo(archive, StandardCharsets.UTF_8, BUFFER_SIZE));
    }

    /// Parses one corpus archive through Apache Commons Compress and records its observable entries.
    private static @Unmodifiable List<EntryDigest> readWithCommonsCompress(Path archive, Charset charset) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (CpioArchiveInputStream input = new CpioArchiveInputStream(
                Files.newInputStream(archive),
                CpioConstants.BLOCK_SIZE,
                charset.name()
        )) {
            @Nullable CpioArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                @Nullable String path = normalizePath(entry.getName());
                BodyDigest body = digest(input);
                if (path != null) {
                    entries.add(fromCommonsCompress(path, entry, body));
                }
            }
        }
        return List.copyOf(entries);
    }

    /// Parses one corpus archive through Arkivo's forward-only CPIO API.
    private static @Unmodifiable List<EntryDigest> readWithArkivo(Path archive, Charset charset, int chunk)
            throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        var options = CPIOArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(
                ArchiveMetadataCharsetDetector.fixed(charset));
        try (InputStream input = new FragmentedInputStream(Files.newInputStream(archive), chunk);
             CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(input, options)) {
            while (reader.next()) {
                CPIOArkivoEntryAttributes attributes = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                try (InputStream body = reader.openInputStream()) {
                    entries.add(fromArkivo(attributes, digest(body)));
                }
            }
        }
        return List.copyOf(entries);
    }

    /// Converts one Commons Compress entry into an implementation-neutral digest.
    private static EntryDigest fromCommonsCompress(
            String path,
            CpioArchiveEntry entry,
            BodyDigest body
    ) throws IOException {
        CPIODialect dialect = dialect(entry.getFormat());
        boolean newAscii = dialect == CPIODialect.NEW_ASCII || dialect == CPIODialect.NEW_ASCII_CRC;
        return new EntryDigest(
                path,
                dialect,
                entry.isRegularFile(),
                entry.isDirectory(),
                entry.isSymbolicLink(),
                entry.getSize(),
                body.size(),
                body.crc32(),
                body.sha256(),
                entry.getInode(),
                entry.getUID(),
                entry.getGID(),
                entry.getNumberOfLinks(),
                entry.getMode(),
                entry.getTime(),
                newAscii ? CPIOArkivoEntryAttributes.NOT_STORED : entry.getDevice(),
                newAscii ? CPIOArkivoEntryAttributes.NOT_STORED : entry.getRemoteDevice(),
                newAscii ? entry.getDeviceMaj() : CPIOArkivoEntryAttributes.NOT_STORED,
                newAscii ? entry.getDeviceMin() : CPIOArkivoEntryAttributes.NOT_STORED,
                newAscii ? entry.getRemoteDeviceMaj() : CPIOArkivoEntryAttributes.NOT_STORED,
                newAscii ? entry.getRemoteDeviceMin() : CPIOArkivoEntryAttributes.NOT_STORED,
                dialect == CPIODialect.NEW_ASCII_CRC
                        ? entry.getChksum() : CPIOArkivoEntryAttributes.NOT_STORED
        );
    }

    /// Converts one Arkivo entry into the same implementation-neutral digest.
    private static EntryDigest fromArkivo(CPIOArkivoEntryAttributes entry, BodyDigest body) {
        return new EntryDigest(
                entry.path(),
                entry.dialect(),
                entry.isRegularFile(),
                entry.isDirectory(),
                entry.isSymbolicLink(),
                entry.size(),
                body.size(),
                body.crc32(),
                body.sha256(),
                entry.inode(),
                entry.userId(),
                entry.groupId(),
                entry.linkCount(),
                entry.mode(),
                entry.lastModifiedTime().toMillis() / 1000L,
                entry.device(),
                entry.remoteDevice(),
                entry.deviceMajor(),
                entry.deviceMinor(),
                entry.remoteDeviceMajor(),
                entry.remoteDeviceMinor(),
                entry.checksum()
        );
    }

    /// Digests the current entry body without closing the owning archive stream.
    private static BodyDigest digest(InputStream input) throws IOException {
        CRC32 crc32 = new CRC32();
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return new BodyDigest(size, crc32.getValue(), HexFormat.of().formatHex(sha256.digest()));
            }
            if (read == 0) {
                throw new IOException("CPIO entry stream made no progress");
            }
            crc32.update(buffer, 0, read);
            sha256.update(buffer, 0, read);
            size = Math.addExact(size, read);
        }
    }

    /// Maps the Commons Compress wire-format identifier to Arkivo's dialect model.
    private static CPIODialect dialect(short format) throws IOException {
        return switch (format) {
            case CpioConstants.FORMAT_NEW -> CPIODialect.NEW_ASCII;
            case CpioConstants.FORMAT_NEW_CRC -> CPIODialect.NEW_ASCII_CRC;
            case CpioConstants.FORMAT_OLD_ASCII -> CPIODialect.OLD_ASCII;
            case CpioConstants.FORMAT_OLD_BINARY -> CPIODialect.OLD_BINARY;
            default -> throw new IOException("Unsupported Commons Compress CPIO format: " + format);
        };
    }

    /// Applies the same conventional root and relative-path normalization as the Arkivo reader.
    private static @Nullable String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.equals(".") || normalized.isEmpty() ? null : normalized;
    }

    /// Returns every valid CPIO fixture in the pinned Commons Compress source release.
    private static Stream<String> readableArchives() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".cpio"))
                .filter(path -> !MALFORMED_ARCHIVES.contains(path))
                .sorted();
    }

    /// Describes one entry's type, format metadata, and content without retaining its body.
    ///
    /// @param path normalized archive-local path
    /// @param dialect CPIO header dialect
    /// @param regularFile whether the entry is a regular file
    /// @param directory whether the entry is a directory
    /// @param symbolicLink whether the entry is a symbolic link
    /// @param declaredSize size declared by the CPIO header
    /// @param bodySize number of body bytes observed
    /// @param bodyCrc32 unsigned CRC-32 of the body
    /// @param bodySha256 SHA-256 of the complete body, including symbolic-link target bytes
    /// @param inode inode number
    /// @param userId numeric user identifier
    /// @param groupId numeric group identifier
    /// @param linkCount hard-link count
    /// @param mode POSIX mode and file-type bits
    /// @param modificationTimeSeconds modification time in epoch seconds
    /// @param device legacy device field or `NOT_STORED`
    /// @param remoteDevice legacy remote-device field or `NOT_STORED`
    /// @param deviceMajor device major number or `NOT_STORED`
    /// @param deviceMinor device minor number or `NOT_STORED`
    /// @param remoteDeviceMajor remote-device major number or `NOT_STORED`
    /// @param remoteDeviceMinor remote-device minor number or `NOT_STORED`
    /// @param checksum stored additive checksum or `NOT_STORED`
    @NotNullByDefault
    private record EntryDigest(
            String path,
            CPIODialect dialect,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink,
            long declaredSize,
            long bodySize,
            long bodyCrc32,
            String bodySha256,
            long inode,
            long userId,
            long groupId,
            long linkCount,
            long mode,
            long modificationTimeSeconds,
            long device,
            long remoteDevice,
            long deviceMajor,
            long deviceMinor,
            long remoteDeviceMajor,
            long remoteDeviceMinor,
            long checksum
    ) {
    }

    /// Describes one consumed CPIO entry body.
    ///
    /// @param size number of bytes consumed
    /// @param crc32 unsigned CRC-32 of the consumed bytes
    /// @param sha256 SHA-256 of the consumed bytes
    @NotNullByDefault
    private record BodyDigest(long size, long crc32, String sha256) {
    }

    /// Limits each source read to expose header and body boundaries independently of archive buffering.
    @NotNullByDefault
    private static final class FragmentedInputStream extends FilterInputStream {
        /// The maximum number of bytes returned by one bulk read.
        private final int chunk;

        /// Wraps a source with the specified positive read bound.
        private FragmentedInputStream(InputStream input, int chunk) {
            super(input);
            this.chunk = chunk;
        }

        /// Reads at most the configured chunk size without changing EOF behavior.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return in.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
