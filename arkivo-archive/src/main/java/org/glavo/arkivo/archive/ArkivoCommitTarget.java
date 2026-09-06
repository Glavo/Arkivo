// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoCommitTargetSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/// Controls where an archive editor writes the assembled archive before commit.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoCommitTarget {
    /// Returns a target that writes directly to the original archive path.
    ///
    /// Writes through the returned output modify the original immediately. Rollback does not restore its previous
    /// contents. The output requires a non-null source path.
    ///
    /// @return the direct replacement target
    static ArkivoCommitTarget replaceOriginal() {
        return ArkivoCommitTargetSupport.replaceOriginal();
    }

    /// Returns a target that writes to a temporary file and atomically replaces the original archive path on commit.
    ///
    /// The output requires a non-null source path. Commit requires the file system to support an atomic move from the
    /// temporary directory to that path; it does not fall back to a non-atomic replacement. Rollback deletes the
    /// temporary file without modifying the original archive.
    ///
    /// @param directory the directory in which to create the temporary archive
    /// @return an atomic replacement target using {@code directory}
    static ArkivoCommitTarget atomicReplace(Path directory) {
        return ArkivoCommitTargetSupport.atomicReplace(directory);
    }

    /// Returns a target that writes the assembled archive to the given path.
    ///
    /// Writes are visible at the destination before commit. Rollback leaves any written bytes in place. This target
    /// does not require a source path.
    ///
    /// @param path the destination archive path
    /// @return a target that publishes directly to {@code path}
    static ArkivoCommitTarget writeTo(Path path) {
        return ArkivoCommitTargetSupport.writeTo(path);
    }

    /// Opens output storage for a commit that will replace or derive from the given source archive path.
    ///
    /// `sourcePath` is `null` when the edited archive was opened from a non-path source. Targets that publish to a
    /// destination independent of the source, such as `writeTo(Path)`, accept a missing source path. Targets that must
    /// replace the source reject that case.
    ///
    /// @param sourcePath the source archive path, or {@code null} when the source is not path-backed
    /// @return output storage for assembling and publishing the rewritten archive
    /// @throws IOException if output storage cannot be prepared
    /// @throws IllegalArgumentException if `sourcePath` is `null` and the target requires a path-backed source
    ArkivoCommitOutput openOutput(@Nullable Path sourcePath) throws IOException;
}
