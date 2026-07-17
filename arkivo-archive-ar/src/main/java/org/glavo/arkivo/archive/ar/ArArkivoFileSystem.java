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
/// `update` opens a complete-rewrite session and atomically replaces the source by default; its
/// [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can select another publication target. AR symbol
/// indexes are omitted from rewritten archives because member offsets
/// change; callers that require a linker index must rebuild it with a platform tool such as `ranlib`.
/// Indexed sessions stage member bodies through the configured edit storage, using temporary files under the system
/// temporary directory by default. The file system owns and closes the selected edit storage.
/// A member being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding member state.
/// Channel-source update sessions require an explicit commit target because they have no source path to replace.
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
    protected ArArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens an AR archive file system.
    public static ArArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens an AR archive file system with read options.
    public static ArArkivoFileSystem open(Path path, ArArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Creates a new path-backed AR archive file system.
    public static ArArkivoFileSystem create(Path path) throws IOException {
        return create(path, ArArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed AR archive file system with options.
    public static ArArkivoFileSystem create(Path path, ArArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a complete-rewrite update of an existing path-backed AR archive.
    public static ArArkivoFileSystem update(Path path) throws IOException {
        return update(path, ArArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a complete-rewrite update of an existing path-backed AR archive with options.
    public static ArArkivoFileSystem update(Path path, ArArchiveOptions.Update options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static ArArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel with options.
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
    public static ArArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only AR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
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
    public static ArArkivoFileSystem update(
            SeekableByteChannel source,
            ArArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens a complete-rewrite update from an owned repeatable seekable source.
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
