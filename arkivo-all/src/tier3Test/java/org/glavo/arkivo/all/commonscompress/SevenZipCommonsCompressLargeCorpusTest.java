// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Fully expands Commons Compress 7z fixtures whose logical output is intentionally too expensive for Tier 2.
@NotNullByDefault
final class SevenZipCommonsCompressLargeCorpusTest {
    /// The fixed buffer size used to digest expanded entry bodies.
    private static final int BUFFER_SIZE = 1024 * 1024;

    /// The Gradle-provided directory containing the extracted Commons Compress source release.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.commonsCompress.testDataDirectory";

    /// Fully consumes every file body from the upstream COMPRESS-592 large-archive regression fixture.
    @Test
    void fullyReadsLargeArchive() throws IOException {
        long fileCount = 0L;
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(resource("COMPRESS-592.7z"));
             var paths = Files.walk(fileSystem.getPath("/"))) {
            var iterator = paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                        path,
                        SevenZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                ContentDigest digest;
                try (var input = Files.newInputStream(path)) {
                    CRC32 crc32 = new CRC32();
                    long size = 0L;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            throw new IOException("Expanded 7z entry made no progress");
                        }
                        crc32.update(buffer, 0, read);
                        size = Math.addExact(size, read);
                    }
                    digest = new ContentDigest(size, crc32.getValue());
                }
                assertEquals(attributes.size(), digest.size(), path.toString());
                if (attributes.crc32() != SevenZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                    assertEquals(attributes.crc32(), digest.crc32(), path.toString());
                }
                fileCount++;
            }
        }
        assertTrue(fileCount > 0L, "large 7z fixture must expose at least one regular file");
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

    /// Describes one fully consumed entry body without retaining its bytes.
    ///
    /// @param size  the number of decoded bytes
    /// @param crc32 the unsigned CRC-32 of the decoded bytes
    @NotNullByDefault
    private record ContentDigest(long size, long crc32) {
    }
}
