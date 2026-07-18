// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

/// Opens 7z archives as NIO file systems.
///
/// Opening an existing archive indexes header metadata. Compressed bodies are decoded lazily, so the source path,
/// seekable source, or volume source must continue to expose the same bytes until the file system closes. Paths and
/// attributes form snapshots of the indexed header and do not observe external source changes. The common option's
/// thread-safety strategy governs concurrent operations, managed entry resources, and close coordination.
///
/// Path-backed `update` opens a complete-rewrite session. Closing a changed session atomically replaces the source by
/// default; [org.glavo.arkivo.archive.ArchiveUpdateOptions#commitTarget()] can publish a single-volume derivative.
/// Existing path-backed split archives preserve their first-volume size unless an explicit split size selects another
/// output layout.
///
/// Modified decoded bodies and compressed random-read snapshots are staged through the configured edit storage.
/// Each file system owns and closes configured storage. Read-only sessions keep decoded bodies up to 1 MiB in memory
/// and use temporary files for larger bodies by default; update sessions always use temporary files by default.
/// Path-backed temporary files are placed beside the archive, while explicit volume sessions use the platform
/// temporary directory. Copy entries remain directly addressable and bypass staging.
/// Storage close is deferred while a decoded seekable channel still owns a transient body.
///
/// An entry being replaced through an open writable channel is hidden from new reads until that channel closes;
/// channels and attribute snapshots opened before the replacement retain the preceding entry state.
///
/// Updates preserve decoded entry content and stored timestamps and attributes, then re-encode every surviving entry
/// with the configured output compression, filter chain, solid file-count policy, password, and header-encryption
/// policy.
///
/// Single-volume channel-source updates require an explicit commit target because no source path is
/// available for replacement. General volume sources remain read-only through `open`; use `update` with an explicit
/// transactional volume target when preserving or changing a multi-volume layout.
///
/// A successfully returned file system owns an explicitly supplied seekable channel, channel source, or volume source
/// and closes it with the file system. An `ArkivoVolumeTarget` itself remains caller-owned; the file system owns the
/// output transaction obtained from it. Path creation uses create-or-truncate semantics and the path may contain an
/// incomplete archive until close finalizes its header. Closing a changed update publishes the replacement; an
/// unchanged update closes without rewriting the source.
@NotNullByDefault
public abstract sealed class SevenZipArkivoFileSystem extends ArkivoFileSystem permits SevenZipArkivoFileSystemImpl {
    /// The option for an `ArkivoPasswordProvider` value.
    ///
    /// Read operations use the provider to decrypt encrypted data and headers. Write operations request one archive
    /// password, strictly interpret its bytes as UTF-16LE, and encrypt every non-empty entry data stream with
    /// 7z AES-256/SHA-256. Content encryption does not hide entry names or other header metadata.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.7z", "passwordProvider", ArkivoPasswordProvider.class);

    /// The option for the default `SevenZipCompression` used by non-empty output entries.
    ///
    /// Complete-rewrite updates use this compression for surviving entries unless an entry attribute view overrides it.
    ///
    /// Values may be a complete compression object, a `SevenZipCompressionMethod`, or a stable method name string.
    /// The default remains `SevenZipCompression.copy()`.
    private static final ArchiveOption<SevenZipCompression> COMPRESSION =
            ArchiveOption.of(
                    "arkivo.7z",
                    "compression",
                    SevenZipCompression.class,
                    SevenZipArkivoFileSystem::compressionOptionValue
            );


    /// The option for an optional `SevenZipFilter` applied before output compression.
    ///
    /// Complete-rewrite updates use this filter for surviving entries unless an entry attribute view overrides it.
    ///
    /// Values may be a complete filter object, a `SevenZipFilterMethod`, or a stable method name string. BCJ2 creates
    /// four physical folder streams whose MAIN, CALL, and JUMP branches use the selected compression. No filter is
    /// applied by default.
    private static final ArchiveOption<SevenZipFilter> FILTER =
            ArchiveOption.of(
                    "arkivo.7z",
                    "filter",
                    SevenZipFilter.class,
                    SevenZipArkivoFileSystem::filterOptionValue
            );


