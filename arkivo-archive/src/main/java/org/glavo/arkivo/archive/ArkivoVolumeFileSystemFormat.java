// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Describes an archive format that can expose multiple physical volumes as one file system.
@NotNullByDefault
public interface ArkivoVolumeFileSystemFormat extends ArkivoFileSystemFormat {
    /// Opens a read-only file system from an owned volume source.
    ///
    /// @param source the volume source whose ownership is transferred to the returned file system
    /// @return a new read-only multi-volume archive file system
    /// @throws IOException if the archive cannot be opened or decoded
    default ArkivoFileSystem open(ArkivoVolumeSource source) throws IOException {
        return open(source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a file system from an owned volume source with options.
    ///
    /// @param source the volume source whose ownership is transferred to the returned file system
    /// @param options the read and lifecycle options
    /// @return a new multi-volume archive file system
    /// @throws IOException if the archive cannot be opened or decoded
    ArkivoFileSystem open(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException;

    /// Describes a format that supports multi-volume creation and complete-rewrite updates.
    @NotNullByDefault
    interface Writable extends ArkivoVolumeFileSystemFormat {
        /// Opens a complete-rewrite update from an owned volume source to a transactional volume target.
        ///
        /// @param source the volume source whose ownership is transferred to the returned file system
        /// @param target the transactional destination for replacement volumes
        /// @param splitSize the positive maximum output volume size in bytes
        /// @return a writable archive file system that publishes replacement volumes on successful close
        /// @throws IOException if the source or output transaction cannot be opened
        /// @throws IllegalArgumentException if {@code splitSize} is not positive
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
        ///
        /// @param source the volume source whose ownership is transferred to the returned file system
        /// @param target the transactional destination for replacement volumes
        /// @param splitSize the positive maximum output volume size in bytes
        /// @param options the update, publication, read-limit, and lifecycle options
        /// @return a writable archive file system that publishes replacement volumes on successful close
        /// @throws IOException if the source or output transaction cannot be opened
        /// @throws IllegalArgumentException if {@code splitSize} is not positive
        ArkivoFileSystem update(
                ArkivoVolumeSource source,
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveUpdateOptions options
        ) throws IOException;

        /// Creates a writable file system over a transactional volume target.
        ///
        /// @param target the transactional destination for the new archive volumes
        /// @param splitSize the positive maximum output volume size in bytes
        /// @return a new writable multi-volume archive file system
        /// @throws IOException if the output transaction cannot be opened
        /// @throws IllegalArgumentException if {@code splitSize} is not positive
        default ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
            return create(target, splitSize, ArchiveCreateOptions.DEFAULT);
        }

        /// Creates a writable file system over a transactional volume target with options.
        ///
        /// @param target the transactional destination for the new archive volumes
        /// @param splitSize the positive maximum output volume size in bytes
        /// @param options the creation and lifecycle options
        /// @return a new writable multi-volume archive file system
        /// @throws IOException if the output transaction cannot be opened
        /// @throws IllegalArgumentException if {@code splitSize} is not positive
        ArkivoFileSystem create(
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveCreateOptions options
        ) throws IOException;
    }
}
