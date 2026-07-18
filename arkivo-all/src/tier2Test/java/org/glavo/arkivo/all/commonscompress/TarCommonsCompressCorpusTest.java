// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR dialect, sparse-file, metadata, and corruption compatibility with Commons Compress fixtures.
@NotNullByDefault
final class TarCommonsCompressCorpusTest {
    /// The caller-owned bytes following a complete upstream archive.
    private static final byte @Unmodifiable [] ARCHIVE_TRAILER =
            "Hello, world!\n".getBytes(StandardCharsets.US_ASCII);

    /// Expansion-heavy archive containers assigned to a higher test tier.
    private static final @Unmodifiable Set<String> TIER3_ARCHIVES = Set.of(
            "8.posix.tar.gz",
            "8.star.tar.gz",
            "zip64support.tar.bz2"
    );

    /// TAR containers used to distribute another format's fixtures rather than to test TAR semantics.
    private static final @Unmodifiable Set<String> DELEGATED_CONTAINERS = Set.of("zstd-tests.tar");

    /// A fixture whose legacy names are explicitly encoded as ISO-8859-1.
    private static final @Unmodifiable Set<String> EXPLICIT_CHARSET_ARCHIVES = Set.of("COMPRESS-114.tar");

    /// Incremental backup fixtures whose repeated paths are valid in the stream but not representable by one NIO path.
    private static final @Unmodifiable Set<String> DUPLICATE_PATH_ARCHIVES = Set.of(
            "COMPRESS-612/test-times-gnu-incremental.tar",
            "COMPRESS-612/test-times-oldgnu-incremental.tar",
            "COMPRESS-612/test-times-xstar-incremental.tar",
            "COMPRESS-612/test-times-xustar-incremental.tar"
    );

    /// Fixtures with absolute entry paths rejected by Arkivo's archive path-safety contract.
    private static final @Unmodifiable Set<String> UNSAFE_PATH_ARCHIVES = Set.of(
            "archives/SunOS_cAEf.tar",
            "archives/SunOS_cEf.tar",
            "longpath/hudson-E.tar"
    );

