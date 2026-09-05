// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoReadOnlyFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests ZIP file-system ownership and cleanup of archive volume sources.
@NotNullByDefault
public final class ZipVolumeSourceLifecycleTest {
    /// Verifies that split-volume setup preserves an I/O close failure as a suppressed exception.
    @Test
    public void splitSetupSuppressesChannelCloseFailure() throws IOException {
        SizeFailingVolumeSource volumes = new SizeFailingVolumeSource(false);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
            IOException exception = assertThrows(IOException.class, fileSystem::preambleSize);

            assertEquals("size failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
            assertEquals(1, volumes.firstCloseCount());
            assertEquals(1, volumes.secondCloseCount());
        }
    }

    /// Verifies that split-volume setup preserves a runtime close failure as a suppressed exception.
    @Test
    public void splitSetupSuppressesChannelRuntimeCloseFailure() throws IOException {
        SizeFailingVolumeSource volumes = new SizeFailingVolumeSource(true);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
            IOException exception = assertThrows(IOException.class, fileSystem::preambleSize);

            assertEquals("size failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
            assertEquals(1, volumes.firstCloseCount());
            assertEquals(1, volumes.secondCloseCount());
        }
    }

    /// Verifies that closing a ZIP file system closes its owned volume source.
    @Test
    public void closeClosesOwnedVolumeSource() throws IOException {
        CloseTrackingVolumeSource volumes = new CloseTrackingVolumeSource();
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes);

        fileSystem.close();
        fileSystem.close();

