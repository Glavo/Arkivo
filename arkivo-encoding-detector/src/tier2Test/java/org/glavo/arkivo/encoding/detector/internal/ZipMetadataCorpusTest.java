// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies metadata from real ZIP archives against names asserted by their upstream test suites.
@NotNullByDefault
class ZipMetadataCorpusTest {
    /// Checks complete entry names through both streaming and indexed readers.
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void independentlySpecifiedNames(Fixture fixture) throws Exception {
        Path file = Path.of(Objects.requireNonNull(
                System.getProperty("arkivo." + fixture.corpus + ".testDataDirectory"))).resolve(fixture.relative);
        var options = fixture.declared ? ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(input -> {
            fail("Declared Unicode names must bypass legacy inference");
            return "";
        }) : ZipArchiveOptions.READ_DEFAULTS;
        var expected = fixture.names.stream().sorted().toList();
        var streamed = new ArrayList<String>();
        try (var reader = ZipArkivoStreamingReader.open(file, options)) {
            while (reader.next()) streamed.add(reader.readAttributes().path());
        }
        assertEquals(expected, streamed.stream().sorted().toList());
        try (var archive = ZipArkivoFileSystem.open(file, options)) {
            var root = archive.getPath("/");
            try (var paths = Files.walk(root)) {
                var indexed = paths.filter(path -> !path.equals(root))
                        .map(path -> root.relativize(path).toString() + (Files.isDirectory(path) ? "/" : ""))
                        .sorted().toList();
                assertEquals(expected, indexed);
            }
        }
    }

    /// Uses JSZip unicode/load assertions, Zip4j's GBK extraction assertion, and Go's UTF-8 ZIP fixtures.
    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("jszip", "test/ref/local_encoding_in_name.zip", List.of(
                        "Новая папка/", "Новая папка/Новый текстовый документ.txt"), false),
                new Fixture("zip4j", "src/test/resources/test-archives/testfile_with_chinese_filename_by_7zip.zip",
                        List.of("fff - 副本.txt", "fff.txt"), false),
                new Fixture("go", "src/archive/zip/testdata/utf8-7zip.zip", List.of("世界"), true),
                new Fixture("go", "src/archive/zip/testdata/utf8-infozip.zip", List.of("世界"), true),
                new Fixture("go", "src/archive/zip/testdata/utf8-osx.zip", List.of("世界"), false),
                new Fixture("go", "src/archive/zip/testdata/utf8-winrar.zip", List.of("世界"), true),
                new Fixture("go", "src/archive/zip/testdata/utf8-winzip.zip", List.of("世界"), true),
                new Fixture("jszip", "test/ref/winrar_utf8_in_name.zip", List.of("€15.txt"), true));
    }

    /// Describes an independently specified complete archive.
    /// @param corpus verified download identifier
    /// @param relative path within the upstream source archive
    /// @param names complete expected names
    /// @param declared whether all names carry authoritative Unicode declarations
    @NotNullByDefault
    record Fixture(String corpus, String relative, @Unmodifiable List<String> names, boolean declared) {
    }
}
