// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArchiveOption;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Opens TAR archives as NIO file systems.
///
/// Supplying `READ` and `WRITE` through `ArkivoFileSystem.OPEN_OPTIONS` opens a complete rewrite update session.
/// Closing a changed update session atomically replaces the source by default; `ArkivoFileSystem.COMMIT_TARGET` can
/// select another publication policy.
/// Indexed read and update sessions stage entry bodies through `ArkivoFileSystem.EDIT_STORAGE`, using temporary files
/// under the system temporary directory by default. The file system owns and closes the selected edit storage.
/// An entry being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding entry state.
/// Channel-source update sessions require an explicit `ArkivoFileSystem.COMMIT_TARGET` because they have no source
/// path to replace. The detected format or explicitly selected compression codec is preserved when publishing the
/// derivative.
/// GNU sparse entries are staged as expanded logical files; an update commit normalizes old GNU `S` entries to regular
/// TAR entries while preserving their expanded content and metadata.
@NotNullByDefault
public abstract sealed class TarArkivoFileSystem extends ArkivoFileSystem permits TarArkivoFileSystemImpl {
    /// The option for a compression codec wrapping the TAR byte stream.
    ///
    /// Values may be a `CompressionCodec<?>` or stable compression format name. Existing seekable archives auto-detect installed formats
    /// when this option is absent, while forward-only streaming readers treat an absent option as uncompressed because
    /// they cannot reliably undo a false compression match. New archives remain uncompressed when it is absent.
    public static final ArchiveOption<CompressionCodec<?>> COMPRESSION =
            ArchiveOption.of(
                    "arkivo.tar",
                    "compression",
                    compressionCodecType(),
                    TarArkivoFileSystem::compressionOptionValue
            );

    /// Creates a TAR archive file system base instance.
    protected TarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a TAR archive file system.
    public static TarArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArchiveOptions.EMPTY);
    }

    /// Opens a TAR archive file system with options.
    ///
    /// `READ` and `WRITE` select update mode. `CREATE` additionally allows a missing source archive.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed entry bodies in read and update modes.
    public static TarArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemProvider.instance().openPath(path, options);
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static TarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a TAR archive file system directly from one owned seekable channel with options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`. The returned file system owns and closes the channel in all modes.
    public static TarArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }
    /// Opens a read-only TAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static TarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a TAR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed entry bodies. `READ` and `WRITE` select
    /// complete-rewrite update mode and require an explicit `ArkivoFileSystem.COMMIT_TARGET`.
    public static TarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemImpl.open(TarArkivoFileSystemProvider.instance(), source, options);
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
