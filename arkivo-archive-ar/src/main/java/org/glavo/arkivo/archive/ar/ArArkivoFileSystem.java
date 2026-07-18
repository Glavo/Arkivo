// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.Objects;

/// Opens AR archives as NIO file systems.
///
/// Opening an existing archive builds a logical snapshot of its members and stages member bodies through the selected
/// edit storage. Subsequent external changes to the source are not reflected in paths or attribute snapshots. The
/// thread-safety strategy in the common options governs concurrent operations and close coordination.
///
/// `update` opens a complete-rewrite session and atomically replaces the source by default; its
/// [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can select another publication target. AR symbol
/// indexes are omitted from rewritten archives because member offsets
/// change; callers that require a linker index must rebuild it with a platform tool such as `ranlib`.
/// Indexed sessions stage member bodies through the configured edit storage, using temporary files under the system
/// temporary directory by default. The file system owns and closes the selected edit storage.
/// A member being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding member state.
/// Channel-source update sessions require an explicit commit target because they have no source path to replace.
///
/// A successfully returned channel-backed file system owns its `SeekableByteChannel` or
/// `ArkivoSeekableChannelSource` and closes it with the file system. Path factories own only the internal handles they
/// open. Creation uses create-new semantics and fails when the archive path already exists. Closing a writable session
/// finishes its archive output; after close begins, paths and entry resources reject new operations according to the
/// selected thread-safety strategy.
@NotNullByDefault
public abstract sealed class ArArkivoFileSystem extends ArkivoFileSystem permits ArArkivoFileSystemImpl {
    /// The option for the detector used to select charsets for AR member names.
    private static final ArchiveOption<ArchiveMetadataCharsetDetector> METADATA_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.ar",
                    "metadataCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    ArArkivoFileSystem::metadataCharsetDetectorOptionValue
            );

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
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
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
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
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
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
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
                toLegacyOptions(options)
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
                toLegacyOptions(options)
        );
    }

    /// Converts strongly typed AR read settings for the internal parser.
    static ArchiveOptions toLegacyOptions(ArArchiveOptions.Read options) {
        return ArchiveOptions.fromReadOptions(options.common())
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector());
    }

    /// Converts strongly typed AR creation settings for the internal writer.
    static ArchiveOptions toLegacyOptions(ArArchiveOptions.Create options) {
        return ArchiveOptions.fromCreateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW
                ))
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector());
    }

    /// Converts strongly typed AR update settings for the internal complete-rewrite implementation.
    static ArchiveOptions toLegacyOptions(ArArchiveOptions.Update options) {
        return ArchiveOptions.fromUpdateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                ))
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector());
    }

    /// Converts a raw metadata charset detector option value.
    private static ArchiveMetadataCharsetDetector metadataCharsetDetectorOptionValue(Object value) {
        if (value instanceof ArchiveMetadataCharsetDetector detector) {
            return detector;
        }
        if (value instanceof Charset charset) {
            return ArchiveMetadataCharsetDetector.fixed(charset);
        }
        if (value instanceof String stringValue) {
            return ArchiveMetadataCharsetDetector.fixed(Charset.forName(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected ArchiveMetadataCharsetDetector, Charset, or String for key: "
                        + METADATA_CHARSET_DETECTOR.key()
        );
    }
}
