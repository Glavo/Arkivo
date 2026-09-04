// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemImpl;
import org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemProvider;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Exposes one HFS Plus or HFSX partition in a flattened DMG as a read-only NIO file system.
///
/// Opening indexes the catalog and extent metadata but does not retain file bodies. Every opened file channel decodes
/// only the UDIF runs needed by that file's HFS Plus allocation extents. A successfully returned file system owns the
/// supplied source; path factories open independent channels and retain only the path-backed source factory.
///
/// The automatic partition selection chooses the first direct HFS Plus or HFSX partition. Embedded HFS wrappers, APFS,
/// FileVault, HFS hard-link resolution, native HFS case-folded lookup, extended attributes, resource-fork exposure, and
/// `decmpfs` compressed files are not currently supported. Catalog paths therefore use their exact stored spelling and
/// Unicode normalization.
@NotNullByDefault
public abstract sealed class DMGArkivoFileSystem extends ArkivoFileSystem permits DMGArkivoFileSystemImpl {
    /// Creates a DMG file system base with the selected common thread-safety strategy.
    ///
    /// @param options the validated read options
    protected DMGArkivoFileSystem(DMGArchiveOptions options) {
        super(Objects.requireNonNull(options, "options").common().threadSafety());
    }

    /// Opens a path-backed DMG with default options.
    ///
    /// The returned file system opens independent path channels as needed and does not keep one physical channel open.
    ///
    /// @param path the DMG path
    /// @return a new read-only file system
    /// @throws IOException if the image or selected HFS Plus volume cannot be opened or parsed
    public static DMGArkivoFileSystem open(Path path) throws IOException {
        return open(path, DMGArchiveOptions.DEFAULT);
    }

    /// Opens a path-backed DMG with read options.
    ///
    /// @param path the DMG path
    /// @param options the read limits, thread-safety policy, and partition selection
    /// @return a new read-only file system
    /// @throws IOException if the image or selected HFS Plus volume cannot be opened or parsed
    public static DMGArkivoFileSystem open(Path path, DMGArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return DMGArkivoFileSystemProvider.instance().openPath(path, options);
    }

    /// Opens a DMG from one owned channel with default options.
    ///
    /// The channel's current position becomes logical DMG offset zero. The returned file system owns and closes the
    /// channel; setup failure also closes it.
    ///
    /// @param source the channel whose ownership is transferred
    /// @return a new read-only file system
    /// @throws IOException if source adaptation or image parsing fails
    public static DMGArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, DMGArchiveOptions.DEFAULT);
    }

    /// Opens a DMG from one owned channel with read options.
    ///
    /// The channel's current position becomes logical DMG offset zero. The returned file system owns and closes the
    /// channel; setup failure also closes it.
    ///
    /// @param source the channel whose ownership is transferred
    /// @param options the read limits, thread-safety policy, and partition selection
    /// @return a new read-only file system
    /// @throws IOException if source adaptation or image parsing fails
    public static DMGArkivoFileSystem open(
            SeekableByteChannel source,
            DMGArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a DMG from an owned repeatable source with default options.
    ///
    /// Every channel returned by the source must expose the same immutable DMG bytes. The returned file system owns and
    /// closes the source; setup failure also closes it.
    ///
    /// @param source the repeatable source whose ownership is transferred
    /// @return a new read-only file system
    /// @throws IOException if the image or selected HFS Plus volume cannot be opened or parsed
    public static DMGArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, DMGArchiveOptions.DEFAULT);
    }

    /// Opens a DMG from an owned repeatable source with read options.
    ///
    /// Every channel returned by the source must expose the same immutable DMG bytes. The returned file system owns and
    /// closes the source; setup failure also closes it.
    ///
    /// @param source the repeatable source whose ownership is transferred
    /// @param options the read limits, thread-safety policy, and partition selection
    /// @return a new read-only file system
    /// @throws IOException if the image or selected HFS Plus volume cannot be opened or parsed
    public static DMGArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            DMGArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return DMGArkivoFileSystemImpl.open(
                DMGArkivoFileSystemProvider.instance(),
                source,
                null,
                options,
                () -> {
                }
        );
    }

    /// Returns the selected HFS Plus or HFSX partition.
    ///
    /// @return the immutable selected partition descriptor
    /// @throws java.nio.file.ClosedFileSystemException if this file system is closed
    public abstract DMGPartition partition();
}