    /// The option for an immutable preprocessing filter chain applied before output compression.
    ///
    /// Filters run in list order. Complete-rewrite updates use this chain for surviving entries unless an entry
    /// attribute view overrides it. Values may be a SevenZipFilterChain, a list of SevenZipFilter values, or any
    /// single value accepted by FILTER. An empty chain disables preprocessing. BCJ2 must be the sole chain element.
    /// FILTER and FILTERS are mutually exclusive.
    private static final ArchiveOption<SevenZipFilterChain> FILTERS =
            ArchiveOption.of(
                    "arkivo.7z",
                    "filters",
                    SevenZipFilterChain.class,
                    SevenZipArkivoFileSystem::filterChainOptionValue
            );


    /// The option for the maximum number of non-empty files encoded into one solid folder.
    ///
    /// A value of `1` disables solid grouping and preserves independent compression streams. Larger values let
    /// consecutive files with equal compression and filter settings share one coder pipeline. A setting change starts
    /// a new folder even before this limit is reached. The default is `1`.
    private static final ArchiveOption<Integer> SOLID_FILE_COUNT =
            ArchiveOption.of(
                    "arkivo.7z",
                    "solidFileCount",
                    Integer.class,
                    SevenZipArkivoFileSystem::solidFileCountOptionValue
            );

    /// The option for the maximum `Long` byte size of each numbered output volume.
    ///
    /// Path-backed split output requires a conventional first-volume path such as `archive.7z.001`. Updates preserve
    /// an existing split archive's first-volume size when this option is absent; `-1` explicitly rewrites it as a
    /// single-volume archive.
    private static final ArchiveOption<Long> SPLIT_SIZE =
            ArchiveOption.of(
                    "arkivo.7z",
                    "splitSize",
                    Long.class,
                    SevenZipArkivoFileSystem::splitSizeOptionValue
            );


    /// The option for whether new archives should encrypt metadata headers.
    ///
    /// Header encryption requires `PASSWORD_PROVIDER` and hides entry names and other metadata in an AES-encrypted
    /// encoded header. Non-empty entry data remains encrypted by the same password provider.
    private static final ArchiveOption<Boolean> ENCRYPT_HEADERS =
            ArchiveOption.of(
                    "arkivo.7z",
                    "encryptHeaders",
                    Boolean.class,
                    SevenZipArkivoFileSystem::booleanOptionValue
            );


