// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares archive entry metadata and content across Arkivo and independent decoders.
@NotNullByDefault
public final class ArchiveCorpusAssertions {
    /// The buffer size used while hashing entry bodies.
    private static final int BUFFER_SIZE = 16 * 1024;

    /// Prevents utility-class construction.
    private ArchiveCorpusAssertions() {
    }

    /// Reads every visible file-system entry and returns a deterministic content digest.
    public static @Unmodifiable List<EntryDigest> readFileSystem(ArkivoFileSystem fileSystem) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        Path root = fileSystem.getPath("/");
        try (var paths = Files.walk(root)) {
            var iterator = paths.filter(candidate -> !candidate.equals(root)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                BodyDigest body = attributes.isRegularFile()
                        ? digest(Files.newInputStream(path))
                        : new BodyDigest(attributes.size(), 0L, "");
                entries.add(new EntryDigest(
                        normalizePath(root.relativize(path).toString().replace('\\', '/')),
                        attributes.isDirectory(),
                        attributes.isSymbolicLink(),
                        body.size(),
                        body.crc32(),
                        body.sha256()
                ));
            }
        } catch (UncheckedIOException exception) {
            // Files.walk wraps failures encountered after the root has been opened.
            throw exception.getCause();
        }
        entries.sort(Comparator.comparing(EntryDigest::path));
        return List.copyOf(entries);
    }

    /// Reads every streaming entry body and returns digests in physical archive order.
    public static @Unmodifiable List<EntryDigest> readStreaming(ArkivoStreamingReader reader) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        while (reader.next()) {
            ArchiveEntryAttributes attributes = reader.readAttributes();
            BodyDigest body = attributes.isRegularFile()
                    ? digest(reader.openInputStream())
                    : new BodyDigest(attributes.size(), 0L, "");
            entries.add(new EntryDigest(
                    normalizePath(attributes.path()),
                    attributes.isDirectory(),
                    attributes.isSymbolicLink(),
                    body.size(),
                    body.crc32(),
                    body.sha256()
            ));
        }
        return List.copyOf(entries);
    }

    /// Reads physical ZIP entries with Commons Compress, independently of Arkivo's parsers and decoders.
    public static @Unmodifiable List<EntryDigest> readZipReference(Path archive) throws IOException {
        String name = archive.getFileName().toString();
        // These upstream streaming fixtures have unusable central-directory size fields.
        if (name.equals("bla-stored-dd.zip") || name.equals("bla-stored-dd-nosig.zip")) {
            return readZipStreamingReference(archive);
        }
        int extension = name.lastIndexOf('.');
        boolean split = extension >= 0 && Files.exists(archive.resolveSibling(name.substring(0, extension) + ".z01"));
        List<EntryDigest> entries = new ArrayList<>();
        try (var channel = split ? ZipSplitReadOnlySeekableByteChannel.buildFromLastSplitSegment(archive)
                : Files.newByteChannel(archive);
             ZipFile reference = ZipFile.builder().setSeekableByteChannel(channel).setCharset("IBM437").get()) {
            var iterator = reference.getEntriesInPhysicalOrder();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                BodyDigest body = entry.isDirectory() || entry.isUnixSymlink()
                        ? new BodyDigest(entry.getSize(), 0L, "")
                        : digest(reference.getInputStream(entry));
                entries.add(new EntryDigest(normalizePath(entry.getName()), entry.isDirectory(),
                        entry.isUnixSymlink(), body.size(), body.crc32(), body.sha256()));
            }
        }
        return List.copyOf(entries);
    }

    /// Reads ZIP local records independently, including archives without a usable central directory.
    private static @Unmodifiable List<EntryDigest> readZipStreamingReference(Path archive) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (var input = new ZipArchiveInputStream(Files.newInputStream(archive), "IBM437", true, true)) {
            for (@Nullable var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                BodyDigest body = entry.isDirectory() ? new BodyDigest(0, 0, "") : digestBody(input);
                entries.add(new EntryDigest(normalizePath(entry.getName()), entry.isDirectory(), false,
                        body.size(), body.crc32(), body.sha256()));
            }
        }
        return List.copyOf(entries);
    }

    /// Reads 7z entry types and complete regular-file bodies with Commons Compress and an optional password.
    public static @Unmodifiable List<EntryDigest> readSevenZipReference(Path archive, char @Nullable [] password)
            throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (var reference = SevenZFile.builder().setPath(archive).setPassword(password)
                .setMaxMemoryLimitKiB(256 * 1024).get()) {
            for (var entry : reference.getEntries()) {
                boolean symbolicLink = entry.getHasWindowsAttributes()
                        && (entry.getWindowsAttributes() >>> 16 & 0170000) == 0120000;
                BodyDigest body = entry.isDirectory() || symbolicLink
                        ? new BodyDigest(entry.getSize(), 0L, "") : digest(reference.getInputStream(entry));
                entries.add(new EntryDigest(normalizePath(entry.getName()), entry.isDirectory(), symbolicLink,
                        body.size(), body.crc32(), body.sha256()));
            }
        }
        return List.copyOf(entries);
    }

    /// Reads all AR member bodies with the independent Commons Compress parser.
    public static @Unmodifiable List<EntryDigest> readArReference(Path archive) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (var input = new ArArchiveInputStream(Files.newInputStream(archive))) {
            for (@Nullable var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                BodyDigest body = digestBody(input);
                assertEquals(entry.getSize(), body.size(), entry.getName());
                entries.add(new EntryDigest(normalizePath(entry.getName()), false, false,
                        body.size(), body.crc32(), body.sha256()));
            }
        }
        return List.copyOf(entries);
    }

    /// Reads TAR bodies with Commons Compress, including the corpus's gzip and BZip2 wrappers.
    public static @Unmodifiable List<EntryDigest> readTarReference(Path archive) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(archive);
             InputStream decoded = archive.toString().endsWith(".bz2") ? new BZip2CompressorInputStream(raw, true)
                     : archive.toString().endsWith(".gz") || archive.toString().endsWith(".tgz")
                     ? GzipCompressorInputStream.builder().setInputStream(raw).setDecompressConcatenated(true).get() : raw;
             var input = new TarArchiveInputStream(decoded)) {
            for (@Nullable var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                BodyDigest body = entry.isFile() && !entry.isSymbolicLink()
                        ? digestBody(input) : new BodyDigest(entry.getSize(), 0L, "");
                entries.add(new EntryDigest(normalizePath(entry.getName()), entry.isDirectory(),
                        entry.isSymbolicLink(), body.size(), body.crc32(), body.sha256()));
            }
        }
        return List.copyOf(entries);
    }

    /// Verifies that two access paths expose identical physical entries while allowing synthesized NIO directories.
    public static void assertEquivalentEntries(
            @Unmodifiable List<EntryDigest> expected,
            @Unmodifiable List<EntryDigest> actual
    ) {
        @Unmodifiable List<EntryDigest> expectedContents = expected.stream()
                .filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(EntryDigest::path))
                .toList();
        @Unmodifiable List<EntryDigest> actualContents = actual.stream()
                .filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(EntryDigest::path))
                .toList();
        assertEquals(expectedContents, actualContents);

        @Unmodifiable List<EntryDigest> expectedDirectories = expected.stream()
                .filter(EntryDigest::directory)
                .toList();
        for (EntryDigest actualEntry : actual) {
            if (actualEntry.directory()) {
                assertTrue(
                        expectedDirectories.contains(actualEntry),
                        () -> "streaming directory is missing from the file-system view: " + actualEntry.path()
                );
            }
        }
    }

    /// Computes an unsigned CRC-32 while fully consuming and closing a stream.
    static long crc32(InputStream input) throws IOException {
        return digest(input).crc32();
    }

    /// Computes the byte count, CRC-32, and SHA-256 while fully consuming and closing a stream.
    private static BodyDigest digest(InputStream input) throws IOException {
        try (input) {
            return digestBody(input);
        }
    }

    /// Hashes the current entry without closing its containing archive stream.
    private static BodyDigest digestBody(InputStream input) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return new BodyDigest(size, crc32.getValue(), HexFormat.of().formatHex(sha256.digest()));
            }
            if (read == 0) {
                throw new IOException("Archive entry stream made no progress");
            }
            crc32.update(buffer, 0, read);
            sha256.update(buffer, 0, read);
            size = Math.addExact(size, read);
        }
    }

    /// Removes a provider-specific leading slash from an archive-local path.
    private static String normalizePath(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.length();
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    /// Describes observable entry metadata and content without retaining a body buffer.
    ///
    /// @param path         normalized archive-local path
    /// @param directory    whether the entry is a directory
    /// @param symbolicLink whether the entry is a symbolic link
    /// @param size         logical entry size
    /// @param crc32        unsigned CRC-32 for a regular file, or zero for another entry type
    /// @param sha256       SHA-256 of regular-file content, or an empty string for other entry types
    @NotNullByDefault
    public record EntryDigest(String path, boolean directory, boolean symbolicLink, long size, long crc32, String sha256) {
    }

    /// Describes the body observations collected while consuming one entry stream.
    ///
    /// @param size  number of body bytes consumed
    /// @param crc32 unsigned CRC-32 of the consumed bytes
    /// @param sha256 SHA-256 of the consumed bytes
    @NotNullByDefault
    private record BodyDigest(long size, long crc32, String sha256) {
    }
}
