// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Resolves files from the prepared Apache Commons Compress source-release test resources.
@NotNullByDefault
final class CommonsCompressTestResources {
    /// The system property containing the prepared Commons Compress source-release root.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.commonsCompress.testDataDirectory";

    /// The largest nested TAR entry this helper will materialize in memory.
    private static final int MAXIMUM_TAR_ENTRY_SIZE = 16 * 1024 * 1024;

    /// Prevents instantiation.
    private CommonsCompressTestResources() {
    }

    /// Returns the canonical `src/test/resources` directory below the configured source-release root.
    static Path resourceRoot() throws IOException {
        String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }

        Path sourceRoot = Path.of(configured).toAbsolutePath().normalize().toRealPath();
        Path unresolvedResourceRoot = sourceRoot.resolve("src/test/resources").normalize();
        if (!unresolvedResourceRoot.startsWith(sourceRoot)) {
            throw new IOException("Commons Compress resource root escapes the configured source root");
        }

        Path resourceRoot = unresolvedResourceRoot.toRealPath();
        if (!resourceRoot.startsWith(sourceRoot) || !Files.isDirectory(resourceRoot)) {
            throw new IOException("Invalid Commons Compress test resource root: " + resourceRoot);
        }
        return resourceRoot;
    }

    /// Resolves a required regular resource without permitting absolute paths or traversal outside the corpus root.
    static Path resource(String first, String... more) throws IOException {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(more, "more");
        Path relative = Path.of(first, more).normalize();
        if (relative.toString().isEmpty()
                || relative.isAbsolute()
                || relative.getNameCount() == 0
                || relative.startsWith("..")) {
            throw new IllegalArgumentException("Invalid Commons Compress resource path: " + relative);
        }

        Path root = resourceRoot();
        Path unresolved = root.resolve(relative).normalize();
        if (!unresolved.startsWith(root)) {
            throw new IllegalArgumentException("Commons Compress resource path escapes the corpus: " + relative);
        }

        Path resolved = unresolved.toRealPath();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            throw new IOException("Invalid Commons Compress resource: " + relative);
        }
        return resolved;
    }

    /// Opens a required corpus resource for streaming reads.
    static InputStream open(String first, String... more) throws IOException {
        return Files.newInputStream(resource(first, more));
    }

    /// Reads all bytes from a required corpus resource.
    static byte @Unmodifiable [] read(String first, String... more) throws IOException {
        return Files.readAllBytes(resource(first, more));
    }

    /// Reads one required regular-file entry from an uncompressed TAR corpus resource.
    static byte @Unmodifiable [] readTarEntry(String archiveResource, String entryName) throws IOException {
        Objects.requireNonNull(archiveResource, "archiveResource");
        String normalizedEntryName = normalizeTarEntryName(entryName);
        try (TarArchiveInputStream input = new TarArchiveInputStream(open(archiveResource))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!normalizedEntryName.equals(entry.getName())) {
                    continue;
                }
                if (!entry.isFile()) {
                    throw new IOException("Commons Compress TAR resource is not a regular file: " + entryName);
                }
                if (entry.getSize() > MAXIMUM_TAR_ENTRY_SIZE) {
                    throw new IOException("Commons Compress TAR resource is too large: " + entryName);
                }
                byte @Unmodifiable [] content = input.readNBytes(MAXIMUM_TAR_ENTRY_SIZE + 1);
                if (content.length > MAXIMUM_TAR_ENTRY_SIZE) {
                    throw new IOException("Commons Compress TAR resource exceeds the in-memory limit: " + entryName);
                }
                if (entry.getSize() >= 0L && content.length != entry.getSize()) {
                    throw new IOException("Truncated Commons Compress TAR resource: " + entryName);
                }
                return content;
            }
        }
        throw new FileNotFoundException(
                "Missing entry " + normalizedEntryName + " in Commons Compress resource " + archiveResource
        );
    }

    /// Validates and normalizes a relative TAR entry name.
    private static String normalizeTarEntryName(String entryName) {
        Objects.requireNonNull(entryName, "entryName");
        if (entryName.isBlank() || entryName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid TAR entry name: " + entryName);
        }
        Path path = Path.of(entryName).normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) {
            throw new IllegalArgumentException("Invalid TAR entry name: " + entryName);
        }
        return path.toString().replace('\\', '/');
    }
}
