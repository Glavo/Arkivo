// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemConfig;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Opens AR archives as NIO file systems.
///
/// Symbolic-link targets are decoded strictly as UTF-8. Reading or following a link with malformed target bytes
/// throws [java.nio.charset.CharacterCodingException] instead of substituting characters in its path.
///
/// Opening an existing archive builds a logical snapshot of its members and stages member bodies through the selected
/// edit storage. Subsequent external changes to the source are not reflected in paths or attribute snapshots. The
/// thread-safety strategy in the common options governs concurrent operations and close coordination.
///
/// `update` opens a complete-rewrite session and atomically replaces the source by default; its
/// [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can select another publication target. AR symbol
/// indexes are omitted from rewritten archives because member offsets
/// change; callers that require a linker index must rebuild it with a platform tool such as `ranlib`.
/// Creating or renaming an explicit member to the root-level names `__.SYMDEF`, `__.SYMDEF SORTED`,
/// `__.SYMDEF_64`, or `__.SYMDEF_64 SORTED` throws [java.nio.file.FileSystemException]; these names are
/// reserved for BSD symbol tables. Existing ordinary members whose stored paths normalize to these names remain
/// readable and updatable; rewriting keeps their records distinct from symbol tables.
/// Creating or renaming a member to a name containing NUL throws [java.nio.file.FileSystemException] before changing
/// the archive index or writing member bytes.
/// Indexed sessions stage member bodies through the configured edit storage, using temporary files under the system
/// temporary directory by default. The file system owns and closes the selected edit storage.
/// A member being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding member state.
/// For a new member opened through a writable channel, its name and any missing parent directories enter the index
/// only when the staged body is committed.
/// If a storage write or truncation fails on an update channel, further mutations through that channel fail. Closing
/// it discards the staged body and reports an `IOException` with the mutation failure as its cause. An existing member
/// remains unchanged, and a new member is not added. Argument and member-size validation failures do not discard a body.
/// Channel-source update sessions require an explicit commit target because they have no source path to replace.
///
/// Member creation accepts `posix:permissions` and `basic:lastModifiedTime` initial attributes. Modification times
/// are [java.nio.file.attribute.FileTime] values; serialization discards fractional seconds. Creation attributes do
/// not change an existing member opened for writing. When a supported attribute is repeated, its last value is used.
///
/// A successfully returned channel-backed file system owns its `SeekableByteChannel` or
/// `ArkivoSeekableChannelSource` and closes it with the file system. Path factories own only the internal handles they
/// open. Creation uses create-new semantics and fails when the archive path already exists. Closing a writable session
/// finishes its archive output; after close begins, paths and entry resources reject new operations according to the
/// selected thread-safety strategy.
@NotNullByDefault
public abstract sealed class ArArkivoFileSystem extends ArkivoFileSystem permits ArArkivoFileSystemImpl {
    /// Creates an AR archive file system base instance.
    ///
    /// @param threadSafety the operation-coordination strategy for this file system
    protected ArArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens an AR archive file system.
    ///
    /// @param path the path of the existing AR archive
    /// @return a read-only file system for the archive
    /// @throws IOException if the archive cannot be opened or its member table is invalid
    public static ArArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens an AR archive file system with read options.
    ///
    /// @param path the path of the existing AR archive
    /// @param options the read configuration
    /// @return a read-only file system for the archive
    /// @throws IOException if the archive cannot be opened or its member table is invalid
    public static ArArkivoFileSystem open(Path path, ArArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, ArArkivoFileSystemConfig.fromReadOptions(options));
    }

    /// Creates a new path-backed AR archive file system.
    ///
    /// @param path the path at which to create the archive
    /// @return a writable file system whose close operation publishes the new archive
    /// @throws IOException if the archive cannot be created, including when `path` already exists
    public static ArArkivoFileSystem create(Path path) throws IOException {
        return create(path, ArArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed AR archive file system with options.
    ///
    /// @param path the path at which to create the archive
    /// @param options the creation configuration
    /// @return a writable file system whose close operation publishes the new archive
    /// @throws IOException if the archive cannot be created, including when `path` already exists
    public static ArArkivoFileSystem create(Path path, ArArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, ArArkivoFileSystemConfig.fromCreateOptions(options));
    }

    /// Opens a complete-rewrite update of an existing path-backed AR archive.
    ///
    /// @param path the path of the archive to update
    /// @return a writable file system that publishes a complete replacement when closed successfully
    /// @throws IOException if the archive cannot be opened or update storage cannot be initialized
    public static ArArkivoFileSystem update(Path path) throws IOException {
        return update(path, ArArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a complete-rewrite update of an existing path-backed AR archive with options.
    ///
    /// @param path the path of the archive to update
    /// @param options the update configuration, including the publication target
    /// @return a writable file system that publishes a complete replacement when closed successfully
    /// @throws IOException if the archive cannot be opened or update storage cannot be initialized
    public static ArArkivoFileSystem update(Path path, ArArchiveOptions.Update options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, ArArkivoFileSystemConfig.fromUpdateOptions(options));
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    ///
    /// @param source the channel whose current position is the archive start
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be read; setup failure closes `source`
    public static ArArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel with options.
    ///
    /// @param source the channel whose current position is the archive start
    /// @param options the read configuration
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be read; setup failure closes `source`
    public static ArArkivoFileSystem open(
            SeekableByteChannel source,
            ArArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only AR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source the repeatable logical archive source
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be opened
    public static ArArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only AR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source the repeatable logical archive source
    /// @param options the read configuration
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be opened
    public static ArArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemImpl.open(
                ArArkivoFileSystemProvider.instance(),
                source,
                ArArkivoFileSystemConfig.fromReadOptions(options)
        );
    }

    /// Opens a complete-rewrite update from one owned seekable channel.
    ///
    /// @param source the channel whose current position is the archive start
    /// @param options the update configuration, which must identify a commit target
    /// @return a writable complete-rewrite file system that owns `source`
    /// @throws IOException if the archive cannot be opened or update storage cannot be initialized; setup failure
    /// closes `source`
    public static ArArkivoFileSystem update(
            SeekableByteChannel source,
            ArArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens a complete-rewrite update from an owned repeatable seekable source.
    ///
    /// @param source the repeatable logical archive source
    /// @param options the update configuration, which must identify a commit target
    /// @return a writable complete-rewrite file system that owns `source`
    /// @throws IOException if the archive cannot be opened or update storage cannot be initialized
    public static ArArkivoFileSystem update(
            ArkivoSeekableChannelSource source,
            ArArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemImpl.open(
                ArArkivoFileSystemProvider.instance(),
                source,
                ArArkivoFileSystemConfig.fromUpdateOptions(options)
        );
    }

}
