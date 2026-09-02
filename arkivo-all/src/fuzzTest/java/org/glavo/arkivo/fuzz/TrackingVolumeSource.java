// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;

/// Exposes in-memory archive volumes while recording source and channel ownership behavior.
@NotNullByDefault
final class TrackingVolumeSource implements ArkivoVolumeSource {
    /// Immutable physical volume contents in logical order.
    private final @Unmodifiable List<byte @Unmodifiable []> volumes;

    /// Every independent channel returned to the archive consumer.
    private final List<ReadOnlyByteArrayChannel> openedChannels = new ArrayList<>();

    /// Whether the owning archive consumer closed this source.
    private boolean closed;

    /// Copies physical volume contents into a new tracking source.
    ///
    /// @param volumes the physical volumes in logical order
    TrackingVolumeSource(List<byte @Unmodifiable []> volumes) {
        ArrayList<byte @Unmodifiable []> copies = new ArrayList<>(volumes.size());
        for (byte[] volume : volumes) {
            copies.add(volume.clone());
        }
        this.volumes = List.copyOf(copies);
    }

    /// Opens a new independent channel for one present physical volume.
    @Override
    public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        if (index < 0L || index >= volumes.size()) {
            return null;
        }
        ReadOnlyByteArrayChannel channel = new ReadOnlyByteArrayChannel(volumes.get(Math.toIntExact(index)));
        openedChannels.add(channel);
        return channel;
    }

    /// Marks this source closed without closing independently returned channels.
    @Override
    public void close() {
        closed = true;
    }

    /// Returns whether the owning archive consumer closed this source.
    boolean isClosed() {
        return closed;
    }

    /// Returns whether every channel returned to the consumer has been closed.
    boolean allOpenedChannelsClosed() {
        for (ReadOnlyByteArrayChannel channel : openedChannels) {
            if (channel.isOpen()) {
                return false;
            }
        }
        return true;
    }
}