    /// Creates a 7z archive file system base instance.
    ///
    /// @param threadSafety the concurrency and managed-resource close policy
    protected SevenZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }


    /// Opens a 7z archive file system.
    ///
    /// @param path the first or only archive volume
    /// @return an owned read-only file system for the archive
    /// @throws IOException if the archive or one of its discovered volumes cannot be opened or parsed
    public static SevenZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, SevenZipArchiveOptions.READ_DEFAULTS);
    }


    /// Opens a 7z archive file system with read options.
    ///
    /// @param path    the first or only archive volume
    /// @param options the read limits, password provider, storage, and thread-safety policy
    /// @return an owned read-only file system for the archive
    /// @throws IOException if the archive or one of its discovered volumes cannot be opened or parsed
    public static SevenZipArkivoFileSystem open(Path path, SevenZipArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                path,
                SevenZipArkivoFileSystemConfig.fromReadOptions(options)
        );
    }

    /// Creates or replaces a path-backed 7z archive with default creation options.
    ///
    /// @param path the output archive path
    /// @return a writable file system that finalizes the archive on close
    /// @throws IOException if the output cannot be opened or initialized
    public static SevenZipArkivoFileSystem create(Path path) throws IOException {
        return create(path, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates or replaces a path-backed 7z archive with explicit creation options.
    ///
    /// @param path    the output archive path
    /// @param options the output encoding, encryption, storage, and thread-safety policy
    /// @return a writable file system that finalizes the archive on close
    /// @throws IOException if the output or configured staging storage cannot be initialized
    public static SevenZipArkivoFileSystem create(
            Path path,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                path,
                SevenZipArkivoFileSystemConfig.fromCreateOptions(options)
        );
    }

    /// Creates or replaces a split path-backed 7z archive with explicit creation options.
    ///
    /// @param firstVolume the conventional first-volume path, such as `archive.7z.001`
    /// @param splitSize   the positive maximum number of bytes per output volume
    /// @param options     the output encoding, encryption, storage, and thread-safety policy
    /// @return a writable file system that publishes all volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive or the path/options are inconsistent
    /// @throws IOException if the output volume set or staging storage cannot be initialized
    public static SevenZipArkivoFileSystem create(
            Path firstVolume,
            long splitSize,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(firstVolume, "firstVolume");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                firstVolume,
                SevenZipArkivoFileSystemConfig.fromCreateOptions(options, splitSize)
        );
    }

    /// Opens a path-backed 7z archive for complete-rewrite update with default options.
    ///
    /// @param path the first or only archive volume
    /// @return an update file system that publishes changed content on close
    /// @throws IOException if the archive cannot be opened, parsed, or prepared for replacement
    public static SevenZipArkivoFileSystem update(Path path) throws IOException {
        return update(path, SevenZipArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a path-backed 7z archive for complete-rewrite update with explicit options.
    ///
    /// @param path    the first or only archive volume
    /// @param options the read, rewrite, storage, publication, and thread-safety policy
    /// @return an update file system that publishes changed content on close
    /// @throws IOException if the archive cannot be opened, parsed, or prepared for replacement
    public static SevenZipArkivoFileSystem update(
            Path path,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                path,
                SevenZipArkivoFileSystemConfig.fromUpdateOptions(options)
        );
    }

    /// Opens a path-backed 7z archive for complete-rewrite update into one output file.
    ///
    /// @param path the first or only source volume
    /// @return an update file system that publishes a single-volume replacement on close
    /// @throws IOException if the archive cannot be opened, parsed, or prepared for replacement
    public static SevenZipArkivoFileSystem updateSingleVolume(Path path) throws IOException {
        return updateSingleVolume(path, SevenZipArchiveOptions.UPDATE_DEFAULTS);
    }

    /// Opens a path-backed 7z archive for complete-rewrite update into one output file with explicit options.
    ///
    /// @param path    the first or only source volume
    /// @param options the read, rewrite, storage, publication, and thread-safety policy
    /// @return an update file system that publishes a single-volume replacement on close
    /// @throws IOException if the archive cannot be opened, parsed, or prepared for replacement
    public static SevenZipArkivoFileSystem updateSingleVolume(
            Path path,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                path,
                SevenZipArkivoFileSystemConfig.fromUpdateOptions(
                        options,
                        SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE
                )
        );
    }

    /// Opens a path-backed 7z archive for complete-rewrite update with explicit split output.
    ///
    /// @param path      the first or only source volume
    /// @param splitSize the positive maximum number of bytes per replacement volume
    /// @param options   the read, rewrite, storage, publication, and thread-safety policy
    /// @return an update file system that publishes the split replacement on close
    /// @throws IllegalArgumentException if `splitSize` is not positive or the options conflict with split publication
    /// @throws IOException if the archive cannot be opened, parsed, or prepared for replacement
    public static SevenZipArkivoFileSystem update(
            Path path,
            long splitSize,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        return SevenZipArkivoFileSystemProvider.instance().openPath(
                path,
                SevenZipArkivoFileSystemConfig.fromUpdateOptions(options, splitSize)
        );
    }


    /// Opens a read-only 7z archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    ///
    /// @param source the owned channel positioned at the logical archive start
    /// @return a read-only file system backed by the channel
    /// @throws IOException if the source cannot be wrapped, read, or parsed
    public static SevenZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, SevenZipArchiveOptions.READ_DEFAULTS);
    }


    /// Opens a read-only 7z archive file system directly from one owned seekable channel with options.
    ///
    /// @param source  the owned channel positioned at the logical archive start
    /// @param options the read limits, password provider, storage, and thread-safety policy
    /// @return a read-only file system backed by the channel
    /// @throws IOException if the source cannot be wrapped, read, or parsed
    public static SevenZipArkivoFileSystem open(
            SeekableByteChannel source,
            SevenZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Creates a 7z archive in one owned seekable channel with default creation options.
    ///
    /// @param target the owned channel to truncate and use as archive output
    /// @return a writable file system that finalizes the channel content on close
    /// @throws IOException if the target cannot be wrapped or initialized
    public static SevenZipArkivoFileSystem create(SeekableByteChannel target) throws IOException {
        return create(target, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a 7z archive in one owned seekable channel with explicit creation options.
    ///
    /// @param target  the owned channel to truncate and use as archive output
    /// @param options the output encoding, encryption, storage, and thread-safety policy
    /// @return a writable file system that finalizes the channel content on close
    /// @throws IOException if the target or configured staging storage cannot be initialized
    public static SevenZipArkivoFileSystem create(
            SeekableByteChannel target,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromCreateOptions(options);
        return SeekableChannelSources.open(target, source -> openConfiguredSource(source, config));
    }

    /// Opens one owned seekable channel for complete-rewrite update with explicit publication options.
    ///
    /// @param source  the owned source channel positioned at the logical archive start
    /// @param options the update policy, including the required publication target
    /// @return an update file system that publishes changed content on close
    /// @throws IllegalArgumentException if the options do not provide a commit target
    /// @throws IOException if the source cannot be wrapped, read, or parsed
    public static SevenZipArkivoFileSystem update(
            SeekableByteChannel source,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> update(channelSource, options));
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source the repeatable owned source whose channels start at archive offset zero
    /// @return a read-only file system backed by the source
    /// @throws IOException if the source cannot open a channel or the archive cannot be parsed
    public static SevenZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, SevenZipArchiveOptions.READ_DEFAULTS);
    }


    /// Opens a read-only 7z archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source  the repeatable owned source whose channels start at archive offset zero
    /// @param options the read limits, password provider, storage, and thread-safety policy
    /// @return a read-only file system backed by the source
    /// @throws IOException if the source cannot open a channel or the archive cannot be parsed
    public static SevenZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            SevenZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config;
        try {
            config = SevenZipArkivoFileSystemConfig.fromReadOptions(options);
        } catch (RuntimeException | Error exception) {
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }
        return openConfiguredSource(source, config);
    }

    /// Opens a repeatable channel source for complete-rewrite update with explicit publication options.
    ///
    /// @param source  the repeatable owned source whose channels start at archive offset zero
    /// @param options the update policy, including the required publication target
    /// @return an update file system that publishes changed content on close
    /// @throws IllegalArgumentException if the options do not provide a commit target
    /// @throws IOException if the source cannot open a channel or the archive cannot be parsed
    public static SevenZipArkivoFileSystem update(
            ArkivoSeekableChannelSource source,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config;
        try {
            if (options.common().commitTarget() == null) {
                throw new IllegalArgumentException("7z channel-source updates require a commit target");
            }
            config = SevenZipArkivoFileSystemConfig.fromUpdateOptions(options);
        } catch (RuntimeException | Error exception) {
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }
        return openConfiguredSource(source, config);
    }

    /// Opens one already validated pathless source configuration.
    private static SevenZipArkivoFileSystem openConfiguredSource(
            ArkivoSeekableChannelSource source,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                null,
                source,
                config
        );
    }


    /// Opens a multi-volume 7z archive file system.
    ///
    /// @param volumes the owned ordered volume source
    /// @return a read-only file system backed by the volume source
    /// @throws IOException if a required volume cannot be opened or the archive cannot be parsed
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, SevenZipArchiveOptions.READ_DEFAULTS);
    }


    /// Opens a multi-volume 7z archive file system with options.
    ///
    /// @param volumes the owned ordered volume source
    /// @param options the read limits, password provider, storage, and thread-safety policy
    /// @return a read-only file system backed by the volume source
    /// @throws IOException if a required volume cannot be opened or the archive cannot be parsed
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes, SevenZipArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromReadOptions(options);
        return new SevenZipArkivoFileSystemImpl(SevenZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }


    /// Opens a complete-rewrite update over a multi-volume source and transactional volume target.
    ///
    /// The returned file system owns the source after this method returns successfully. Closing a changed file system
    /// assembles a new archive and commits every output volume; failures roll back the target transaction.
    ///
    /// @param source    the owned ordered source volumes
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @return an update file system that publishes changed volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive
    /// @throws IOException if the source, staging storage, or output transaction cannot be initialized
    public static SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return update(source, target, splitSize, SevenZipArchiveOptions.UPDATE_DEFAULTS);
    }


    /// Opens a complete-rewrite update over explicit multi-volume input and output with options.
    ///
    /// Archive open options, `SPLIT_SIZE`, and `COMMIT_TARGET` are determined by this factory and must not be supplied
    /// in the options.
    ///
    /// @param source    the owned ordered source volumes
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @param options   the read, rewrite, encryption, storage, and thread-safety policy
    /// @return an update file system that publishes changed volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive or the options provide a commit target
    /// @throws IOException if the source, staging storage, or output transaction cannot be initialized
    public static SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            SevenZipArchiveOptions.Update options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (options.common().commitTarget() != null) {
            throw new IllegalArgumentException("7z volume updates use the factory volume target");
        }
        SevenZipArkivoFileSystemConfig config =
                SevenZipArkivoFileSystemConfig.fromUpdateOptions(options);
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                source,
                target,
                splitSize,
                config
        );
    }


    /// Creates a forward-only 7z file system that publishes split output to a transactional volume target.
    ///
    /// The complete archive is assembled in a local seekable temporary file before volumes are published because 7z
    /// finalization rewrites header data.
    ///
    /// The target is opened when the file system closes. A successful close commits every volume; failure rolls back
    /// unpublished output.
    ///
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @return a writable file system that publishes all volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive
    /// @throws IOException if temporary staging cannot be initialized
    public static SevenZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }


    /// Creates a forward-only 7z file system over a transactional volume target with options.
    ///
    /// The complete archive is assembled in a local seekable temporary file before volumes are published.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory and must not be supplied in the options.
    ///
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @param options   the output encoding, encryption, storage, and thread-safety policy
    /// @return a writable file system that publishes all volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive or the options conflict with volume output
    /// @throws IOException if temporary staging cannot be initialized
    public static SevenZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromCreateOptions(options);
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                target,
                splitSize,
                config
        );
    }


    /// Returns the major 7z format version stored in the signature header.
    ///
    /// @return the unsigned major version number
    public abstract int majorVersion();

    /// Returns the minor 7z format version stored in the signature header.
    ///
    /// @return the unsigned minor version number
    public abstract int minorVersion();

    /// Returns the offset of the next header relative to the first byte after the signature header.
    ///
    /// @return the non-negative relative next-header offset
    public abstract long nextHeaderOffset();

    /// Returns the size in bytes of the next header.
    ///
    /// @return the non-negative encoded next-header size
    public abstract long nextHeaderSize();

    /// Returns the expected CRC-32 value of the next header bytes.
    ///
    /// @return the unsigned 32-bit next-header CRC-32 value
    public abstract long nextHeaderCrc32();

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


    /// Converts a raw compression option value.
    private static SevenZipCompression compressionOptionValue(Object value) {
        if (value instanceof SevenZipCompression compression) {
            return compression;
        }
        if (value instanceof SevenZipCompressionMethod method) {
            return SevenZipCompression.of(method);
        }
        if (value instanceof String stringValue) {
            return SevenZipCompression.of(SevenZipCompressionMethod.parse(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected SevenZipCompression, SevenZipCompressionMethod, or String for key: " + COMPRESSION.key()
        );
    }


    /// Converts a raw filter option value.
    private static SevenZipFilter filterOptionValue(Object value) {
        if (value instanceof SevenZipFilter filter) {
            return filter;
        }
        if (value instanceof SevenZipFilterMethod method) {
            return SevenZipFilter.of(method);
        }
        if (value instanceof String stringValue) {
            return SevenZipFilter.of(SevenZipFilterMethod.parse(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected SevenZipFilter, SevenZipFilterMethod, or String for key: " + FILTER.key()
        );
    }


    /// Converts a raw filter-chain option value.
    private static SevenZipFilterChain filterChainOptionValue(Object value) {
        if (value instanceof SevenZipFilterChain chain) {
            return chain;
        }
        if (value instanceof Iterable<?> values) {
            ArrayList<SevenZipFilter> filters = new ArrayList<>();
            for (Object item : values) {
                if (!(item instanceof SevenZipFilter filter)) {
                    throw new IllegalArgumentException(
                            "Expected only SevenZipFilter values for key: " + FILTERS.key()
                    );
                }
                filters.add(filter);
            }
            return filters.isEmpty() ? SevenZipFilterChain.EMPTY : new SevenZipFilterChain(filters);
        }
        return SevenZipFilterChain.of(filterOptionValue(value));
    }


    /// Converts and validates a raw solid file-count option value.
    private static Integer solidFileCountOptionValue(Object value) {
        int count;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            count = ((Number) value).intValue();
        } else if (value instanceof Long longValue) {
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("7z solid file count is outside the Integer range");
            }
            count = longValue.intValue();
        } else if (value instanceof String stringValue) {
            count = Integer.parseInt(stringValue);
        } else {
            throw new IllegalArgumentException(
                    "Expected Integer, compatible integral number, or String for key: " + SOLID_FILE_COUNT.key()
            );
        }
        if (count <= 0) {
            throw new IllegalArgumentException("7z solid file count must be positive");
        }
        return count;
    }

    /// Converts a raw boolean option value.
    private static Boolean booleanOptionValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        throw new IllegalArgumentException("Expected Boolean or String for key: " + ENCRYPT_HEADERS.key());
    }

    /// Closes a channel source after option validation fails and suppresses any cleanup failure.
    private static void closeSourceAfterOpenFailure(ArkivoSeekableChannelSource source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
    }
}
