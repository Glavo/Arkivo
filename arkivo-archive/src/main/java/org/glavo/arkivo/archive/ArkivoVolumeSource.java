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

/// A source of readable channels for the volumes of an archive.
///
/// Each non-null result of [#openVolume(long)] is owned by the caller. Repeated calls for the same index must return
/// channels with independent positions. Closing one returned channel must not close another.
///
/// Channels may share resources owned by the source. Callers should close all returned channels before closing the
/// source; a channel backed by such a shared resource may become unusable when the source is closed.
/// [ArkivoVolumeChannel] combines a finite sequence of volumes into one read-only seekable channel.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoVolumeSource extends Closeable {
    /// Returns a source backed by a finite list of volume paths.
    ///
    /// Each call opens the requested path anew; file contents are not snapshotted. Closing this source has no effect
    /// on channels it has returned and does not prevent later opens.
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
    /// Implementations must document any effect on returned channels and whether further opens are permitted.
    ///
    /// @implSpec The default implementation does nothing.
    ///
    /// @throws IOException if source-owned discovery resources cannot be released
    @Override
    default void close() throws IOException {
    }
}
