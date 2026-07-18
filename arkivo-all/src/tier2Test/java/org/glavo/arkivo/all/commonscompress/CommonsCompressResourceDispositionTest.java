// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Ensures every file in the downloaded Commons Compress corpus has one explicit, reviewable disposition.
@NotNullByDefault
final class CommonsCompressResourceDispositionTest {
    /// The immutable manifest bundled with the Tier 2 test classes.
    private static final String MANIFEST_RESOURCE =
            "/org/glavo/arkivo/all/commonscompress/commons-compress-resource-dispositions.tsv";

    /// The expected file count of the pinned Commons Compress source release.
    private static final int EXPECTED_RESOURCE_COUNT = 353;

    /// The expected aggregate byte count of the pinned Commons Compress source release.
    private static final long EXPECTED_RESOURCE_SIZE = 96_228_477L;

    /// Verifies manifest uniqueness, size metadata, dispositions, and exact corpus membership.
    @Test
    void accountsForEveryDownloadedResourceExactlyOnce() throws IOException {
        @Unmodifiable Map<String, DispositionRow> manifest = readManifest();
        @Unmodifiable Map<String, Long> actual = readActualResources();

        assertEquals(EXPECTED_RESOURCE_COUNT, manifest.size());
        assertEquals(EXPECTED_RESOURCE_COUNT, actual.size());
        assertEquals(new TreeSet<>(actual.keySet()), new TreeSet<>(manifest.keySet()));

        long totalSize = 0L;
        for (DispositionRow row : manifest.values()) {
            assertFalse(row.reason().isBlank(), row.path());
            assertEquals(actual.getOrDefault(row.path(), -1L).longValue(), row.size(), row.path());
            totalSize = Math.addExact(totalSize, row.size());
        }
        assertEquals(EXPECTED_RESOURCE_SIZE, totalSize);
    }

    /// Reads and validates the checked-in disposition manifest.
    private static @Unmodifiable Map<String, DispositionRow> readManifest() throws IOException {
        @Nullable InputStream stream =
                CommonsCompressResourceDispositionTest.class.getResourceAsStream(MANIFEST_RESOURCE);
        if (stream == null) {
            throw new AssertionError("missing resource disposition manifest");
        }

        Map<String, DispositionRow> rows = new LinkedHashMap<>();
        try (stream; BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            assertEquals("path\tsize\tdisposition\treason", reader.readLine());
            @Nullable String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String @Unmodifiable [] columns = line.split("\t", -1);
                assertEquals(4, columns.length, "manifest line " + lineNumber);
                DispositionRow row = new DispositionRow(
                        columns[0],
                        Long.parseLong(columns[1]),
                        Disposition.valueOf(columns[2]),
                        columns[3]
                );
                assertFalse(rows.containsKey(row.path()), "duplicate manifest path: " + row.path());
                rows.put(row.path(), row);
            }
        }
        return Map.copyOf(rows);
    }

    /// Enumerates current files and sizes from the Gradle-prepared upstream source release.
    private static @Unmodifiable Map<String, Long> readActualResources() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        Map<String, Long> resources = new HashMap<>();
        try (var paths = Files.walk(root)) {
            var iterator = paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String relative = root.relativize(path).toString().replace('\\', '/');
                assertFalse(resources.containsKey(relative), "duplicate physical resource path: " + relative);
                resources.put(relative, Files.size(path));
            }
        }
        return Map.copyOf(resources);
    }

    /// Enumerates the intentional ownership or exclusion contracts available to manifest rows.
    @NotNullByDefault
    private enum Disposition {
        /// Tested as an AR archive in Tier 2.
        TESTED_AR_TIER2,
        /// Tested as a TAR archive in Tier 2.
        TESTED_TAR_TIER2,
        /// Tested as a ZIP archive in Tier 2.
        TESTED_ZIP_TIER2,
        /// Consumed as a split ZIP volume in Tier 2.
        TESTED_ZIP_COMPANION_TIER2,
        /// Tested as a ZIP-based application container in Tier 2.
        TESTED_ZIP_CONTAINER_TIER2,
        /// Explicitly rejected as malformed ZIP input in Tier 2.
        TESTED_MALFORMED_ZIP_TIER2,
        /// Tested as a 7z archive in Tier 2.
        TESTED_7Z_TIER2,
        /// Tested as a 7z multi-volume companion in Tier 2.
        TESTED_7Z_COMPANION_TIER2,
        /// Limited in Tier 2 and fully expanded in Tier 3.
        TESTED_7Z_TIER2_TIER3,
        /// Tested directly by a codec in Tier 2.
        TESTED_CODEC_TIER2,
        /// Used to supply nested codec payloads in Tier 2.
        TESTED_CODEC_SUPPORT_TIER2,
        /// Fully expanded only in Tier 3.
        TESTED_EXPANSION_TIER3,
        /// Fully consumed as a bounded expansion regression in Tier 2.
        TESTED_ZIP_EXPANSION_TIER2,
        /// Deliberately misleading non-archive input rejected in Tier 2.
        EXPECTED_NON_ARCHIVE_TIER2,
        /// An archive family not implemented by Arkivo.
        UNSUPPORTED_ARCHIVE_FORMAT,
        /// A compression family not implemented by Arkivo.
        UNSUPPORTED_CODEC_FORMAT,
        /// Upstream service-loader metadata rather than test input.
        UPSTREAM_SPI_METADATA,
        /// Plain expected content or helper data owned by another fixture.
        REFERENCE_PAYLOAD
    }

    /// Stores one parsed disposition row.
    ///
    /// @param path        normalized path relative to the upstream resource root
    /// @param size        expected physical resource size in bytes
    /// @param disposition test ownership or deliberate exclusion category
    /// @param reason      concise reviewable explanation of the category
    @NotNullByDefault
    private record DispositionRow(String path, long size, Disposition disposition, String reason) {
    }
}
