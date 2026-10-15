// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies conventional modern and legacy RAR split-volume path discovery.
@NotNullByDefault
final class RarSplitVolumePathsTest {
    /// Temporary directory containing synthetic volume names.
    @TempDir
    private Path temporaryDirectory;

    /// Discovers modern and legacy volumes without switching file-system providers.
    @ParameterizedTest
    @CsvSource({"sample.part001.rar,sample.part002.rar", "sample.rar,sample.part2.rar", "sample.rar,sample.r00"})
    void discoversVolumesInAnotherFileSystem(String firstName, String secondName) throws IOException {
        try (var fs = FileSystems.newFileSystem(temporaryDirectory.resolve("container.zip"), Map.of("create", "true"))) {
            Files.createDirectory(fs.getPath("/dir"));
            for (String prefix : List.of("", "/", "/dir/")) {
                Path first = Files.write(fs.getPath(prefix + firstName), new byte[0]);
                Path second = Files.write(fs.getPath(prefix + secondName), new byte[0]);
                assertEquals(List.of(first, second), RarSplitVolumePaths.discover(first));
            }
        }
    }

    /// Verifies modern `partN` discovery preserves width and stops at the first missing volume.
    @Test
    void discoversModernPartVolumesWithPreservedWidth() throws IOException {
        Path first = touch("modern.PART0001.RAR");
        Path second = touch("modern.PART0002.RAR");
        Path third = touch("modern.PART0003.RAR");
        Path fifth = touch("modern.PART0005.RAR");

        List<Path> paths = Objects.requireNonNull(RarSplitVolumePaths.discover(first));
        assertEquals(List.of(first, second, third), paths);
        assertThrows(UnsupportedOperationException.class, () -> paths.add(fifth));
    }

    /// Verifies a plain `.rar` first volume discovers modern numbered continuation files.
    @Test
    void discoversModernContinuationsFromPlainRarPath() throws IOException {
        Path first = touch("plain.RAR");
        Path second = touch("plain.part2.RAR");
        Path third = touch("plain.part3.RAR");

        assertEquals(List.of(first, second, third), RarSplitVolumePaths.discover(first));
    }

    /// Verifies a plain `.rar` first volume falls back to the legacy `rNN` sequence.
    @Test
    void discoversLegacyContinuationVolumes() throws IOException {
        Path first = touch("legacy.rar");
        Path second = touch("legacy.r00");
        Path third = touch("legacy.r01");

        assertEquals(List.of(first, second, third), RarSplitVolumePaths.discover(first));
    }

    /// Verifies modern continuation names take precedence when both naming layouts are present.
    @Test
    void prefersModernContinuationsOverLegacyNames() throws IOException {
        Path first = touch("mixed.rar");
        Path modern = touch("mixed.part2.rar");
        touch("mixed.r00");

        assertEquals(List.of(first, modern), RarSplitVolumePaths.discover(first));
    }

    /// Verifies missing storage and a first volume without a continuation do not establish a split layout.
    @Test
    void returnsNullWithoutAnEstablishedContinuation() throws IOException {
        Path missing = temporaryDirectory.resolve("missing.rar");
        assertNull(RarSplitVolumePaths.discover(missing));

        Path single = touch("single.rar");
        assertNull(RarSplitVolumePaths.discover(single));

        Path unrelated = touch("unrelated.bin");
        touch("unrelated.part2.rar");
        assertNull(RarSplitVolumePaths.discover(unrelated));
    }

    /// Verifies visually similar Unicode digits do not define a conventional `part1` path.
    @Test
    void rejectsNonAsciiModernVolumeNumbers() throws IOException {
        Path fullwidth = touch("fullwidth.part００１.rar");
        touch("fullwidth.part002.rar");
        Path arabicIndic = touch("arabic.part٠٠١.rar");
        touch("arabic.part002.rar");

        assertNull(RarSplitVolumePaths.discover(fullwidth));
        assertNull(RarSplitVolumePaths.discover(arabicIndic));
    }

    /// Creates an empty test file with the supplied file name.
    private Path touch(String fileName) throws IOException {
        return Files.write(temporaryDirectory.resolve(fileName), new byte[0]);
    }
}
