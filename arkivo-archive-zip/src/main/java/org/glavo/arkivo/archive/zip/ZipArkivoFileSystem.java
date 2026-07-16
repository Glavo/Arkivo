// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveOption;
import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
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
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
///
/// Single-volume channel sources support complete-rewrite updates when `READ` and `WRITE` are supplied together.
/// Such updates require an explicit `ArkivoFileSystem.COMMIT_TARGET`, preserve preamble and surviving local-record
/// bytes, and leave the original source unchanged. Existing entries, including an entry with an active replacement
/// channel, expose their preceding state until replacement commit; completed replacement or added entries expose their
/// new state. Compressed or encrypted seekable entry channels stage decoded bytes through
/// `ArkivoFileSystem.EDIT_STORAGE`; the file system owns configured storage, while the default keeps entries up to
/// 1 MiB in memory and uses temporary files beside a path-backed archive or in the system temporary directory for
/// volume sources. General volume sources remain
/// read-only through `open`; use `update` with an explicit transactional volume target and output split size for
/// complete-rewrite mutation.
@NotNullByDefault
public abstract sealed class ZipArkivoFileSystem extends ArkivoFileSystem
        permits ZipArkivoReadOnlyFileSystemImpl, ZipArkivoWritableFileSystemImpl {
    /// The option for an `ArkivoPasswordProvider` value.
    public static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.zip", "passwordProvider", ArkivoPasswordProvider.class);

    /// The option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArchiveOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArchiveOption.of(
                    "arkivo.zip",
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystem::defaultEncryptionOptionValue
            );

    /// The minimum ZIP split volume size defined by the ZIP format.
    public static final long MINIMUM_SPLIT_SIZE = 64L * 1024L;

    /// The maximum ZIP split volume size defined by the ZIP format.
    public static final long MAXIMUM_SPLIT_SIZE = 0xffff_ffffL;

    /// The option for a `Long` value that sets the maximum size of each output volume.
    public static final ArchiveOption<Long> SPLIT_SIZE =
            ArchiveOption.of("arkivo.zip", "splitSize", Long.class, ZipArkivoFileSystem::splitSizeOptionValue);

    /// The option for the detector used to select legacy entry-name and comment charsets while reading ZIP metadata.
    public static final ArchiveOption<ArchiveMetadataCharsetDetector> LEGACY_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.zip",
                    "legacyCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    ZipArkivoFileSystem::legacyCharsetDetectorOptionValue
            );

    /// Creates a ZIP archive file system base instance.
    protected ZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a ZIP archive file system.
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArchiveOptions.EMPTY);
    }

    /// Opens a ZIP archive file system with options.
    public static ZipArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ZipArkivoFileSystemProvider.instance().openPath(path, options);
    }

    /// Opens a read-only ZIP archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static ZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`. The returned file system owns and closes the channel in all modes.
    public static ZipArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only ZIP archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static ZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`.
    public static ZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromOptions(options);
        return config.archiveWritable()
                ? ZipArkivoWritableFileSystemImpl.openUpdate(
                        ZipArkivoFileSystemProvider.instance(),
                        source,
                        config
                )
                : new ZipArkivoReadOnlyFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, source, config);
    }

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ArchiveOptions.EMPTY);
    }

    /// Opens a split ZIP archive file system with options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromOptions(options);
        if (config.archiveWritable()) {
            throw new UnsupportedOperationException("ZIP volume sources cannot be opened with write archive options");
        }
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
        return update(source, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a complete-rewrite multi-volume update with options.
    ///
    /// Archive open options, `SPLIT_SIZE`, `COMMIT_TARGET`, and source mutation policy are
    /// determined by this factory and must not be supplied in the options.
    public static ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireSplitSize(splitSize);
        if (options.contains(ArkivoFileSystem.OPEN_OPTIONS)) {
            throw new IllegalArgumentException("ZIP volume update open options are determined by the factory");
        }
        if (options.contains(SPLIT_SIZE)) {
            throw new IllegalArgumentException("ZIP volume update splitSize must be provided as the factory argument");
        }
        if (options.contains(ArkivoFileSystem.COMMIT_TARGET)) {
            throw new IllegalArgumentException("ZIP volume updates use the factory volume target");
        }
        if (options.contains(ArkivoFileSystem.SOURCE_MUTATION_POLICY)) {
            throw new IllegalArgumentException("ZIP volume updates always perform a complete rewrite");
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
        return create(target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Creates a writable ZIP file system over a transactional volume target with options.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory. Commit targets, edit storage,
    /// and source mutation policies do not apply to direct volume creation.
    public static ZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireSplitSize(splitSize);
        if (options.contains(ArkivoFileSystem.OPEN_OPTIONS)) {
            throw new IllegalArgumentException("ZIP volume target open options are determined by the factory");
        }
        if (options.contains(SPLIT_SIZE)) {
            throw new IllegalArgumentException("ZIP volume target splitSize must be provided as the factory argument");
        }
        if (options.contains(ArkivoFileSystem.COMMIT_TARGET)) {
            throw new IllegalArgumentException("ZIP volume target creation uses the factory target");
        }
        if (options.contains(ArkivoFileSystem.EDIT_STORAGE)) {
            throw new IllegalArgumentException("ZIP volume target creation does not use edit storage");
        }
        if (options.contains(ArkivoFileSystem.SOURCE_MUTATION_POLICY)) {
            throw new IllegalArgumentException("ZIP volume target creation has no source to mutate");
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromWriterOptions(options);
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

    /// Converts a raw default encryption option value.
    private static ZipEncryption defaultEncryptionOptionValue(Object value) {
        if (value instanceof ZipEncryption encryption) {
            return encryption;
        }
        if (value instanceof String stringValue) {
            return ZipEncryption.of(stringValue);
        }
        throw new IllegalArgumentException("Expected ZipEncryption or String for key: " + DEFAULT_ENCRYPTION.key());
    }

    /// Requires a ZIP split size within format limits.
    private static void requireSplitSize(long splitSize) {
        if (splitSize < MINIMUM_SPLIT_SIZE || splitSize > MAXIMUM_SPLIT_SIZE) {
            throw new IllegalArgumentException(
                    "splitSize must be between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
    }

    /// Converts a raw split size option value.
    private static Long splitSizeOptionValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected Long, compatible integral number, or String for key: " + SPLIT_SIZE.key()
        );
    }

    /// Converts a raw legacy charset detector option value.
    private static ArchiveMetadataCharsetDetector legacyCharsetDetectorOptionValue(Object value) {
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
                        + LEGACY_CHARSET_DETECTOR.key()
        );
    }
}
