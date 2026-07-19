// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/// Opens TAR archives as NIO file systems.
///
/// Opening an existing archive builds a stable snapshot of its logical paths and attributes. When the selected outer
/// codec exposes a random-access index, contiguous entry bodies remain lazy views over the backing archive; other entry
/// bodies are retained through the selected edit storage. A backing archive used by lazy views must remain byte-for-byte
/// unchanged until the file system closes. External changes never update the indexed paths or attributes and can make a
/// later body read fail. The thread-safety strategy in the common options governs concurrent operations and close
/// coordination.
///
/// `update` opens a complete-rewrite session and atomically replaces the source by default; its
/// [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can select another publication target.
/// Read and update sessions use the configured edit storage for entry bodies that cannot remain source-backed, using
/// temporary files under the system temporary directory by default. The file system owns and closes the selected edit
/// storage and any caller-supplied repeatable source.
/// An entry being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding entry state.
/// Channel-source update sessions require an explicit commit target because they have no source path to replace. The
/// detected format or explicitly selected compression codec is preserved when publishing the
/// derivative.
/// GNU sparse entries are staged as expanded logical files; an update commit normalizes old GNU `S` entries to regular
/// TAR entries while preserving their expanded content and metadata.
///
/// A channel or repeatable source remains caller-owned until factory arguments have been validated. Ownership then
/// transfers to the open operation: initialization failure closes the source, and a returned file system closes it
/// during file-system close. Creation uses create-new semantics and fails when the archive path already exists. Closing
/// a writable session writes the TAR end marker, finishes any outer codec, and closes owned output resources. A
/// channel-source update requires an explicit commit target.
@NotNullByDefault
public abstract sealed class TarArkivoFileSystem extends ArkivoFileSystem permits TarArkivoFileSystemImpl {
    /// The option for the explicit TAR outer-compression policy.
    ///
    /// Raw NIO environment values may be a policy, a `CompressionCodec`, or a stable compression format name. A codec or
    /// format name selects [TarCompression.Codec].
    private static final ArchiveOption<TarCompression> COMPRESSION =
            ArchiveOption.of(
                    "arkivo.tar",
                    "compression",
                    TarCompression.class,
                    TarArkivoFileSystem::compressionOptionValue
            );

    /// The option for decoding compression of an existing archive during an update.
    private static final ArchiveOption<TarCompression> SOURCE_COMPRESSION =
            ArchiveOption.of(
                    "arkivo.tar",
                    "sourceCompression",
                    TarCompression.class,
                    TarArkivoFileSystem::compressionOptionValue
            );

