// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ownership-attempt tracking without changing returned-channel ownership.
@NotNullByDefault
final class OwnedArchiveSourcesTest {
    /// Directory containing test archive content.
    @TempDir
    Path temporaryDirectory;

    /// Verifies seekable-source operations are delegated and failed closure is recorded before retry.
    @Test
    void tracksSeekableSourceCloseAttempts() throws IOException {
        Path path = temporaryDirectory.resolve("archive.bin");
        Files.write(path, new byte[]{1, 2, 3});
        TrackingSeekableSource delegate = new TrackingSeekableSource(path);
        delegate.failFirstClose = true;
        OwnedArchiveSources.OwnedSeekableSource source = OwnedArchiveSources.own(delegate);

        assertFalse(source.closeAttempted());
        try (SeekableByteChannel direct = source.openChannel();
             SeekableByteChannel volume = Objects.requireNonNull(source.openVolume(0L))) {
            assertEquals(2, delegate.openCalls());
            assertNull(source.openVolume(1L));

            IOException failure = assertThrows(IOException.class, source::close);
            assertEquals("close failure", failure.getMessage());
            assertTrue(source.closeAttempted());
            assertFalse(delegate.closed());
            assertTrue(direct.isOpen());
            assertTrue(volume.isOpen());

            source.close();
            assertTrue(delegate.closed());
            assertTrue(direct.isOpen());
            assertTrue(volume.isOpen());
            assertEquals(2, delegate.closeCalls());
        }
    }

    /// Verifies the volume-only wrapper delegates discovery and leaves returned channels caller-owned.
    @Test
    void tracksVolumeSourceWithoutOwningReturnedChannels() throws IOException {
        Path path = temporaryDirectory.resolve("volume.bin");
        Files.write(path, new byte[]{4, 5});
        TrackingSeekableSource delegate = new TrackingSeekableSource(path);
        ArkivoVolumeSource volumeDelegate = delegate;
        OwnedArchiveSources.OwnedVolumeSource source = OwnedArchiveSources.own(volumeDelegate);

        assertNull(source.openVolume(-1L));
        assertNull(source.openVolume(Long.MAX_VALUE));
        try (SeekableByteChannel channel = Objects.requireNonNull(source.openVolume(0L))) {
            assertEquals(1, delegate.openCalls());
            source.close();
            assertTrue(source.closeAttempted());
            assertTrue(delegate.closed());
            assertTrue(channel.isOpen());
        }
    }

    /// Opens independent file channels while recording source lifecycle calls.
    @NotNullByDefault
    private static final class TrackingSeekableSource implements ArkivoSeekableChannelSource {
        /// Path opened for each archive view.
        private final Path path;

        /// Number of channel-open calls.
        private int openCalls;

        /// Number of source-close calls.
        private int closeCalls;

        /// Whether the first source-close call should fail before completion.
        private boolean failFirstClose;

        /// Whether source cleanup has completed.
        private boolean closed;

        /// Creates a source for the given path.
        private TrackingSeekableSource(Path path) {
            this.path = path;
        }

        /// Opens one independent read-only channel.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new IOException("source is closed");
            }
            openCalls++;
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }

        /// Attempts source cleanup without closing previously returned channels.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (failFirstClose && closeCalls == 1) {
                throw new IOException("close failure");
            }
            closed = true;
        }

        /// Returns the number of channel-open calls.
        private int openCalls() {
            return openCalls;
        }

        /// Returns the number of source-close calls.
        private int closeCalls() {
            return closeCalls;
        }

        /// Returns whether source cleanup has completed.
        private boolean closed() {
            return closed;
        }
    }
}
