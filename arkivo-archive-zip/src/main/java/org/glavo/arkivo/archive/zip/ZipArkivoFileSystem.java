// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoReadOnlyFileSystemImpl;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoWritableFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
///
/// Path and seekable-channel updates preserve surviving local-record bytes while publishing a complete replacement
/// archive. Existing entries, including one with an active replacement channel, expose their preceding state until
/// replacement commit; completed replacement or added entries expose their new state. Compressed or encrypted
/// seekable entry channels stage decoded bytes through the configured edit storage. General volume sources remain
/// read-only through `open`; use `update` with an explicit transactional volume target for multi-volume mutation.
@NotNullByDefault
public abstract sealed class ZipArkivoFileSystem extends ArkivoFileSystem
        permits ZipArkivoReadOnlyFileSystemImpl, ZipArkivoWritableFileSystemImpl {
    /// The minimum ZIP split volume size defined by the ZIP format.
    public static final long MINIMUM_SPLIT_SIZE = 64L * 1024L;

    /// The maximum ZIP split volume size defined by the ZIP format.
    public static final long MAXIMUM_SPLIT_SIZE = 0xffff_ffffL;

    /// Creates a ZIP archive file system base instance.
    protected ZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Creates a new path-backed ZIP archive file system with default creation options.
    public static ZipArkivoFileSystem create(Path path) throws IOException {
        return create(path, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed ZIP archive file system with explicit creation options.
    public static ZipArkivoFileSystem create(Path path, ZipArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                path,
                ZipArkivoFileSystemConfig.fromCreateOptions(options)
        );
    }

    /// Creates a ZIP archive file system that owns the given output stream with default options.
    public static ZipArkivoFileSystem create(OutputStream output) {
        return create(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a ZIP archive file system that owns the given output stream.
    public static ZipArkivoFileSystem create(OutputStream output, ZipArchiveOptions.Create options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                output,
                ZipArkivoFileSystemConfig.fromCreateOptions(options)
        );
    }

    /// Creates a ZIP archive file system that owns the given writable channel with default options.
    public static ZipArkivoFileSystem create(WritableByteChannel output) {
        return create(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a ZIP archive file system that owns the given writable channel.
    public static ZipArkivoFileSystem create(WritableByteChannel output, ZipArchiveOptions.Create options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                output,
                ZipArkivoFileSystemConfig.fromCreateOptions(options)
        );
    }

    /// Opens a path-backed ZIP archive for complete-rewrite update with default options.
    public static ZipArkivoFileSystem update(Path path) throws IOException {
        return update(path, ZipArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a path-backed ZIP archive for complete-rewrite update with explicit options.
    public static ZipArkivoFileSystem update(Path path, ZipArchiveOptions.Update options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                path,
                ZipArkivoFileSystemConfig.fromUpdateOptions(options),
                null,
                true
        );
    }

    /// Opens an owned seekable channel for complete-rewrite ZIP update with explicit options.
    public static ZipArkivoFileSystem update(
            SeekableByteChannel source,
            ZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens an owned repeatable channel source for complete-rewrite ZIP update with explicit options.
    public static ZipArkivoFileSystem update(
            ArkivoSeekableChannelSource source,
            ZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ZipArkivoWritableFileSystemImpl.openUpdate(
                ZipArkivoFileSystemProvider.instance(),
                source,
                ZipArkivoFileSystemConfig.fromUpdateOptions(options)
        );
    }

    /// Opens a ZIP archive file system.
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system with options.
    public static ZipArkivoFileSystem open(Path path, ZipArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ZipArkivoFileSystemProvider.instance().openReadPath(
                path,
                ZipArkivoFileSystemConfig.fromReadOptions(options)
        );
    }

    /// Opens a read-only ZIP archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static ZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with options.
    ///
    /// The returned file system owns and closes the channel.
    public static ZipArkivoFileSystem open(
            SeekableByteChannel source,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only ZIP archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static ZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static ZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        return new ZipArkivoReadOnlyFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, source, config);
    }

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a split ZIP archive file system with options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, ZipArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        return new ZipArkivoReadOnlyFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }

    /// Opens a complete-rewrite update over a multi-volume source and transactional volume target.
    ///
    /// The returned file system owns the source after this method returns successfully. Closing the file system commits
    /// a complete replacement archive to the target; failures roll back the target transaction.
    public static ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return update(source, target, splitSize, ZipArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a complete-rewrite multi-volume update with options.
    ///
    /// Archive open options, `SPLIT_SIZE`, `COMMIT_TARGET`, and source mutation policy are
    /// determined by this factory and must not be supplied in the options.
    public static ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireSplitSize(splitSize);
        if (options.common().commitTarget() != null) {
            throw new IllegalArgumentException("ZIP volume updates use the factory volume target");
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromUpdateOptions(options);
        return ZipArkivoWritableFileSystemImpl.openUpdate(
                ZipArkivoFileSystemProvider.instance(),
                source,
                target,
                splitSize,
                config
        );
    }

    /// Creates a writable ZIP file system over a transactional volume target.
    ///
    /// The target transaction opens with the file system. A successful close commits every output volume; failures
    /// roll back the transaction.
    public static ZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a writable ZIP file system over a transactional volume target with options.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory. Commit targets, edit storage,
    /// and source mutation policies do not apply to direct volume creation.
    public static ZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireSplitSize(splitSize);
        if (options.common().editStorage() != null) {
            throw new IllegalArgumentException("ZIP volume target creation does not use edit storage");
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromCreateOptions(options);
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                target,
                splitSize,
                config
        );
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    public abstract long preambleSize() throws IOException;

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    public abstract SeekableByteChannel openPreambleChannel() throws IOException;

    /// Requires a ZIP split size within format limits.
    private static void requireSplitSize(long splitSize) {
        if (splitSize < MINIMUM_SPLIT_SIZE || splitSize > MAXIMUM_SPLIT_SIZE) {
            throw new IllegalArgumentException(
                    "splitSize must be between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
    }

}
