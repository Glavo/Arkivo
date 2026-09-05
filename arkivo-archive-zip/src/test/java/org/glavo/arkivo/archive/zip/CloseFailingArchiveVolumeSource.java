// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/// Supplies independent archive channels while making one channel's first close attempt fail.
@NotNullByDefault
final class CloseFailingArchiveVolumeSource implements ArkivoVolumeSource {
    /// The immutable archive snapshot.
    private final byte @Unmodifiable [] archive;

    /// The one-based open count whose returned channel fails its first close attempt.
    private final int closeFailingOpen;

    /// Whether the selected close attempt fails with a runtime exception.
    private final boolean failCloseAtRuntime;

    /// The number of volume channels opened so far.
    private int openCount;

    /// Creates a source whose selected channel fails its first close attempt with an `IOException`.
    ///
    /// @param archive the archive bytes copied by this source
    /// @param closeFailingOpen the positive one-based channel open count to fail
    CloseFailingArchiveVolumeSource(byte[] archive, int closeFailingOpen) {
        this(archive, closeFailingOpen, false);
    }

    /// Creates a source whose selected channel fails its first close attempt in the requested mode.
    ///
    /// @param archive the archive bytes copied by this source
    /// @param closeFailingOpen the positive one-based channel open count to fail
    /// @param failCloseAtRuntime whether to throw an `IllegalStateException` instead of an `IOException`
    CloseFailingArchiveVolumeSource(byte[] archive, int closeFailingOpen, boolean failCloseAtRuntime) {
        if (closeFailingOpen <= 0) {
            throw new IllegalArgumentException("closeFailingOpen must be positive");
        }
        this.archive = archive.clone();
        this.closeFailingOpen = closeFailingOpen;
        this.failCloseAtRuntime = failCloseAtRuntime;
    }

    /// Opens a new channel for the only archive volume.
    @Override
    public @Nullable SeekableByteChannel openVolume(long index) {
        if (index != 0L) {
            return null;
        }
        openCount++;
        return new CloseFailingSeekableByteChannel(
                new ReadOnlyByteArrayChannel(archive),
                openCount == closeFailingOpen,
                failCloseAtRuntime
        );
    }

    /// Delegates channel operations and optionally fails the first close attempt.
    @NotNullByDefault
    private static final class CloseFailingSeekableByteChannel implements SeekableByteChannel {
        /// The underlying in-memory channel.
        private final SeekableByteChannel delegate;

        /// Whether the first close attempt fails.
        private final boolean failFirstClose;

        /// Whether the failure is a runtime exception.
        private final boolean failCloseAtRuntime;

        /// Whether the configured close failure has already occurred.
        private boolean closeFailed;

        /// Creates a close-failing wrapper around the given channel.
        private CloseFailingSeekableByteChannel(
                SeekableByteChannel delegate,
                boolean failFirstClose,
                boolean failCloseAtRuntime
        ) {
            this.delegate = delegate;
            this.failFirstClose = failFirstClose;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Reads from the delegate channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate.read(destination);
        }

        /// Writes to the delegate channel, which is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns the delegate channel position.
        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        /// Sets the delegate channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        /// Returns the delegate channel size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Truncates the delegate channel, which is read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        /// Returns whether the delegate channel is open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the configured first close attempt without closing the delegate, then closes normally.
        @Override
        public void close() throws IOException {
            if (failFirstClose && !closeFailed) {
                closeFailed = true;
                if (failCloseAtRuntime) {
                    throw new IllegalStateException("close failed");
                }
                throw new IOException("close failed");
            }
            delegate.close();
        }
    }
}
