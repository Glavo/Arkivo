// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/// Opens readable channels for split archive volumes.
@NotNullByDefault
public interface ArkivoVolumeSource extends Closeable {
    /// Returns a source backed by a finite list of volume paths.
    static ArkivoVolumeSource of(List<Path> paths) {
        List<Path> copiedPaths = List.copyOf(paths);
        return index -> {
            if (index < 0 || index >= copiedPaths.size()) {
                return null;
            }
            return Files.newByteChannel(copiedPaths.get((int) index), StandardOpenOption.READ);
        };
    }

    /// Opens the readable channel for a zero-based volume index, or returns `null` when the volume is absent.
    @Nullable SeekableByteChannel openVolume(long index) throws IOException;

    /// Closes resources owned by this source.
    @Override
    default void close() throws IOException {
    }
}
