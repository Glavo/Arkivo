// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/// Opens independent readable channels for split archive volumes.
///
/// Each successful call to `openVolume(long)` transfers ownership of the returned channel to the consumer. Repeated
/// calls for the same index must return newly opened channels with independent positions and lifecycles.
/// Closing the source releases future volume-discovery resources but does not close channels returned by earlier calls.
/// ArkivoVolumeChannel opens a finite source as one read-only logical seekable channel.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoVolumeSource extends Closeable {
    /// Returns a source backed by a finite list of volume paths.
    ///
    /// @param paths the volume paths in logical order; the list is copied
    /// @return a source that opens each listed path as an independent read-only channel
    static ArkivoVolumeSource of(List<Path> paths) {
        List<Path> copiedPaths = List.copyOf(paths);
        return index -> {
            if (index < 0 || index >= copiedPaths.size()) {
                return null;
            }
            return Files.newByteChannel(copiedPaths.get((int) index), StandardOpenOption.READ);
        };
    }

    /// Opens a new readable channel for a zero-based volume index, or returns `null` when the volume is absent.
    ///
    /// @param index the zero-based logical volume index
    /// @return a new caller-owned channel, or {@code null} if the volume is absent
    /// @throws IOException if the requested volume cannot be opened
    @Nullable SeekableByteChannel openVolume(long index) throws IOException;

    /// Closes resources owned by this source when the archive consumer no longer needs to open volume channels.
    ///
    /// This method does not close independently returned volume channels.
    ///
    /// @throws IOException if source-owned discovery resources cannot be released
    @Override
    default void close() throws IOException {
    }
}
