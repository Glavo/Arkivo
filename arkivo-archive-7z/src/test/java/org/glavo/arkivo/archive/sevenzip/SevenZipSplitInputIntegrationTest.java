// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests explicit and conventionally named split-volume 7z inputs.
@NotNullByDefault
public final class SevenZipSplitInputIntegrationTest {
    /// The isolated directory used for conventionally named physical volumes.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies cross-volume body reads, random access, and ownership of an explicit volume source.
    @Test
    public void readsExplicitVolumeSourceAndClosesIt() throws IOException {
        byte[] content = "split volume content body".getBytes(StandardCharsets.UTF_8);
        byte[] archive = SevenZipTestArchiveFixtures.copyFileArchive(content);
        int bodyStart = 32;
        RecordingVolumeSource source = new RecordingVolumeSource(SevenZipTestArchiveFixtures.splitArchive(
                archive,
                5,
                bodyStart + 2,
                bodyStart + content.length - 1,
                archive.length - 3
        ));

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(source)) {
            Path file = fileSystem.getPath("/hello.txt");

            assertEquals(content.length, Files.size(file));
            assertArrayEquals(content, Files.readAllBytes(file));
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                assertEquals(content.length, channel.size());
                channel.position(1L);
                ByteBuffer buffer = ByteBuffer.allocate(content.length - 1);
                assertEquals(content.length - 1, channel.read(buffer));
                assertArrayEquals(Arrays.copyOfRange(content, 1, content.length), buffer.array());
            }
            assertEquals(0, source.closeCount());
        }

        assertTrue(source.openCount() > 0);
        assertTrue(source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies construction preserves a shared volume-open and source-close failure without self-suppression.
    @Test
    public void preservesSharedVolumeOpenAndSourceCloseFailure() {
        IOException sharedFailure = new IOException("shared volume failure");
        FailingVolumeSource source = new FailingVolumeSource(sharedFailure, sharedFailure);

        IOException failure = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(source));

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, source.closeCount());
    }

    /// Verifies construction suppresses a distinct source-close failure behind the volume-open failure.
    @Test
    public void suppressesDistinctSourceCloseFailureAfterVolumeOpenFailure() {
        IOException openFailure = new IOException("volume open failure");
        IOException closeFailure = new IOException("source close failure");
        FailingVolumeSource source = new FailingVolumeSource(openFailure, closeFailure);

        IOException failure = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(source));

        assertSame(openFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, source.closeCount());
    }

    /// Verifies discovery and reading of conventionally numbered split-volume paths.
    @Test
    public void discoversAndReadsSplitVolumePaths() throws IOException {
        byte[] content = "split volume path content body".getBytes(StandardCharsets.UTF_8);
        byte[][] volumes = SevenZipTestArchiveFixtures.splitArchive(
                SevenZipTestArchiveFixtures.copyFileArchive(content),
                5
        );
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = temporaryDirectory.resolve("sample.7z.002");
        Files.write(firstVolume, volumes[0]);
        Files.write(secondVolume, volumes[1]);

        List<Path> discoveredPaths = Objects.requireNonNull(
                SevenZipArkivoFormat.instance().discoverVolumePaths(firstVolume)
        );
        assertEquals(List.of(firstVolume, secondVolume), discoveredPaths);
        assertThrows(UnsupportedOperationException.class, discoveredPaths::clear);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            Path file = fileSystem.getPath("/hello.txt");

            assertArrayEquals(content, Files.readAllBytes(file));
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                assertEquals(content.length, channel.size());
                channel.position(1L);
                ByteBuffer buffer = ByteBuffer.allocate(content.length - 1);
                assertEquals(content.length - 1, channel.read(buffer));
                assertArrayEquals(Arrays.copyOfRange(content, 1, content.length), buffer.array());
            }
        }
    }

    /// Verifies that a complete archive named like a first split part ignores a stale second part.
    @Test
    public void completeFirstVolumeIgnoresStaleSecondVolume() throws IOException {
        byte[] content = "single numbered 7z content".getBytes(StandardCharsets.UTF_8);
        Path firstVolume = temporaryDirectory.resolve("sample.7z.001");
        Path secondVolume = temporaryDirectory.resolve("sample.7z.002");
        Files.write(firstVolume, SevenZipTestArchiveFixtures.copyFileArchive(content));
        Files.write(secondVolume, "stale".getBytes(StandardCharsets.UTF_8));

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hello.txt")));
        }
    }

    /// Fails volume opening and source closure with independently configurable exceptions.
    @NotNullByDefault
    private static final class FailingVolumeSource implements ArkivoVolumeSource {
        /// Failure reported while opening any volume.
        private final IOException openFailure;

        /// Failure reported while closing the source.
        private final IOException closeFailure;

        /// Number of source-close calls.
        private int closeCount;

        /// Creates a source with the supplied failures.
        private FailingVolumeSource(IOException openFailure, IOException closeFailure) {
            this.openFailure = openFailure;
            this.closeFailure = closeFailure;
        }

        /// Reports the configured volume-open failure.
        @Override
        public SeekableByteChannel openVolume(long index) throws IOException {
            throw openFailure;
        }

        /// Records closure and reports the configured source-close failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw closeFailure;
        }

        /// Returns the number of source-close calls.
        private int closeCount() {
            return closeCount;
        }
    }
}
