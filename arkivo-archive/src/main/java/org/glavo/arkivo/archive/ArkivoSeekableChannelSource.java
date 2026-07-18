// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.SharedSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/// Opens independent readable channels for a single random-access archive.
///
/// Each successful call to `openChannel()` transfers ownership of the returned channel to the consumer. Implementations
/// must return a newly opened channel whose position and lifecycle are independent of channels returned by earlier calls.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoSeekableChannelSource extends ArkivoVolumeSource {
    /// Returns an owning source that exposes independent logical views over one seekable channel.
    ///
    /// The channel's current position becomes logical archive offset zero, and its current remaining extent becomes the
    /// fixed archive size. The returned source serializes physical positioning and reads so its logical channels can be
    /// consumed concurrently.
    ///
    /// @param channel the channel whose ownership is transferred to the returned source
    /// @return an owning repeatable source over the channel's initial remaining extent
    /// @throws IOException if the channel position or size cannot be queried
    static ArkivoSeekableChannelSource of(SeekableByteChannel channel) throws IOException {
        return SharedSeekableChannelSource.open(channel);
    }

    /// Opens a new readable channel for the archive.
    ///
    /// @return a new caller-owned channel positioned at logical archive offset zero
    /// @throws IOException if an independent logical channel cannot be opened
    SeekableByteChannel openChannel() throws IOException;

    /// Opens the single archive as volume zero, or returns `null` for every other volume index.
    @Override
    default @Nullable SeekableByteChannel openVolume(long index) throws IOException {
        return index == 0L ? openChannel() : null;
    }
}
