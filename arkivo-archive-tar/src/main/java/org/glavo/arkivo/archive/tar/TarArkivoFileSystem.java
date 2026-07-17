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
/// `update` opens a complete-rewrite session and atomically replaces the source by default; its
/// [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can select another publication target.
/// Indexed sessions stage entry bodies through the configured edit storage, using temporary files under the system
/// temporary directory by default. The file system owns and closes the selected edit storage.
/// An entry being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding entry state.
/// Channel-source update sessions require an explicit commit target because they have no source path to replace. The
/// detected format or explicitly selected compression codec is preserved when publishing the
/// derivative.
/// GNU sparse entries are staged as expanded logical files; an update commit normalizes old GNU `S` entries to regular
/// TAR entries while preserving their expanded content and metadata.
@NotNullByDefault
public abstract sealed class TarArkivoFileSystem extends ArkivoFileSystem permits TarArkivoFileSystemImpl {
    /// The option for a compression codec wrapping the TAR byte stream.
    ///
    /// Values may be a `CompressionCodec` or stable compression format name. Existing seekable archives auto-detect installed formats
    /// when this option is absent, while forward-only streaming readers treat an absent option as uncompressed because
    /// they cannot reliably undo a false compression match. New archives remain uncompressed when it is absent.
    private static final ArchiveOption<CompressionCodec<?>> COMPRESSION =
            ArchiveOption.of(
                    "arkivo.tar",
                    "compression",
                    compressionCodecType(),
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
    protected TarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a TAR archive file system.
    public static TarArkivoFileSystem open(Path path) throws IOException {
        return open(path, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a TAR archive file system with read options.
    public static TarArkivoFileSystem open(Path path, TarArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Creates a new path-backed TAR archive file system.
    public static TarArkivoFileSystem create(Path path) throws IOException {
        return create(path, TarArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed TAR archive file system with options.
    public static TarArkivoFileSystem create(Path path, TarArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a complete-rewrite update of an existing path-backed TAR archive.
    public static TarArkivoFileSystem update(Path path) throws IOException {
        return update(path, TarArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a complete-rewrite update of an existing path-backed TAR archive with options.
    public static TarArkivoFileSystem update(Path path, TarArchiveOptions.Update options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static TarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel with options.
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
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static TarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
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
    public static TarArkivoFileSystem update(
            SeekableByteChannel source,
            TarArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens a complete-rewrite update from an owned repeatable seekable source.
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
        ArchiveOptions result = ArchiveOptions.fromReadOptions(options.common())
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector());
        return options.compression() == null ? result : result.with(COMPRESSION, options.compression());
    }

    /// Converts strongly typed TAR creation settings for the internal writer.
    static ArchiveOptions toLegacyOptions(TarArchiveOptions.Create options) {
        ArchiveOptions result = ArchiveOptions.fromCreateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW
                ));
        return options.compression() == null ? result : result.with(COMPRESSION, options.compression());
    }

    /// Converts strongly typed TAR update settings for the internal complete-rewrite implementation.
    static ArchiveOptions toLegacyOptions(TarArchiveOptions.Update options) {
        ArchiveOptions result = ArchiveOptions.fromUpdateOptions(options.common())
                .with(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                ))
                .with(METADATA_CHARSET_DETECTOR, options.metadataCharsetDetector());
        return options.compression() == null ? result : result.with(COMPRESSION, options.compression());
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
    private static CompressionCodec<?> compressionOptionValue(Object value) {
        if (value instanceof CompressionCodec<?> codec) {
            return codec;
        }
        if (value instanceof String name) {
            return CompressionFormats.require(name).defaultCodec();
        }
        throw new IllegalArgumentException(
                "Expected CompressionCodec or String for key: " + COMPRESSION.key()
        );
    }

    /// Returns the erased compression codec class token with its public wildcard type restored.
    @SuppressWarnings("unchecked")
    private static Class<CompressionCodec<?>> compressionCodecType() {
        return (Class<CompressionCodec<?>>) (Class<?>) CompressionCodec.class;
    }
}
