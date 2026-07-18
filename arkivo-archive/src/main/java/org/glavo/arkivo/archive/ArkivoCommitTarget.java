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
    /// @return the direct replacement target
    static ArkivoCommitTarget replaceOriginal() {
        return ArkivoCommitTargetSupport.replaceOriginal();
    }

    /// Returns a target that writes to a temporary file and atomically replaces the original archive path on commit.
    ///
    /// @param directory the directory in which to create the temporary archive
    /// @return an atomic replacement target using {@code directory}
    static ArkivoCommitTarget atomicReplace(Path directory) {
        return ArkivoCommitTargetSupport.atomicReplace(directory);
    }

    /// Returns a target that writes the assembled archive to the given path.
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
    ArkivoCommitOutput openOutput(@Nullable Path sourcePath) throws IOException;
}
