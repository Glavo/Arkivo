// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemOption;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoReadFileSystemImpl;
import org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
///
/// Single-volume channel sources support complete-rewrite updates when `READ` and `WRITE` are supplied together.
/// Such updates require an explicit `ArkivoFileSystem.COMMIT_TARGET`, preserve preamble and surviving local-record
/// bytes, and leave the original source unchanged. Existing entries and completed replacement or added entries remain
/// readable during the update session. General volume sources remain read-only through `open`; use `update` with an
/// explicit transactional volume target and output split size for complete-rewrite mutation.
@NotNullByDefault
public abstract sealed class ZipArkivoFileSystem extends ArkivoFileSystem
        permits StreamingZipArkivoFileSystemImpl, StreamingZipArkivoReadFileSystemImpl, ZipArkivoFileSystemImpl {
    /// The environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("arkivo.zip", "passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystem::defaultEncryptionOptionValue
            );

    /// The minimum ZIP split volume size defined by the ZIP format.
    public static final long MINIMUM_SPLIT_SIZE = 64L * 1024L;

    /// The maximum ZIP split volume size defined by the ZIP format.
    public static final long MAXIMUM_SPLIT_SIZE = 0xffff_ffffL;

    /// The environment option for a `Long` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Long> SPLIT_SIZE =
            ArkivoFileSystemOption.of("arkivo.zip", "splitSize", Long.class, ZipArkivoFileSystem::splitSizeOptionValue);

    /// The environment option for a `ZipEntryNameEncoding` value that controls entry name decoding.
    public static final ArkivoFileSystemOption<ZipEntryNameEncoding> ENTRY_NAME_ENCODING =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "entryNameEncoding",
                    ZipEntryNameEncoding.class,
                    ZipArkivoFileSystem::entryNameEncodingOptionValue
            );

    /// Creates a ZIP archive file system base instance.
    protected ZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a ZIP archive file system.
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ZipArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a read-only ZIP archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static ZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with environment options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`. The returned file system owns and closes the channel in all modes.
    public static ZipArkivoFileSystem open(
            SeekableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, environment));
    }
    /// Opens a read-only ZIP archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static ZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`.
    public static ZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return config.archiveWritable()
                ? StreamingZipArkivoFileSystemImpl.openUpdate(
                        ZipArkivoFileSystemProvider.instance(),
                        source,
                        config
                )
                : new ZipArkivoFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, source, config);
    }

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a split ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        if (config.archiveWritable()) {
            throw new UnsupportedOperationException("ZIP volume sources cannot be opened with write archive options");
        }
        return new ZipArkivoFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, volumes, config);
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
        return update(source, target, splitSize, Map.of());
    }

    /// Opens a complete-rewrite multi-volume update with environment options.
    ///
    /// Archive open options, `SPLIT_SIZE`, `COMMIT_TARGET`, and source mutation policy are
    /// determined by this factory and must not be supplied in the environment.
    public static ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");
        requireSplitSize(splitSize);
        if (environment.containsKey(ArkivoFileSystem.OPEN_OPTIONS.key())) {
            throw new IllegalArgumentException("ZIP volume update open options are determined by the factory");
        }
        if (environment.containsKey(SPLIT_SIZE.key())) {
            throw new IllegalArgumentException("ZIP volume update splitSize must be provided as the factory argument");
        }
        if (environment.containsKey(ArkivoFileSystem.COMMIT_TARGET.key())) {
            throw new IllegalArgumentException("ZIP volume updates use the factory volume target");
        }
        if (environment.containsKey(ArkivoFileSystem.SOURCE_MUTATION_POLICY.key())) {
            throw new IllegalArgumentException("ZIP volume updates always perform a complete rewrite");
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromUpdateEnvironment(environment);
        return StreamingZipArkivoFileSystemImpl.openUpdate(
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
        return create(target, splitSize, Map.of());
    }

    /// Creates a writable ZIP file system over a transactional volume target with environment options.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory. Commit targets, edit storage,
    /// and source mutation policies do not apply to direct volume creation.
    public static ZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");
        requireSplitSize(splitSize);
        if (environment.containsKey(ArkivoFileSystem.OPEN_OPTIONS.key())) {
            throw new IllegalArgumentException("ZIP volume target open options are determined by the factory");
        }
        if (environment.containsKey(SPLIT_SIZE.key())) {
            throw new IllegalArgumentException("ZIP volume target splitSize must be provided as the factory argument");
        }
        if (environment.containsKey(ArkivoFileSystem.COMMIT_TARGET.key())) {
            throw new IllegalArgumentException("ZIP volume target creation uses the factory target");
        }
        if (environment.containsKey(ArkivoFileSystem.EDIT_STORAGE.key())) {
            throw new IllegalArgumentException("ZIP volume target creation does not use edit storage");
        }
        if (environment.containsKey(ArkivoFileSystem.SOURCE_MUTATION_POLICY.key())) {
            throw new IllegalArgumentException("ZIP volume target creation has no source to mutate");
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromWriterEnvironment(environment);
        return new StreamingZipArkivoFileSystemImpl(
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

    /// Converts a raw entry name encoding option value.
    private static ZipEntryNameEncoding entryNameEncodingOptionValue(Object value) {
        if (value instanceof ZipEntryNameEncoding encoding) {
            return encoding;
        }
        if (value instanceof String stringValue) {
            return ZipEntryNameEncoding.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ZipEntryNameEncoding or String for key: " + ENTRY_NAME_ENCODING.key()
        );
    }
}
