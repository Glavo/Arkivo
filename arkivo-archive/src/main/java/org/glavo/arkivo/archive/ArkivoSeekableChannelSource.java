// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

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
    /// Opens a new readable channel for the archive.
    SeekableByteChannel openChannel() throws IOException;

    /// Opens the single archive as volume zero, or returns `null` for every other volume index.
    @Override
    default @Nullable SeekableByteChannel openVolume(long index) throws IOException {
        return index == 0L ? openChannel() : null;
    }
}
