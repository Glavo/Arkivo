// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoCommitTargetSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Controls where a ZIP editor writes the assembled archive before commit.
@NotNullByDefault
public interface ZipArkivoCommitTarget {
    /// Returns a target that writes directly to the original archive path.
    static ZipArkivoCommitTarget replaceOriginal() {
        return ZipArkivoCommitTargetSupport.replaceOriginal();
    }

    /// Returns a target that writes to a temporary file and atomically replaces the original archive path on commit.
    static ZipArkivoCommitTarget atomicReplace(Path directory) {
        return ZipArkivoCommitTargetSupport.atomicReplace(directory);
    }

    /// Returns a target that writes the assembled archive to the given path.
    static ZipArkivoCommitTarget writeTo(Path path) {
        return ZipArkivoCommitTargetSupport.writeTo(path);
    }

    /// Opens output storage for a commit that will replace or derive from the given source archive path.
    ZipArkivoCommitOutput openOutput(Path sourcePath) throws IOException;
}
