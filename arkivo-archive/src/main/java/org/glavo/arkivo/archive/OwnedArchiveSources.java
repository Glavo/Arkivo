// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Creates archive source wrappers that expose whether a close was already attempted.
@NotNullByDefault
final class OwnedArchiveSources {
    /// Creates no instances.
    private OwnedArchiveSources() {
    }

    /// Wraps one repeatable seekable source for ownership tracking.
    static OwnedSeekableSource own(ArkivoSeekableChannelSource source) {
        return new OwnedSeekableSource(source);
    }

    /// Wraps one volume source for ownership tracking.
    static OwnedVolumeSource own(ArkivoVolumeSource source) {
        return new OwnedVolumeSource(source);
    }

    /// Tracks ownership operations over one volume source.
    @NotNullByDefault
    static class OwnedVolumeSource implements ArkivoVolumeSource {
        /// Wrapped source.
        private final ArkivoVolumeSource delegate;

        /// Whether closing has been attempted at least once.
        private boolean closeAttempted;

        /// Creates an ownership-tracking source.
        private OwnedVolumeSource(ArkivoVolumeSource delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Opens one volume through the wrapped source.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            return delegate.openVolume(index);
        }

        /// Attempts to close the wrapped source.
        @Override
        public void close() throws IOException {
            closeAttempted = true;
            delegate.close();
        }

        /// Returns whether a consumer has already attempted to close this source.
        final boolean closeAttempted() {
            return closeAttempted;
        }
    }

    /// Tracks ownership operations over one repeatable seekable source.
    @NotNullByDefault
    static final class OwnedSeekableSource extends OwnedVolumeSource
            implements ArkivoSeekableChannelSource {
        /// Wrapped seekable source.
        private final ArkivoSeekableChannelSource delegate;

        /// Creates an ownership-tracking seekable source.
        private OwnedSeekableSource(ArkivoSeekableChannelSource delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        /// Opens one independent archive channel through the wrapped source.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            return delegate.openChannel();
        }
    }
}
