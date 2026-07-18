// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Maps logical archive volumes to published paths and enumerates output owned by one archive layout.
///
/// The output directory should share a file system with every returned volume path so staged files and backups can be
/// moved during publication. Existing-path discovery must include stale volumes that should be removed when a shorter
/// replacement is committed, while excluding unrelated files.
@NotNullByDefault
public interface ArkivoVolumePathLayout {
    /// Returns the directory in which temporary publication state should be created.
    ///
    /// @return the staging directory, normally on the same file system as every published volume
    Path outputDirectory();

    /// Returns the final path for one zero-based volume index and the zero-based final volume index.
    ///
    /// @param index the zero-based volume index to map
    /// @param finalVolumeIndex the zero-based index of the last archive volume
    /// @return the final publication path for {@code index}
    Path volumePath(long index, long finalVolumeIndex);

    /// Returns every currently published path owned by this layout, including stale volumes from older output.
    ///
    /// The returned list must not change and should contain only paths that the transaction may replace or remove.
    ///
    /// @return immutable paths currently owned by this archive layout
    /// @throws IOException if existing publication state cannot be enumerated
    @Unmodifiable List<Path> existingVolumePaths() throws IOException;
}
