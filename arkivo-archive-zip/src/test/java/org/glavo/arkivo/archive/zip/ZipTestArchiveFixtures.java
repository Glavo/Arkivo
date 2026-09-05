// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Provides ZIP archive fixtures, temporary paths, and in-memory channels shared by focused tests.
@NotNullByDefault
final class ZipTestArchiveFixtures {
    /// Creates no instances.
    private ZipTestArchiveFixtures() {
    }

    /// Writes a deflated archive containing `dir/hello.txt` and returns its path.
    static Path writeDeflatedArchive(Path archivePath) throws IOException {
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            var directoryEntry = writer.beginDirectory("dir");
            directoryEntry.close();
            var helloEntry = writer.beginFile("dir/hello.txt");
            try (var output = helloEntry.openOutputStream()) {
                output.write("hello".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archivePath;
    }

    /// Writes one stored JDK ZIP entry with optional extra data and comment metadata.
    static void writeStoredZipEntry(
            ZipOutputStream output,
            String name,
            byte[] content,
            byte @Nullable [] extraData,
            @Nullable String comment
    ) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc32.getValue());
        if (extraData != null) {
            entry.setExtra(extraData);
        }
        if (comment != null) {
            entry.setComment(comment);
        }
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    /// Returns a complete single-entry stored ZIP archive.
    static byte[] singleStoredZipArchive(String name, byte[] content) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            writeStoredZipEntry(output, name, content, null, null);
        }
        return archive.toByteArray();
    }

    /// Returns a ZIP update fixture with a preamble and three regular entries.
    static byte[] updateSourceZip(byte[] preamble) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(Objects.requireNonNull(preamble, "preamble"));
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String name : List.of("keep.txt", "replace.txt", "remove.txt")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.substring(0, name.indexOf('.')).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /// Returns a minimal empty ZIP archive with the given preamble bytes.
    static byte[] emptyZipWithPreamble(byte[] preamble) {
        ByteBuffer buffer = ByteBuffer.allocate(preamble.length + 22).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(preamble);
        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive whose offsets include the preamble size.
    static byte[] singleEntryZipWithPreambleAndAdjustedOffsets(byte[] preamble) {
        byte[] name = new byte[]{'a'};
        int localHeaderOffset = preamble.length;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderOffset + localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                preamble.length + localHeaderSize + centralDirectorySize + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(preamble);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a two-volume ZIP archive containing one stored file named `hello.txt`.
    static byte[][] splitStoredArchive() {
        byte[] name = "hello.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "split".getBytes(StandardCharsets.UTF_8);
        CRC32 crc32 = new CRC32();
        crc32.update(content);

        int localHeaderSize = 30 + name.length;
        ByteBuffer firstVolume = ByteBuffer.allocate(localHeaderSize + content.length).order(ByteOrder.LITTLE_ENDIAN);
        firstVolume.putInt(0x04034b50);
        firstVolume.putShort((short) 20);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putInt((int) crc32.getValue());
        firstVolume.putInt(content.length);
        firstVolume.putInt(content.length);
        firstVolume.putShort((short) name.length);
        firstVolume.putShort((short) 0);
        firstVolume.put(name);
        firstVolume.put(content);

        int centralDirectorySize = 46 + name.length;
        ByteBuffer secondVolume = ByteBuffer.allocate(centralDirectorySize + 22).order(ByteOrder.LITTLE_ENDIAN);
        secondVolume.putInt(0x02014b50);
        secondVolume.putShort((short) 20);
        secondVolume.putShort((short) 20);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putInt((int) crc32.getValue());
        secondVolume.putInt(content.length);
        secondVolume.putInt(content.length);
        secondVolume.putShort((short) name.length);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putInt(0);
        secondVolume.putInt(0);
        secondVolume.put(name);

        secondVolume.putInt(0x06054b50);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putInt(centralDirectorySize);
        secondVolume.putInt(0);
        secondVolume.putShort((short) 0);

        return new byte[][]{firstVolume.array(), secondVolume.array()};
    }

    /// Creates a temporary archive path under the module build directory.
    static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-zip-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sfx.zip");
    }

    /// Returns the split volume paths that make up an archive written to the given final path.
    static List<Path> splitVolumePaths(Path archivePath) {
        ArrayList<Path> volumes = new ArrayList<>();
        for (int diskNumber = 0; ; diskNumber++) {
            Path volumePath = splitVolumePath(archivePath, diskNumber);
            if (!Files.exists(volumePath)) {
                break;
            }
            volumes.add(volumePath);
        }
        volumes.add(archivePath);
        return List.copyOf(volumes);
    }

    /// Returns the path for a numbered split volume.
    static Path splitVolumePath(Path archivePath, int diskNumber) {
        String volumeNumber = Integer.toString(diskNumber + 1);
        if (volumeNumber.length() == 1) {
            volumeNumber = "0" + volumeNumber;
        }
        String fileName = archivePath.getFileName().toString();
        String baseName = fileName.length() >= 4
                && fileName.regionMatches(true, fileName.length() - 4, ".zip", 0, 4)
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        Path parent = archivePath.getParent();
        Path volumeFileName = Path.of(baseName + ".z" + volumeNumber);
        return parent != null ? parent.resolve(volumeFileName) : volumeFileName;
    }

    /// Deletes a temporary archive, all numbered split volumes, and its containing directory.
    static void deleteTemporaryArchive(Path archivePath) throws IOException {
        for (int diskNumber = 0; ; diskNumber++) {
            if (!Files.deleteIfExists(splitVolumePath(archivePath, diskNumber))) {
                break;
            }
        }
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Returns whether `expected` occurs contiguously within `bytes`.
    static boolean containsBytes(byte @Unmodifiable [] bytes, byte @Unmodifiable [] expected) {
        if (expected.length == 0) {
            return true;
        }
        int maximumStart = bytes.length - expected.length;
        for (int start = 0; start <= maximumStart; start++) {
            int index = 0;
            while (index < expected.length && bytes[start + index] == expected[index]) {
                index++;
            }
            if (index == expected.length) {
                return true;
            }
        }
        return false;
    }

    /// Returns a copy of a ZIP archive with the first signed data-descriptor CRC-32 modified.
    static byte[] tamperFirstDataDescriptorCrc(byte @Unmodifiable [] archive) {
        byte[] tampered = archive.clone();
        ByteBuffer buffer = ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset <= tampered.length - 16; offset++) {
            if (buffer.getInt(offset) == 0x08074b50) {
                tampered[offset + 4] ^= 1;
                return tampered;
            }
        }
        throw new AssertionError("data descriptor signature not found");
    }
}