        assertFalse(fileSystem.isOpen());
        assertTrue(volumes.isClosed());
        assertEquals(1, volumes.closeCount());
        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/"));
        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
        assertThrows(ClosedFileSystemException.class, fileSystem::supportedFileAttributeViews);
    }

    /// Verifies that a close-action failure does not mask an owned volume-source close failure.
    @Test
    public void closeActionFailureIsSuppressedByVolumeSourceFailure() throws IOException {
        CloseFailingVolumeSource volumes = new CloseFailingVolumeSource(Integer.MAX_VALUE);
        ZipArkivoReadOnlyFileSystemImpl fileSystem = new ZipArkivoReadOnlyFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    throw new IllegalStateException("close action failed");
                }
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume source close failed", exception.getMessage());
        assertEquals(1, volumes.closeCount());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies one shared volume-source and close-action failure remains primary without self-suppression.
    @Test
    public void preservesSharedVolumeSourceAndCloseActionFailure() throws IOException {
        IllegalStateException sharedFailure = new IllegalStateException("shared close failure");
        SharedRuntimeFailureVolumeSource volumes = new SharedRuntimeFailureVolumeSource(sharedFailure);
        ZipArkivoReadOnlyFileSystemImpl fileSystem = new ZipArkivoReadOnlyFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    throw sharedFailure;
                }
        );

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertSame(sharedFailure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, volumes.closeCount());
    }

    /// Verifies that closing a ZIP file system retries incomplete volume-source cleanup.
    @Test
    public void closeRetriesVolumeSourceCleanupAfterFailure() throws IOException {
        CloseFailingVolumeSource volumes = new CloseFailingVolumeSource(1);
        int[] closeActionCount = new int[1];
        ZipArkivoReadOnlyFileSystemImpl fileSystem = new ZipArkivoReadOnlyFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> closeActionCount[0]++
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);
        assertEquals("volume source close failed", exception.getMessage());
        assertFalse(fileSystem.isOpen());
        assertEquals(1, volumes.closeCount());
        assertEquals(1, closeActionCount[0]);

        fileSystem.close();
        fileSystem.close();

        assertEquals(2, volumes.closeCount());
        assertEquals(1, closeActionCount[0]);
    }

    /// Supplies two channels whose second size query fails during split-volume setup.
    @NotNullByDefault
    private static final class SizeFailingVolumeSource implements ArkivoVolumeSource {
        /// The first opened volume channel.
        private final CloseTrackingSeekableByteChannel first;

        /// The second opened volume channel.
        private final CloseTrackingSeekableByteChannel second =
                new CloseTrackingSeekableByteChannel(1L, true, false, false);

        /// Creates a source with the requested first-volume close failure mode.
        private SizeFailingVolumeSource(boolean failFirstCloseAtRuntime) {
            first = new CloseTrackingSeekableByteChannel(1L, false, true, failFirstCloseAtRuntime);
        }

        /// Opens the requested test volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index == 0L) {
                return first;
            }
            if (index == 1L) {
                return second;
            }
            return null;
        }

        /// Returns how many times the first channel was closed.
        private int firstCloseCount() {
            return first.closeCount();
        }

        /// Returns how many times the second channel was closed.
        private int secondCloseCount() {
            return second.closeCount();
        }
    }

    /// Records channel closure and injects selected setup failures.
    @NotNullByDefault
    private static final class CloseTrackingSeekableByteChannel implements SeekableByteChannel {
        /// The reported channel size.
        private final long size;

        /// Whether size lookup fails.
        private final boolean failSize;

        /// Whether close fails.
        private final boolean failClose;

        /// Whether close fails with a runtime exception.
        private final boolean failCloseAtRuntime;

        /// Whether this channel is open.
        private boolean open = true;

        /// The number of close calls.
        private int closeCount;

        /// Creates a channel with the requested failure behavior.
        private CloseTrackingSeekableByteChannel(
                long size,
                boolean failSize,
                boolean failClose,
                boolean failCloseAtRuntime
        ) {
            this.size = size;
            this.failSize = failSize;
            this.failClose = failClose;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Reads from the empty channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            return -1;
        }

        /// Rejects writes because this channel is read-only.
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return 0L;
        }

        /// Accepts any non-negative test position without changing the empty channel.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            return this;
        }

        /// Returns the configured size or injects the configured failure.
        @Override
        public long size() throws IOException {
            ensureOpen();
            if (failSize) {
                throw new IOException("size failed");
            }
            return size;
        }

        /// Rejects truncation because this channel is read-only.
        @Override
        public SeekableByteChannel truncate(long newSize) {
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records closure and injects the configured close failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            open = false;
            if (failClose) {
                if (failCloseAtRuntime) {
                    throw new IllegalStateException("close failed");
                }
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Records closure of an otherwise empty volume source.
    @NotNullByDefault
    private static final class CloseTrackingVolumeSource implements ArkivoVolumeSource {
        /// The number of close calls.
        private int closeCount;

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records source closure.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns whether this source has been closed.
        private boolean isClosed() {
            return closeCount != 0;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails a configured number of close attempts on an otherwise empty volume source.
    @NotNullByDefault
    private static final class CloseFailingVolumeSource implements ArkivoVolumeSource {
        /// The number of close calls that fail.
        private final int failureCount;

        /// The number of close calls.
        private int closeCount;

        /// Creates a source that fails the given number of close calls.
        private CloseFailingVolumeSource(int failureCount) {
            if (failureCount < 0) {
                throw new IllegalArgumentException("failureCount must not be negative");
            }
            this.failureCount = failureCount;
        }

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records closure and fails while configured failures remain.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount <= failureCount) {
                throw new IOException("volume source close failed");
            }
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Reports one shared runtime failure whenever its otherwise empty source is closed.
    @NotNullByDefault
    private static final class SharedRuntimeFailureVolumeSource implements ArkivoVolumeSource {
        /// Failure reported by every close attempt.
        private final RuntimeException failure;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a source reporting the supplied failure.
        private SharedRuntimeFailureVolumeSource(RuntimeException failure) {
            this.failure = failure;
        }

        /// Opens no physical volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records closure and reports the configured failure.
        @Override
        public void close() {
            closeCount++;
            throw failure;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
