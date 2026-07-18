// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies path-backed volume discovery and source construction.
@NotNullByDefault
public final class ArkivoFormatPathVolumeTest {
    /// Temporary paths used by each test.
    @TempDir
    public Path temporaryDirectory;

    /// Verifies discovered paths become independent, finite volume channels.
    @Test
    public void opensDiscoveredVolumePaths() throws IOException {
        Path first = temporaryDirectory.resolve("archive.001");
        Path second = temporaryDirectory.resolve("archive.002");
        Files.write(first, new byte[]{1, 2});
        Files.write(second, new byte[]{3, 4, 5});
        TestPathVolumeFormat format = new TestPathVolumeFormat(List.of(first, second));

        try (ArkivoVolumeSource source = format.openVolumeSource(first);
             SeekableByteChannel firstChannel = Objects.requireNonNull(source.openVolume(0L));
             SeekableByteChannel secondChannel = Objects.requireNonNull(source.openVolume(1L))) {
            assertArrayEquals(new byte[]{1, 2}, readAllBytes(firstChannel));
            assertArrayEquals(new byte[]{3, 4, 5}, readAllBytes(secondChannel));
            assertNull(source.openVolume(2L));
        }
    }

    /// Verifies an undiscovered layout falls back to the requested path as one volume.
    @Test
    public void opensSinglePathWhenNoSplitLayoutIsDiscovered() throws IOException {
        Path path = temporaryDirectory.resolve("archive.bin");
        Files.write(path, new byte[]{6, 7});
        TestPathVolumeFormat format = new TestPathVolumeFormat(null);

        try (ArkivoVolumeSource source = format.openVolumeSource(path);
             SeekableByteChannel channel = Objects.requireNonNull(source.openVolume(0L))) {
            assertArrayEquals(new byte[]{6, 7}, readAllBytes(channel));
            assertNull(source.openVolume(1L));
        }
    }

    /// Verifies implementations cannot describe a split layout with fewer than two paths.
    @Test
    public void rejectsInvalidDiscoveredPathCount() {
        Path path = temporaryDirectory.resolve("archive.bin");
        TestPathVolumeFormat format = new TestPathVolumeFormat(List.of(path));

        assertThrows(IllegalStateException.class, () -> format.openVolumeSource(path));
    }

    /// Reads one test channel completely.
    private static byte[] readAllBytes(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        return buffer.array();
    }

    /// Provides configured discovery results for the shared path-volume contract.
    ///
    /// @param paths discovered paths, or `null` for a single-volume path
    @NotNullByDefault
    private record TestPathVolumeFormat(
            @Nullable @Unmodifiable List<Path> paths
    ) implements ArkivoFormat.PathVolume {
        /// Creates one test format with an immutable discovery result.
        private TestPathVolumeFormat {
            if (paths != null) {
                paths = List.copyOf(paths);
            }
        }

        /// Returns the test format name.
        @Override
        public String name() {
            return "test";
        }

        /// Returns the configured discovery result.
        @Override
        public @Nullable @Unmodifiable List<Path> discoverVolumePaths(Path path) {
            return paths;
        }
    }
}