    /// Physical entry paths and logical sizes asserted by the upstream COMPRESS-700 regression.
    private static final @Unmodifiable List<ExpectedTarEntry> COMPRESS_700_ENTRIES = List.of(
            new ExpectedTarEntry(0L, "build/app.dill"),
            new ExpectedTarEntry(105L, "CHANGELOG.md"),
            new ExpectedTarEntry(2119L, "example/android/app/build.gradle"),
            new ExpectedTarEntry(339L, "example/android/app/src/debug/AndroidManifest.xml"),
            new ExpectedTarEntry(1745L, "example/android/app/src/main/AndroidManifest.xml"),
            new ExpectedTarEntry(559L, "example/android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java"),
            new ExpectedTarEntry(353L, "example/android/app/src/main/kotlin/com/example/test_package/MainActivity.kt"),
            new ExpectedTarEntry(446L, "example/android/app/src/main/res/drawable/launch_background.xml"),
            new ExpectedTarEntry(544L, "example/android/app/src/main/res/mipmap-hdpi/ic_launcher.png"),
            new ExpectedTarEntry(442L, "example/android/app/src/main/res/mipmap-mdpi/ic_launcher.png"),
            new ExpectedTarEntry(721L, "example/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png"),
            new ExpectedTarEntry(1031L, "example/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png"),
            new ExpectedTarEntry(1443L, "example/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"),
            new ExpectedTarEntry(369L, "example/android/app/src/main/res/values/styles.xml"),
            new ExpectedTarEntry(339L, "example/android/app/src/profile/AndroidManifest.xml"),
            new ExpectedTarEntry(613L, "example/android/build.gradle"),
            new ExpectedTarEntry(32L, "example/android/gradle.properties"),
            new ExpectedTarEntry(4971L, "example/android/gradlew"),
            new ExpectedTarEntry(2404L, "example/android/gradlew.bat"),
            new ExpectedTarEntry(53636L, "example/android/gradle/wrapper/gradle-wrapper.jar"),
            new ExpectedTarEntry(240L, "example/android/gradle/wrapper/gradle-wrapper.properties"),
            new ExpectedTarEntry(150L, "example/android/local.properties"),
            new ExpectedTarEntry(499L, "example/android/settings.gradle"),
            new ExpectedTarEntry(1630L, "example/android/test_package_android.iml"),
            new ExpectedTarEntry(820L, "example/ios/Flutter/AppFrameworkInfo.plist"),
            new ExpectedTarEntry(31L, "example/ios/Flutter/Debug.xcconfig"),
            new ExpectedTarEntry(461L, "example/ios/Flutter/flutter_export_environment.sh"),
            new ExpectedTarEntry(380L, "example/ios/Flutter/Generated.xcconfig"),
            new ExpectedTarEntry(31L, "example/ios/Flutter/Release.xcconfig"),
            new ExpectedTarEntry(21606L, "example/ios/Runner.xcodeproj/project.pbxproj"),
            new ExpectedTarEntry(159L, "example/ios/Runner.xcodeproj/project.xcworkspace/contents.xcworkspacedata"),
            new ExpectedTarEntry(3382L, "example/ios/Runner.xcodeproj/xcshareddata/xcschemes/Runner.xcscheme"),
            new ExpectedTarEntry(159L, "example/ios/Runner.xcworkspace/contents.xcworkspacedata"),
            new ExpectedTarEntry(417L, "example/ios/Runner/AppDelegate.swift"),
            new ExpectedTarEntry(2641L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Contents.json"),
            new ExpectedTarEntry(10932L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-1024x1024@1x.png"),
            new ExpectedTarEntry(564L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-20x20@1x.png"),
            new ExpectedTarEntry(1283L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-20x20@2x.png"),
            new ExpectedTarEntry(1588L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-20x20@3x.png"),
            new ExpectedTarEntry(1025L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-29x29@1x.png"),
            new ExpectedTarEntry(1716L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-29x29@2x.png"),
            new ExpectedTarEntry(1920L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-29x29@3x.png"),
            new ExpectedTarEntry(1283L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-40x40@1x.png"),
            new ExpectedTarEntry(1895L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-40x40@2x.png"),
            new ExpectedTarEntry(2665L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-40x40@3x.png"),
            new ExpectedTarEntry(2665L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-60x60@2x.png"),
            new ExpectedTarEntry(3831L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-60x60@3x.png"),
            new ExpectedTarEntry(1888L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-76x76@1x.png"),
            new ExpectedTarEntry(3294L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-76x76@2x.png"),
            new ExpectedTarEntry(3612L, "example/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-83.5x83.5@2x.png"),
            new ExpectedTarEntry(414L, "example/ios/Runner/Assets.xcassets/LaunchImage.imageset/Contents.json"),
            new ExpectedTarEntry(68L, "example/ios/Runner/Assets.xcassets/LaunchImage.imageset/LaunchImage.png"),
            new ExpectedTarEntry(68L, "example/ios/Runner/Assets.xcassets/LaunchImage.imageset/LaunchImage@2x.png"),
            new ExpectedTarEntry(68L, "example/ios/Runner/Assets.xcassets/LaunchImage.imageset/LaunchImage@3x.png"),
            new ExpectedTarEntry(340L, "example/ios/Runner/Assets.xcassets/LaunchImage.imageset/README.md"),
            new ExpectedTarEntry(2414L, "example/ios/Runner/Base.lproj/LaunchScreen.storyboard"),
            new ExpectedTarEntry(1631L, "example/ios/Runner/Base.lproj/Main.storyboard"),
            new ExpectedTarEntry(310L, "example/ios/Runner/GeneratedPluginRegistrant.h"),
            new ExpectedTarEntry(204L, "example/ios/Runner/GeneratedPluginRegistrant.m"),
            new ExpectedTarEntry(1576L, "example/ios/Runner/Info.plist"),
            new ExpectedTarEntry(37L, "example/ios/Runner/Runner-Bridging-Header.h"),
            new ExpectedTarEntry(8996L, "example/lib/main.dart"),
            new ExpectedTarEntry(2759L, "example/pubspec.yaml"),
            new ExpectedTarEntry(9879L, "example/README.md"),
            new ExpectedTarEntry(1081L, "example/test/widget_test.dart"),
            new ExpectedTarEntry(913L, "example/test_package.iml"),
            new ExpectedTarEntry(1000L, "flutter_buttons.iml"),
            new ExpectedTarEntry(36861L, "lib/flutter_awesome_buttons.dart"),
            new ExpectedTarEntry(30L, "LICENSE"),
            new ExpectedTarEntry(1751L, "pubspec.yaml"),
            new ExpectedTarEntry(9879L, "README.md"),
            new ExpectedTarEntry(433L, "test/flutter_buttons_test.dart")
    );

    /// Opens every regular-size valid TAR fixture through seekable and streaming APIs.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveThroughFileSystemAndStreaming(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> seekable;
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            seekable = ArchiveCorpusAssertions.readFileSystem(fileSystem);
        }
        assertFalse(seekable.isEmpty(), "seekable TAR view must expose at least one entry");

        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> streaming;
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            streaming = ArchiveCorpusAssertions.readStreaming(reader);
        }
        ArchiveCorpusAssertions.assertEquivalentEntries(seekable, streaming);
    }

    /// Verifies every COMPRESS-700 first-entry metadata value expressible through Arkivo's TAR attributes.
    @Test
    void readsCompress700FirstEntryMetadata() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org/apache/commons/compress/COMPRESS-700/flutter_awesome_buttons-0.1.0.tar"
        );
        FileTime modifiedTime = FileTime.from(Instant.parse("2019-12-03T10:08:42Z"));
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("build/app.dill", attributes.path());
            assertEquals((byte) '0', attributes.typeFlag());
            assertEquals(33_279, attributes.mode());
            assertEquals(0L, attributes.userId());
            assertEquals(0L, attributes.groupId());
            assertNull(attributes.userName());
            assertNull(attributes.groupName());
            assertNull(attributes.linkName());
            assertEquals(0L, attributes.size());
            assertEquals(modifiedTime, attributes.lastModifiedTime());
            assertNull(attributes.recordedLastAccessTime());
            assertNull(attributes.recordedStatusChangeTime());
            assertNull(attributes.recordedCreationTime());
            assertEquals(modifiedTime, attributes.lastAccessTime());
            assertEquals(modifiedTime, attributes.creationTime());
            assertTrue(attributes.isRegularFile());
            assertFalse(attributes.isDirectory());
            assertFalse(attributes.isSymbolicLink());
            assertFalse(attributes.isOther());
            try (InputStream input = reader.openInputStream()) {
                assertEquals(0L, input.transferTo(OutputStream.nullOutputStream()));
            }
        }
    }

    /// Verifies all COMPRESS-700 entries in physical order and consumes every exact-size body.
    @Test
    void readsCompress700EntrySequenceAndSizes() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org/apache/commons/compress/COMPRESS-700/flutter_awesome_buttons-0.1.0.tar"
        );
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            int index = 0;
            for (ExpectedTarEntry expected : COMPRESS_700_ENTRIES) {
                int currentIndex = index;
                assertTrue(reader.next(), () -> "missing COMPRESS-700 entry at index " + currentIndex);
                TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                assertEquals(expected.path(), attributes.path(), () -> "entry path at index " + currentIndex);
                assertEquals(expected.size(), attributes.size(), () -> "entry size at index " + currentIndex);
                assertTrue(attributes.isRegularFile(), () -> "entry type at index " + currentIndex);
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(
                            expected.size(),
                            input.transferTo(OutputStream.nullOutputStream()),
                            () -> "decoded size at index " + currentIndex
                    );
                }
                index++;
            }
            assertFalse(reader.next(), "COMPRESS-700 archive contains unexpected trailing entries");
        }
    }

    /// Rejects each truncated or structurally invalid TAR sample while traversing observable entries.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedArchives")
    void rejectsMalformedArchive(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        assertThrows(IOException.class, () -> {
            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Verifies all GNU and POSIX sparse encodings expand to the same logical body.
    @Test
    void sparseEncodingsExposeEquivalentLogicalContent() throws IOException {
        long referenceCrc = sparseEntryCrc("oldgnu_sparse.tar", "sparsefile", 3_101_184L);
        assertEquals(referenceCrc, sparseEntryCrc("posix00_sparse.tar", "sparsefile", 3_101_184L));
        assertEquals(referenceCrc, sparseEntryCrc("posix01_sparse.tar", "sparsefile", 3_101_184L));
        assertEquals(referenceCrc, sparseEntryCrc("posix10_sparse.tar", "sparsefile", 3_101_184L));
        assertEquals(referenceCrc, sparseEntryCrc("pax_gnu_sparse.tar", "sparsefile-0.0", 3_101_184L));
        assertEquals(referenceCrc, sparseEntryCrc("pax_gnu_sparse.tar", "sparsefile-0.1", 3_101_184L));
        assertEquals(referenceCrc, sparseEntryCrc("pax_gnu_sparse.tar", "sparsefile-1.0", 3_101_184L));
        assertTrue(sparseEntryCrc("oldgnu_extended_sparse.tar", "sparse6", 51_200L) != 0L);
    }

    /// Reads the ISO-8859-1 COMPRESS-114 member names through explicit metadata charset selection.
    @Test
    void readsLegacyNamesWithExplicitCharset() throws IOException {
        TarArchiveOptions.Read options = TarArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.ISO_8859_1)
        );
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(
                CommonsCompressTestResources.resource("COMPRESS-114.tar"),
                options
        )) {
            assertTrue(Files.exists(fileSystem.getPath("/3\u00b1\u00b1\u00b1F06\u00b1W2345\u00b1ZB\u00b1la\u00b1\u00b1\u00b1\u00b1\u00b1\u00b1\u00b1\u00b1BLA")));
        }
    }

    /// Reads incremental duplicate paths in physical order and verifies the seekable NIO view rejects ambiguity.
    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicatePathArchives")
    void streamsDuplicatePathsButRejectsAmbiguousFileSystem(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archive))) {
            long duplicates = ArchiveCorpusAssertions.readStreaming(reader).stream()
                    .filter(entry -> entry.path().equals("test-times.txt"))
                    .count();
            assertTrue(duplicates > 1L, "incremental archive must retain repeated physical entries");
        }

        IOException exception = assertThrows(IOException.class, () -> TarArkivoFileSystem.open(archive).close());
        assertEquals("Duplicate TAR entry path: test-times.txt", exception.getMessage());
    }

    /// Rejects absolute archive entry paths before exposing them through either read API.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafePathArchives")
    void rejectsUnsafeAbsolutePaths(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        IOException exception = assertThrows(IOException.class, () -> TarArkivoFileSystem.open(archive).close());
        assertEquals("TAR entry path must be relative", exception.getMessage());
    }

    /// Leaves the caller-owned source positioned exactly at the first byte following the padded TAR archive.
    @Test
    void leavesCallerOwnedSourceAtArchiveTrailer() throws IOException {
        try (InputStream source = Files.newInputStream(
                CommonsCompressTestResources.resource("archive_with_trailer.tar")
        ); TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source)) {
            while (reader.next()) {
                if (reader.readAttributes().isRegularFile()) {
                    try (InputStream entry = reader.openInputStream()) {
                        entry.transferTo(OutputStream.nullOutputStream());
                    }
                }
            }
            assertArrayEquals(ARCHIVE_TRAILER, source.readNBytes(ARCHIVE_TRAILER.length));
            assertEquals(-1, source.read());
        }
    }

    /// Reads one expanded sparse entry and returns its content CRC-32.
    private static long sparseEntryCrc(String resource, String entryName, long expectedSize) throws IOException {
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            Path entry = fileSystem.getPath("/" + entryName);
            assertEquals(expectedSize, Files.size(entry));
            return ArchiveCorpusAssertions.crc32(Files.newInputStream(entry));
        }
    }

