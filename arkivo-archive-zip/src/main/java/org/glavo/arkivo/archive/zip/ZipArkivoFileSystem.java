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
/// Read-only sessions index the central directory and open local record bytes lazily. The source path, seekable source,
/// or volume source must therefore remain byte-for-byte stable until the file system closes. Paths and
/// `ZipArkivoEntryAttributes` are snapshots and do not observe external source changes. The common option's
/// thread-safety strategy controls concurrent operations and the treatment of active entry resources during close.
///
/// Path and seekable-channel updates preserve surviving local-record bytes while publishing a complete replacement
/// archive. Existing entries, including one with an active replacement channel, expose their preceding state until
/// replacement commit; completed replacement or added entries expose their new state. Compressed or encrypted
/// seekable entry channels stage decoded bytes through the configured edit storage. General volume sources remain
/// read-only through `open`; use `update` with an explicit transactional volume target for multi-volume mutation.
///
/// A successfully returned file system owns an explicitly supplied stream, channel, channel source, or volume source
/// and closes it with the file system. An `ArkivoVolumeTarget` remains caller-owned; only the transaction opened from it
/// is owned by the file system. Path creation uses create-or-truncate semantics and the path is not a complete ZIP
/// archive until close writes the central directory. Updates expose committed logical mutations inside the file system
/// and publish the complete replacement on close.
@NotNullByDefault
public abstract sealed class ZipArkivoFileSystem extends ArkivoFileSystem
        permits ZipArkivoReadOnlyFileSystemImpl, ZipArkivoWritableFileSystemImpl {
    /// The minimum ZIP split volume size defined by the ZIP format.
    public static final long MINIMUM_SPLIT_SIZE = 64L * 1024L;

    /// The maximum ZIP split volume size defined by the ZIP format.
    public static final long MAXIMUM_SPLIT_SIZE = 0xffff_ffffL;

    /// Creates a ZIP archive file system base instance.
    ///
    /// @param threadSafety the synchronization strategy for file-system operations and active entry resources
    protected ZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Creates a new path-backed ZIP archive file system with default creation options.
    ///
    /// The destination is created or truncated during this call. Closing the returned file system writes the central
    /// directory and closes all path-backed output resources; until then, the path need not contain a complete archive.
    ///
    /// @param path the destination archive path
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `path` is `null`
    /// @throws IOException if the destination cannot be prepared for writing
    public static ZipArkivoFileSystem create(Path path) throws IOException {
        return create(path, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a new path-backed ZIP archive file system with explicit creation options.
    ///
    /// The destination is created or truncated during this call. Closing the returned file system writes the central
    /// directory and closes all path-backed output resources; until then, the path need not contain a complete archive.
    ///
    /// @param path the destination archive path
    /// @param options the immutable creation configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `path` or `options` is `null`
    /// @throws IOException if the destination or configured edit storage cannot be prepared
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
    ///
    /// ZIP output begins at the stream's current write location. Closing the file system finishes the archive and closes
    /// the stream; a successful return transfers stream ownership to the file system.
    ///
    /// @param output the destination stream
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `output` is `null`
    public static ZipArkivoFileSystem create(OutputStream output) {
        return create(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a ZIP archive file system that owns the given output stream.
    ///
    /// ZIP output begins at the stream's current write location. Closing the file system finishes the archive and closes
    /// the stream; a successful return transfers stream ownership to the file system. Archive writes, including close,
    /// may block according to the stream implementation.
    ///
    /// @param output the destination stream
    /// @param options the immutable creation configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `output` or `options` is `null`
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
    ///
    /// No repositioning is attempted. For a seekable channel, ZIP output begins at and advances its current position;
    /// otherwise writes follow the channel's current output state. Closing the file system finishes the archive and
    /// closes the channel; a successful return transfers channel ownership to the file system.
    ///
    /// @param output the destination channel
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `output` is `null`
    public static ZipArkivoFileSystem create(WritableByteChannel output) {
        return create(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a ZIP archive file system that owns the given writable channel.
    ///
    /// No repositioning is attempted. For a seekable channel, ZIP output begins at and advances its current position;
    /// otherwise writes follow the channel's current output state. Closing the file system finishes the archive and
    /// closes the channel; a successful return transfers channel ownership to the file system. Archive writes, including
    /// close, may block according to the channel implementation.
    ///
    /// @param output the destination channel
    /// @param options the immutable creation configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `output` or `options` is `null`
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
    ///
    /// This call reads the source metadata and may block. Logical mutations are staged until close publishes a complete
    /// replacement to the source path.
    ///
    /// @param path the existing ZIP archive path
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `path` is `null`
    /// @throws IOException if the source archive cannot be opened, parsed, or staged for update
    public static ZipArkivoFileSystem update(Path path) throws IOException {
        return update(path, ZipArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a path-backed ZIP archive for complete-rewrite update with explicit options.
    ///
    /// This call reads the source metadata and may block. Logical mutations are staged until close publishes a complete
    /// replacement to `options.common().commitTarget()`, or to `path` when that target is `null`.
    ///
    /// @param path the existing ZIP archive path
    /// @param options the immutable update configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `path` or `options` is `null`
    /// @throws IOException if the source archive cannot be opened, parsed, or staged for update
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
    ///
    /// The channel's current position becomes logical archive offset zero, and its remaining extent becomes the fixed
    /// source size. This call reads source metadata and may reposition and read the channel. A successful return transfers
    /// channel ownership to the file system; close publishes the replacement to the required commit target and closes the
    /// source channel without writing to it.
    ///
    /// @param source the source channel positioned at the logical archive start
    /// @param options the update configuration, whose common settings must provide a commit target
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `source` or `options` is `null`
    /// @throws IllegalArgumentException if the options do not provide a commit target
    /// @throws IOException if the source channel cannot be adapted, read, parsed, or staged for update
    public static ZipArkivoFileSystem update(
            SeekableByteChannel source,
            ZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens an owned repeatable channel source for complete-rewrite ZIP update with explicit options.
    ///
    /// This call opens and reads source channels and may block. A successful return transfers source ownership to the file
    /// system; close publishes the replacement to the required commit target, closes every opened channel, and closes the
    /// source.
    ///
    /// @param source the repeatable single-volume source
    /// @param options the update configuration, whose common settings must provide a commit target
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `source` or `options` is `null`
    /// @throws IllegalArgumentException if the options do not provide a commit target
    /// @throws IOException if a source channel cannot be opened or read, or the archive cannot be parsed or staged
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
    ///
    /// The central directory and local records are read lazily. The first archive operation that needs them may block.
    ///
    /// @param path the ZIP archive path
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `path` is `null`
    /// @throws IOException if file-system read support cannot be initialized
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system with options.
    ///
    /// The central directory and local records are read lazily. The first archive operation that needs them may block.
    ///
    /// @param path the ZIP archive path
    /// @param options the immutable read configuration
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `path` or `options` is `null`
    /// @throws IOException if file-system read support cannot be initialized
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
    /// channel. Its remaining extent at invocation becomes the fixed archive size. Later archive access may reposition
    /// and read the channel while lazily parsing the central directory and local records.
    ///
    /// @param source the source channel positioned at the logical archive start
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `source` is `null`
    /// @throws IOException if the channel position or size cannot be queried or source adaptation fails
    public static ZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with options.
    ///
    /// The channel's current position becomes logical archive offset zero, and its remaining extent becomes the fixed
    /// archive size. The returned file system owns and closes the channel. Later archive access may reposition and read
    /// the channel while lazily parsing the central directory and local records.
    ///
    /// @param source the source channel positioned at the logical archive start
    /// @param options the immutable read configuration
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `source` or `options` is `null`
    /// @throws IOException if the channel position or size cannot be queried or source adaptation fails
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
    /// Later archive access opens and reads independent channels while lazily parsing archive records and may block.
    ///
    /// @param source the repeatable single-volume source
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `source` is `null`
    /// @throws IOException if file-system read support cannot be initialized
    public static ZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// Later archive access opens and reads independent channels while lazily parsing archive records and may block.
    ///
    /// @param source the repeatable single-volume source
    /// @param options the immutable read configuration
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `source` or `options` is `null`
    /// @throws IOException if file-system read support cannot be initialized
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
    ///
    /// Later archive access opens and reads volume channels while lazily parsing archive records and may block. A
    /// successful return transfers source ownership to the file system, which closes each opened channel and the source.
    ///
    /// @param volumes the repeatable source of independently positioned volume channels
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `volumes` is `null`
    /// @throws IOException if file-system read support cannot be initialized
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a split ZIP archive file system with options.
    ///
    /// Later archive access opens and reads volume channels while lazily parsing archive records and may block. A
    /// successful return transfers source ownership to the file system, which closes each opened channel and the source.
    ///
    /// @param volumes the repeatable source of independently positioned volume channels
    /// @param options the immutable read configuration
    /// @return a new open read-only ZIP file system
    /// @throws NullPointerException if `volumes` or `options` is `null`
    /// @throws IOException if file-system read support cannot be initialized
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
    ///
    /// @param source the repeatable source of the existing archive volumes
    /// @param target the caller-owned transactional destination for replacement volumes
    /// @param splitSize the maximum number of bytes written to each replacement volume
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `source` or `target` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [#MINIMUM_SPLIT_SIZE] through [#MAXIMUM_SPLIT_SIZE]
    /// @throws IOException if source metadata cannot be read, staging fails, or the target transaction cannot be opened
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
    /// This call reads source metadata and opens the target transaction, and therefore may block. The target itself
    /// remains caller-owned; the file system owns the opened transaction and the source after a successful return.
    ///
    /// @param source the repeatable source of the existing archive volumes
    /// @param target the caller-owned transactional destination for replacement volumes
    /// @param splitSize the maximum number of bytes written to each replacement volume
    /// @param options the immutable update configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `source`, `target`, or `options` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [#MINIMUM_SPLIT_SIZE] through [#MAXIMUM_SPLIT_SIZE],
    /// or if the options specify a commit target
    /// @throws IOException if source metadata cannot be read, staging fails, or the target transaction cannot be opened
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
    ///
    /// @param target the caller-owned transactional destination for archive volumes
    /// @param splitSize the maximum number of bytes written to each volume
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `target` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [#MINIMUM_SPLIT_SIZE] through [#MAXIMUM_SPLIT_SIZE]
    /// @throws IOException if the target transaction cannot be opened
    public static ZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a writable ZIP file system over a transactional volume target with options.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory. Commit targets, edit storage,
    /// and source mutation policies do not apply to direct volume creation.
    /// The target remains caller-owned; the file system owns the transaction opened during this call. Output and close
    /// may block according to the target implementation.
    ///
    /// @param target the caller-owned transactional destination for archive volumes
    /// @param splitSize the maximum number of bytes written to each volume
    /// @param options the immutable creation configuration
    /// @return a new open writable ZIP file system
    /// @throws NullPointerException if `target` or `options` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [#MINIMUM_SPLIT_SIZE] through [#MAXIMUM_SPLIT_SIZE],
    /// or if the creation options specify edit storage
    /// @throws IOException if the target transaction cannot be opened
    public static ZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireSplitSize(splitSize);
        if (options.common().editStorageFactory() != null) {
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
    ///
    /// The first call may read the source and block; read-only file systems cache the discovered range.
    ///
    /// @return the non-negative preamble size in bytes
    /// @throws UnsupportedOperationException if this file system creates a forward-only archive without an existing
    /// source
    /// @throws java.nio.file.ClosedFileSystemException if this file system is closed
    /// @throws IOException if the source cannot be read
    public abstract long preambleSize() throws IOException;

    /// Opens a read-only snapshot channel over the bytes stored before the ZIP archive body.
    ///
    /// The returned channel is managed by this file system. Closing the channel does not close the file system; file
    /// system close makes the channel unusable and may force-close it under strict thread-safety mode.
    /// The channel is read-only, has size [#preambleSize()], and starts at position zero. Reads advance its position and
    /// the destination buffer position according to [SeekableByteChannel] and may block while reading the archive source.
    /// The caller should close the channel when it is no longer needed.
    ///
    /// @return a new read-only seekable channel positioned at the start of the preamble
    /// @throws UnsupportedOperationException if this file system creates a forward-only archive without an existing
    /// source
    /// @throws java.nio.file.ClosedFileSystemException if this file system is closed
    /// @throws IOException if the source channel cannot be opened or the preamble range cannot be read
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
