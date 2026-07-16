// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Describes an archive format that can expose multiple physical volumes as one file system.
@NotNullByDefault
public interface ArkivoVolumeFileSystemFormat extends ArkivoFileSystemFormat {
    /// Opens a read-only file system from an owned volume source.
    default ArkivoFileSystem open(ArkivoVolumeSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a file system from an owned volume source with options.
    ArkivoFileSystem open(
            ArkivoVolumeSource source,
            ArchiveOptions options
    ) throws IOException;

    /// Opens a complete-rewrite update from an owned volume source to a transactional volume target.
    default ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return update(source, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a complete-rewrite update with options.
    ///
    /// Formats without multi-volume update support reject the operation without opening the target or taking source
    /// ownership. Supporting formats own the source after successful setup and publish through the target on close.
    default ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        throw new UnsupportedOperationException("Multi-volume updates are not supported by " + name());
    }

    /// Creates a writable file system over a transactional volume target.
    default ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Creates a writable file system over a transactional volume target with options.
    ///
    /// Formats without multi-volume creation support reject the operation without opening the target.
    default ArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        throw new UnsupportedOperationException("Multi-volume creation is not supported by " + name());
    }
}
