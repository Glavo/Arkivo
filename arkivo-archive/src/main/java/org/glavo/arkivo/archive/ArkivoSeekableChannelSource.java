// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.SharedSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/// A source of readable, independently positioned channels for one archive.
///
/// Each call to [#openChannel()] returns a caller-owned channel. Closing that channel must not close other channels
/// returned by this source. As specified by [ArkivoVolumeSource], the source itself may own backing resources needed
/// by all of its channels.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoSeekableChannelSource extends ArkivoVolumeSource {
    /// Returns an owning source that exposes independent logical views over one seekable channel.
    ///
    /// The channel's current position becomes logical archive offset zero, and its current remaining extent becomes the
    /// fixed archive size; the bytes themselves are not copied. The returned source serializes physical positioning
    /// and reads so its logical channels can be consumed concurrently. Callers must not access the supplied channel
    /// directly after transferring ownership.
    ///
    /// Closing a logical channel leaves the source and other logical channels open. Closing the source closes the
    /// supplied channel and invalidates all of its logical channels. A failed close can be retried.
    /// If initialization fails, this method attempts to close the supplied channel and suppresses any cleanup failure
    /// on the original failure.
    ///
    /// @param channel the channel whose ownership is transferred to the returned source
    /// @return an owning repeatable source over the channel's initial remaining extent
    /// @throws IOException if the channel position or size cannot be queried, or the position exceeds the size
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