    /// The option for the detector used to select charsets for TAR metadata without an authoritative encoding.
    private static final ArchiveOption<ArchiveMetadataCharsetDetector> METADATA_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.tar",
                    "metadataCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    TarArkivoFileSystem::metadataCharsetDetectorOptionValue
            );

    /// Creates a TAR archive file system base instance.
    ///
    /// @param threadSafety the synchronization strategy applied to file-system operations and close coordination
    protected TarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a TAR archive file system.
    ///
    /// @param path the existing archive path; an installed outer compression format is detected automatically
    /// @return a read-only indexed file system that owns its retained entry resources
    /// @throws IOException if the archive cannot be opened, decoded, indexed, or retained within the default limits
    public static TarArkivoFileSystem open(Path path) throws IOException {
        return open(path, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a TAR archive file system with read options.
    ///
    /// @param path    the existing archive path to open
    /// @param options the compression selection, metadata decoding, limits, staging, and thread-safety policy
    /// @return a read-only indexed file system that owns its retained entry resources
    /// @throws IOException if the archive cannot be opened, decoded, indexed, or retained under `options`
    public static TarArkivoFileSystem open(Path path, TarArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Creates a new path-backed TAR archive file system.
    ///
    /// @param path the new archive path; creation fails rather than replacing an existing file
    /// @return a writable file system that finalizes the TAR end marker and closes its output when closed
    /// @throws IOException if the path already exists or the archive output cannot be initialized
    public static TarArkivoFileSystem create(Path path) throws IOException {
        return create(path, TarArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed TAR archive file system with options.
    ///
    /// @param path    the new archive path; creation fails rather than replacing an existing file
    /// @param options the outer compression, staging, and thread-safety policy for the new archive
    /// @return a writable file system that finalizes the TAR and outer codec when closed
    /// @throws IOException if the path already exists or the archive output cannot be initialized
    public static TarArkivoFileSystem create(Path path, TarArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a complete-rewrite update of an existing path-backed TAR archive.
    ///
    /// @param path the existing archive path whose logical contents may be changed
    /// @return an update file system that atomically replaces the source on close when changes were staged
    /// @throws IOException if the source cannot be opened, decoded, indexed, or prepared for rewriting
    public static TarArkivoFileSystem update(Path path) throws IOException {
        return update(path, TarArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a complete-rewrite update of an existing path-backed TAR archive with options.
    ///
    /// @param path    the existing archive path whose logical contents may be changed
    /// @param options the read, rewrite, compression, staging, publication, limits, and thread-safety policy
    /// @return an update file system that publishes a complete replacement on close when changes were staged
    /// @throws IOException if the source cannot be opened, decoded, indexed, or prepared under `options`
    public static TarArkivoFileSystem update(Path path, TarArchiveOptions.Update options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    ///
    /// @param source the seekable channel whose current position is the logical archive start; ownership transfers
    ///               after argument validation
    /// @return a read-only indexed file system that closes `source` with the file system
    /// @throws IOException if the source cannot be decoded, indexed, or retained; an open failure closes `source`
    public static TarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel with options.
    ///
    /// The channel's current position is the logical archive start. Ownership transfers after both arguments are
    /// validated, and the channel is closed on either open failure or file-system close.
    ///
    /// @param source  the seekable channel positioned at the first logical archive byte
    /// @param options the compression selection, metadata decoding, limits, staging, and thread-safety policy
    /// @return a read-only indexed file system backed by the owned channel
    /// @throws IOException if the source cannot be decoded, indexed, or retained under `options`
    public static TarArkivoFileSystem open(
            SeekableByteChannel source,
            TarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source.
    ///
    /// Ownership transfers after argument validation; initialization failure closes the source, and a returned file
    /// system closes it during file-system close.
    ///
    /// @param source the repeatable source whose channels begin at the same logical archive offset
    /// @return a read-only indexed file system that owns `source`
    /// @throws IOException if the source cannot be opened, decoded, indexed, or retained
    public static TarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source with options.
    ///
    /// Ownership transfers after argument validation; initialization failure closes the source, and a returned file
    /// system closes it during file-system close.
    ///
    /// @param source  the repeatable source whose channels begin at the same logical archive offset
    /// @param options the compression selection, metadata decoding, limits, staging, and thread-safety policy
    /// @return a read-only indexed file system that owns `source`
    /// @throws IOException if the source cannot be opened, decoded, indexed, or retained under `options`
    public static TarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            TarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemImpl.open(
                TarArkivoFileSystemProvider.instance(),
                source,
                toLegacyOptions(options)
        );
    }

    /// Opens a complete-rewrite update from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. Because the source has no replaceable path,
    /// `options` must provide a commit target. Ownership transfers after argument validation, and the channel is closed
    /// on open failure or file-system close.
    ///
    /// @param source  the seekable source channel positioned at the first logical archive byte
    /// @param options the update policy, including the required publication target
    /// @return an update file system that publishes a complete replacement through the commit target on close
    /// @throws IllegalArgumentException if `options` does not provide a commit target
    /// @throws IOException              if the source cannot be decoded, indexed, or prepared for rewriting
    public static TarArkivoFileSystem update(
            SeekableByteChannel source,
            TarArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens a complete-rewrite update from an owned repeatable seekable source.
    ///
    /// Because the source has no replaceable path, `options` must provide a commit target. The returned file system
    /// owns and closes the repeatable source; initialization failure also closes it after ownership transfers.
    ///
    /// @param source  the repeatable source whose channels begin at the same logical archive offset
    /// @param options the update policy, including the required publication target
    /// @return an update file system that publishes a complete replacement through the commit target on close
    /// @throws IllegalArgumentException if `options` does not provide a commit target
    /// @throws IOException              if the source cannot be opened, decoded, indexed, or prepared for rewriting
    public static TarArkivoFileSystem update(
            ArkivoSeekableChannelSource source,
            TarArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemImpl.open(
                TarArkivoFileSystemProvider.instance(),
                source,
                toLegacyOptions(options)
        );
    }

    /// Converts strongly typed TAR read settings for internal indexed readers.
    static ArchiveOptions toLegacyOptions(TarArchiveOptions.Read options) {
        return ArchiveOptions.fromReadOptions(options.common())
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector())
                .with(COMPRESSION, options.compression());
    }

    /// Converts strongly typed TAR creation settings for the internal writer.
    static ArchiveOptions toLegacyOptions(TarArchiveOptions.Create options) {
        return ArchiveOptions.fromCreateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW
                ))
                .with(COMPRESSION, options.compression());
    }

    /// Converts strongly typed TAR update settings for the internal complete-rewrite implementation.
    static ArchiveOptions toLegacyOptions(TarArchiveOptions.Update options) {
        return ArchiveOptions.fromUpdateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                ))
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector())
                .with(SOURCE_COMPRESSION, options.sourceCompression())
                .with(COMPRESSION, options.targetCompression());
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

    /// Converts a raw compression option value.
    private static TarCompression compressionOptionValue(Object value) {
        if (value instanceof TarCompression compression) {
            return compression;
        }
        if (value instanceof CompressionCodec<?> codec) {
            return TarCompression.using(codec);
        }
        if (value instanceof String name) {
            return TarCompression.using(CompressionFormats.require(name).defaultCodec());
        }
        throw new IllegalArgumentException(
                "Expected TarCompression, CompressionCodec, or String for key: " + COMPRESSION.key()
        );
    }
}
