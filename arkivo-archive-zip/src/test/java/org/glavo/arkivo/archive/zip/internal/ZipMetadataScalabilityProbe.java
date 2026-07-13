// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Creates and verifies a many-entry ZIP fixture for bounded-heap metadata indexing.
@NotNullByDefault
public final class ZipMetadataScalabilityProbe {
    /// The number of entries indexed by the low-heap verification process.
    private static final int ENTRY_COUNT = 50_000;

    /// Prevents probe instantiation.
    private ZipMetadataScalabilityProbe() {
    }

    /// Creates or verifies the requested fixture according to the two command arguments.
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected mode and ZIP fixture path");
        }
        Path archive = Path.of(arguments[1]);
        switch (arguments[0]) {
            case "create" -> create(archive);
            case "verify" -> verify(archive);
            default -> throw new IllegalArgumentException("Unknown ZIP scalability probe mode: " + arguments[0]);
        }
    }

    /// Creates a standards-compliant ZIP containing many empty stored entries.
    private static void create(Path archive) throws IOException {
        Files.createDirectories(Objects.requireNonNull(archive.toAbsolutePath().getParent(), "archive parent"));
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(fileOutput))) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                ZipEntry entry = new ZipEntry(entryName(index));
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(0L);
                entry.setCompressedSize(0L);
                entry.setCrc(0L);
                output.putNextEntry(entry);
                output.closeEntry();
            }
        }
    }

    /// Opens and traverses the fixture while constrained by the JavaExec maximum heap.
    private static void verify(Path archive) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            long count;
            try (Stream<Path> entries = Files.list(fileSystem.getPath("/"))) {
                count = entries.count();
            }
            if (count != ENTRY_COUNT) {
                throw new AssertionError("Expected " + ENTRY_COUNT + " ZIP entries but indexed " + count);
            }
            verifyEntry(fileSystem, 0);
            verifyEntry(fileSystem, ENTRY_COUNT / 2);
            verifyEntry(fileSystem, ENTRY_COUNT - 1);
        }
    }

    /// Verifies one representative indexed entry without reading a body.
    private static void verifyEntry(ZipArkivoFileSystem fileSystem, int index) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                fileSystem.getPath("/" + entryName(index)),
                BasicFileAttributes.class
        );
        if (!attributes.isRegularFile() || attributes.size() != 0L) {
            throw new AssertionError("Unexpected metadata for ZIP entry " + index);
        }
    }

    /// Returns the stable fixture entry name for one ordinal.
    private static String entryName(int index) {
        return String.format(Locale.ROOT, "entry-%05d.bin", index);
    }
}