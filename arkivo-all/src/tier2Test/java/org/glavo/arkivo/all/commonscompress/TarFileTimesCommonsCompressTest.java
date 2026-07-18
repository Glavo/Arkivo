// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies every TAR time fixture covered by the upstream Commons Compress `FileTimesIT` matrix.
@NotNullByDefault
final class TarFileTimesCommonsCompressTest {
    /// The exact upstream archive and entry expectations in physical stream order.
    private static final @Unmodifiable List<ArchiveTimes> ARCHIVES = List.of(
            archive(
                    "test-times-epax-folder.tar",
                    entry("test/", true,
                            "2022-03-17T00:24:44.147126600Z",
                            "2022-03-17T01:02:11.910960100Z",
                            "2022-03-17T00:24:44.147126600Z", null),
                    entry("test/test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null)
            ),
            archive(
                    "test-times-exustar-folder.tar",
                    entry("test/", true,
                            "2022-03-17T00:24:44.147126600Z",
                            "2022-03-17T00:47:00.367783300Z",
                            "2022-03-17T00:24:44.147126600Z", null),
                    entry("test/test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null)
            ),
            archive(
                    "test-times-gnu.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null)
            ),
            archive(
                    "test-times-gnu-incremental.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null),
                    entry("test-times.txt", false,
                            "2022-03-14T03:17:05Z", "2022-03-14T03:17:10Z",
                            "2022-03-14T03:17:10Z", null)
            ),
            archive(
                    "test-times-gnutar.tar",
                    entry("test-times.txt", false,
                            "2022-03-17T01:52:25Z", "2022-03-17T01:52:25Z",
                            "2022-03-17T01:52:25Z", null)
            ),
            archive(
                    "test-times-oldbsdtar.tar",
                    entry("test-times.txt", false, "2022-03-17T01:52:25Z", null, null, null)
            ),
            archive(
                    "test-times-oldgnu.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null)
            ),
            archive(
                    "test-times-oldgnu-incremental.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null),
                    entry("test-times.txt", false,
                            "2022-03-14T03:17:05Z", "2022-03-14T03:17:06Z",
                            "2022-03-14T03:17:05Z", null)
            ),
            archive(
                    "test-times-pax-folder.tar",
                    entry("test/", true,
                            "2022-03-17T00:24:44.147126600Z",
                            "2022-03-17T01:01:53.369146300Z",
                            "2022-03-17T00:24:44.147126600Z", null),
                    entry("test/test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null)
            ),
            archive(
                    "test-times-posix.tar",
                    entry("test-times.txt", false,
                            "2022-03-14T01:25:03.599853900Z",
                            "2022-03-14T01:31:00.706927200Z",
                            "2022-03-14T01:28:59.700505300Z", null)
            ),
            archive(
                    "test-times-bsd-folder.tar",
                    entry("test/", true,
                            "2022-03-16T10:19:43.382883700Z",
                            "2022-03-16T10:21:01.251181000Z",
                            "2022-03-16T10:19:24.105111500Z",
                            "2022-03-16T10:19:24.105111500Z"),
                    entry("test/test-times.txt", false,
                            "2022-03-16T10:21:00.249238500Z",
                            "2022-03-16T10:21:01.251181000Z",
                            "2022-03-14T01:25:03.599853900Z",
                            "2022-03-14T01:25:03.599853900Z")
            ),
            archive(
                    "test-times-posix-linux.tar",
                    entry("test-times.txt", false,
                            "2022-03-14T01:25:03.599853900Z",
                            "2022-03-14T01:32:13.837251500Z",
                            "2022-03-14T01:31:00.706927200Z", null)
            ),
            archive(
                    "test-times-star-folder.tar",
                    entry("test/", true, "2022-03-17T00:24:44Z", null, null, null),
                    entry("test/test-times.txt", false, "2022-03-17T00:38:20Z", null, null, null)
            ),
            archive(
                    "test-times-ustar.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null)
            ),
            archive(
                    "test-times-v7.tar",
                    entry("test-times.txt", false, "2022-03-14T01:25:03Z", null, null, null)
            ),
            archive(
                    "test-times-xstar.tar",
                    entry("test-times.txt", false,
                            "2022-03-14T04:11:22Z", "2022-03-14T04:12:48Z",
                            "2022-03-14T04:12:47Z", null)
            ),
            archive(
                    "test-times-xstar-folder.tar",
                    entry("test/", true,
                            "2022-03-17T00:24:44Z", "2022-03-17T01:01:34Z",
                            "2022-03-17T00:24:44Z", null),
                    entry("test/test-times.txt", false,
                            "2022-03-17T00:38:20Z", "2022-03-17T00:38:20Z",
                            "2022-03-17T00:38:20Z", null)
            ),
            archive(
                    "test-times-xstar-incremental.tar",
                    entry("test-times.txt", false,
                            "2022-03-14T04:03:29Z", "2022-03-14T04:03:29Z",
                            "2022-03-14T04:03:29Z", null),
                    entry("test-times.txt", false,
                            "2022-03-14T04:11:22Z", "2022-03-14T04:11:23Z",
                            "2022-03-14T04:11:22Z", null)
            ),
            archive(
                    "test-times-xustar.tar",
                    entry("test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null)
            ),
            archive(
                    "test-times-xustar-folder.tar",
                    entry("test/", true,
                            "2022-03-17T00:24:44.147126600Z",
                            "2022-03-17T01:01:19.581236400Z",
                            "2022-03-17T00:24:44.147126600Z", null),
                    entry("test/test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null)
            ),
            archive(
                    "test-times-xustar-incremental.tar",
                    entry("test-times.txt", false,
                            "2022-03-17T00:38:20.470751500Z",
                            "2022-03-17T00:38:20.536752000Z",
                            "2022-03-17T00:38:20.470751500Z", null),
                    entry("test-times.txt", false,
                            "2022-03-17T01:52:25.592262900Z",
                            "2022-03-17T01:52:25.724278500Z",
                            "2022-03-17T01:52:25.592262900Z", null)
            )
    );

    /// Verifies recorded TAR times and the format-independent Basic view's fallback values.
    @ParameterizedTest(name = "{0}")
    @MethodSource("archives")
    void readsExactRecordedAndBasicTimes(ArchiveTimes archive) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                CommonsCompressTestResources.open("COMPRESS-612", archive.resource())
        )) {
            for (EntryTimes expected : archive.entries()) {
                assertTrue(reader.next(), archive.resource() + "!" + expected.path());
                TarArkivoEntryAttributes actual = reader.readAttributes(TarArkivoEntryAttributes.class);
                String context = archive.resource() + "!" + expected.path();
                assertEquals(expected.path(), actual.path(), context);
                assertEquals(expected.directory(), actual.isDirectory(), context);
                assertEquals(!expected.directory(), actual.isRegularFile(), context);

                FileTime modified = fileTime(expected.modifiedTime());
                @Nullable FileTime access = fileTime(expected.accessTime());
                @Nullable FileTime statusChange = fileTime(expected.statusChangeTime());
                @Nullable FileTime creation = fileTime(expected.creationTime());
                assertEquals(modified, actual.lastModifiedTime(), context + " mtime");
                assertEquals(access, actual.recordedLastAccessTime(), context + " recorded atime");
                assertEquals(statusChange, actual.recordedStatusChangeTime(), context + " recorded ctime");
                assertEquals(creation, actual.recordedCreationTime(), context + " recorded creationtime");
                assertEquals(access == null ? modified : access, actual.lastAccessTime(), context + " Basic atime");
                assertEquals(creation == null ? modified : creation, actual.creationTime(), context + " Basic creationtime");
            }
            assertFalse(reader.next(), archive.resource());
        }
    }

    /// Returns every exact upstream archive expectation.
    private static Stream<ArchiveTimes> archives() {
        return ARCHIVES.stream();
    }

    /// Creates one immutable archive expectation from physical-order entries.
    private static ArchiveTimes archive(String resource, EntryTimes @Unmodifiable ... entries) {
        return new ArchiveTimes(resource, List.of(entries));
    }

    /// Creates one exact entry-time expectation.
    private static EntryTimes entry(
            String path,
            boolean directory,
            String modifiedTime,
            @Nullable String accessTime,
            @Nullable String statusChangeTime,
            @Nullable String creationTime
    ) {
        return new EntryTimes(
                path,
                directory,
                modifiedTime,
                accessTime,
                statusChangeTime,
                creationTime
        );
    }

    /// Parses one nullable ISO-8601 timestamp.
    private static @Nullable FileTime fileTime(@Nullable String value) {
        return value == null ? null : FileTime.from(Instant.parse(value));
    }

    /// Describes one physical TAR entry and all time values explicitly recorded for it.
    ///
    /// @param path             exact archive-local path
    /// @param directory        whether the entry is a directory rather than a regular file
    /// @param modifiedTime     exact required modification time
    /// @param accessTime       exact recorded access time, or `null` when absent
    /// @param statusChangeTime exact recorded inode status-change time, or `null` when absent
    /// @param creationTime     exact recorded creation time, or `null` when absent
    @NotNullByDefault
    private record EntryTimes(
            String path,
            boolean directory,
            String modifiedTime,
            @Nullable String accessTime,
            @Nullable String statusChangeTime,
            @Nullable String creationTime
    ) {
    }

    /// Describes the expected physical entries in one upstream TAR fixture.
    ///
    /// @param resource resource name relative to the upstream `COMPRESS-612` directory
    /// @param entries  immutable physical-order entry expectations
    @NotNullByDefault
    private record ArchiveTimes(String resource, @Unmodifiable List<EntryTimes> entries) {
        /// Returns the resource name used by parameterized-test display names.
        @Override
        public String toString() {
            return resource;
        }
    }
}