    /// Discovers valid regular-size TAR fixtures so new upstream samples cannot be silently omitted.
    private static Stream<String> readableArchives() throws IOException {
        return archiveResources()
                .filter(resource -> !resource.contains("-fail.tar"))
                .filter(resource -> !TIER3_ARCHIVES.contains(resource))
                .filter(resource -> !DELEGATED_CONTAINERS.contains(resource))
                .filter(resource -> !EXPLICIT_CHARSET_ARCHIVES.contains(resource))
                .filter(resource -> !DUPLICATE_PATH_ARCHIVES.contains(resource))
                .filter(resource -> !UNSAFE_PATH_ARCHIVES.contains(resource));
    }

    /// Discovers TAR fixtures whose names define an expected parser failure.
    private static Stream<String> malformedArchives() throws IOException {
        return archiveResources().filter(resource -> resource.contains("-fail.tar"));
    }

    /// Returns incremental archive fixtures containing repeated paths.
    private static Stream<String> duplicatePathArchives() {
        return DUPLICATE_PATH_ARCHIVES.stream().sorted();
    }

    /// Returns archives intentionally containing absolute entry paths.
    private static Stream<String> unsafePathArchives() {
        return UNSAFE_PATH_ARCHIVES.stream().sorted();
    }

    /// Returns every TAR-shaped resource relative to the official source resource root.
    private static Stream<String> archiveResources() throws IOException {
        Path root = CommonsCompressTestResources.resourceRoot();
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .filter(TarCommonsCompressCorpusTest::isTarResource)
                .sorted();
    }

    /// Returns whether a resource is an uncompressed or supported compressed TAR archive.
    private static boolean isTarResource(String resource) {
        String lower = resource.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".tar")
                || lower.endsWith(".tar.gz")
                || lower.endsWith(".tar.bz2")
                || lower.endsWith(".tgz");
    }

    /// Stores one expected physical COMPRESS-700 entry.
    ///
    /// @param size logical entry size in bytes
    /// @param path archive-local entry path
    @NotNullByDefault
    private record ExpectedTarEntry(long size, String path) {
    }
}
