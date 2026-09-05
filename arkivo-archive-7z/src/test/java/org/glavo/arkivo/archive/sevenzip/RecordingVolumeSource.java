// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Objects;

/// Provides repeatable in-memory volumes while recording source and channel lifecycles.
@NotNullByDefault
final class RecordingVolumeSource implements ArkivoVolumeSource {
    /// The immutable snapshots exposed as archive volumes.
    private final byte @Unmodifiable [] @Unmodifiable [] volumes;

    /// The channels opened from this source.
    private final ArrayList<SeekableByteChannel> openedChannels = new ArrayList<>();

    /// The number of times this source has been closed.
    private int closeCount;

    /// Creates a source over defensive copies of the given volumes.
    ///
    /// @param volumes the ordered physical volume contents
    RecordingVolumeSource(byte[][] volumes) {
        Objects.requireNonNull(volumes, "volumes");
        this.volumes = new byte[volumes.length][];
        for (int index = 0; index < volumes.length; index++) {
            this.volumes[index] = Objects.requireNonNull(volumes[index], "volume").clone();
        }
    }

    /// Opens an independent channel for the requested volume.
    @Override
    public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
        if (closeCount != 0) {
            throw new IOException("volume source is closed");
        }
        if (index < 0L || index >= volumes.length) {
            return null;
        }
        SeekableByteChannel channel = new ReadOnlyByteArrayChannel(volumes[(int) index]);
        openedChannels.add(channel);
        return channel;
    }

    /// Records closure of this source.
    @Override
    public void close() {
        closeCount++;
    }

    /// Returns the number of channels opened from this source.
    int openCount() {
        return openedChannels.size();
    }

    /// Returns whether every channel opened from this source has been closed.
    boolean allOpenedChannelsClosed() {
        for (SeekableByteChannel channel : openedChannels) {
            if (channel.isOpen()) {
                return false;
            }
        }
        return true;
    }

    /// Returns the number of times this source has been closed.
    int closeCount() {
        return closeCount;
    }
}
