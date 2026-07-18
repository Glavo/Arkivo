// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributes;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.CPIODialect;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies CPIO dialect and metadata compatibility with the Apache Commons Compress fixture corpus.
@NotNullByDefault
final class CPIOCommonsCompressCorpusTest {
    /// The buffer size used to digest entry bodies without retaining corpus payloads.
    private static final int BUFFER_SIZE = 16 * 1024;

    /// Compares every valid upstream CPIO archive with an independently parsed Commons Compress view.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveLikeCommonsCompress(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertEquals(readWithCommonsCompress(archive), readWithArkivo(archive), resource);
    }

    /// Rejects the upstream old-ASCII fixture containing a non-numeric fixed-width metadata value.
    @Test
    void rejectsMalformedLongValue() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org", "apache", "commons", "compress", "cpio", "bad_long_value.cpio"
        );
        assertThrows(IOException.class, () -> readWithArkivo(archive));
    }

    /// Parses one corpus archive through Apache Commons Compress and records its observable entries.
    private static @Unmodifiable List<EntryDigest> readWithCommonsCompress(Path archive) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (CpioArchiveInputStream input = new CpioArchiveInputStream(
                Files.newInputStream(archive),
                CpioConstants.BLOCK_SIZE,
                StandardCharsets.UTF_8.name()
        )) {
            CpioArchiveEntry entry;
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
    private static @Unmodifiable List<EntryDigest> readWithArkivo(Path archive) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(Files.newInputStream(archive))) {
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
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return new BodyDigest(size, crc32.getValue());
            }
            if (read == 0) {
                throw new IOException("CPIO entry stream made no progress");
            }
            crc32.update(buffer, 0, read);
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
    private static Stream<String> readableArchives() {
        return Stream.of(
                "archives/FreeBSD_bin.cpio",
                "archives/FreeBSD_crc.cpio",
                "archives/FreeBSD_hpbin.cpio",
                "archives/FreeBSD_newc.cpio",
                "archives/SunOS_-c.cpio",
                "archives/SunOS_.cpio",
                "archives/SunOS_crc.cpio",
                "archives/SunOS_odc.cpio",
                "bla.cpio",
                "COMPRESS-459.cpio",
                "longpath/minotaur.cpio",
                "redline.cpio"
        );
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
    @NotNullByDefault
    private record BodyDigest(long size, long crc32) {
    }
}
