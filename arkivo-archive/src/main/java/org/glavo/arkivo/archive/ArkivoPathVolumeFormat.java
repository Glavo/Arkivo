// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes an archive format that can discover conventional multi-volume storage from a path.
///
/// A format defines which path identifies the archive layout. For example, ZIP conventionally uses the final `.zip`
/// path, while numbered 7z and modern RAR layouts use their first physical volume.
@NotNullByDefault
public interface ArkivoPathVolumeFormat extends ArkivoFormat {
    /// Discovers the ordered physical paths of a conventional multi-volume archive.
    ///
    /// The returned list starts with logical volume zero, is immutable, and contains at least two paths. This method
    /// returns `null` when the path does not identify a recognized multi-volume layout.
    @Nullable @Unmodifiable List<Path> discoverVolumePaths(Path path) throws IOException;

    /// Opens a path-backed volume source, using one physical volume when no split layout is discovered.
    default ArkivoVolumeSource openVolumeSource(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        @Nullable @Unmodifiable List<Path> volumePaths = discoverVolumePaths(path);
        if (volumePaths == null) {
            return ArkivoVolumeSource.of(List.of(path));
        }
        if (volumePaths.size() < 2) {
            throw new IllegalStateException("Discovered multi-volume paths must contain at least two paths");
        }
        return ArkivoVolumeSource.of(volumePaths);
    }
}
