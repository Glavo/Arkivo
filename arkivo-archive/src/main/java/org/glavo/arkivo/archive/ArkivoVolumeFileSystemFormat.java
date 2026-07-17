// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Describes an archive format that can expose multiple physical volumes as one file system.
@NotNullByDefault
public interface ArkivoVolumeFileSystemFormat extends ArkivoFileSystemFormat {
    /// Opens a read-only file system from an owned volume source.
    default ArkivoFileSystem open(ArkivoVolumeSource source) throws IOException {
        return open(source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a file system from an owned volume source with options.
    ArkivoFileSystem open(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException;

    /// Describes a format that supports multi-volume creation and complete-rewrite updates.
    @NotNullByDefault
    interface Writable extends ArkivoVolumeFileSystemFormat {
        /// Opens a complete-rewrite update from an owned volume source to a transactional volume target.
        default ArkivoFileSystem update(
                ArkivoVolumeSource source,
                ArkivoVolumeTarget target,
                long splitSize
        ) throws IOException {
            return update(source, target, splitSize, ArchiveUpdateOptions.DEFAULT);
        }

        /// Opens a complete-rewrite update with options.
        ///
        /// The format owns the source after successful setup and publishes through the target on close.
        ArkivoFileSystem update(
                ArkivoVolumeSource source,
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveUpdateOptions options
        ) throws IOException;

        /// Creates a writable file system over a transactional volume target.
        default ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
            return create(target, splitSize, ArchiveCreateOptions.DEFAULT);
        }

        /// Creates a writable file system over a transactional volume target with options.
        ArkivoFileSystem create(
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveCreateOptions options
        ) throws IOException;
    }
}
