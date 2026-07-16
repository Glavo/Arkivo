// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArchiveOption;
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
/// Path-backed `READ` and `WRITE` open a complete-rewrite update session. Closing a changed session atomically
/// replaces the source by default; `ArkivoFileSystem.COMMIT_TARGET` can publish a single-volume derivative. Existing
/// path-backed split archives preserve their first-volume size unless `SPLIT_SIZE` selects another output split size.
///
/// Modified decoded bodies and compressed random-read snapshots are staged through `ArkivoFileSystem.EDIT_STORAGE`.
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
/// Single-volume channel-source updates require an explicit `ArkivoFileSystem.COMMIT_TARGET` because no source path is
/// available for replacement. General volume sources remain read-only through `open`; use `update` with an explicit
/// transactional volume target when preserving or changing a multi-volume layout.
@NotNullByDefault
public abstract sealed class SevenZipArkivoFileSystem extends ArkivoFileSystem permits SevenZipArkivoFileSystemImpl {
    /// The option for an `ArkivoPasswordProvider` value.
    ///
    /// Read operations use the provider to decrypt encrypted data and headers. Write operations request one archive
    /// password, strictly interpret its bytes as UTF-16LE, and encrypt every non-empty entry data stream with
    /// 7z AES-256/SHA-256. Content encryption does not hide entry names or other header metadata.
    public static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.7z", "passwordProvider", ArkivoPasswordProvider.class);

    /// The option for the default `SevenZipCompression` used by non-empty output entries.
    ///
    /// Complete-rewrite updates use this compression for surviving entries unless an entry attribute view overrides it.
    ///
    /// Values may be a complete compression object, a `SevenZipCompressionMethod`, or a stable method name string.
    /// The default remains `SevenZipCompression.copy()`.
    public static final ArchiveOption<SevenZipCompression> COMPRESSION =
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
    public static final ArchiveOption<SevenZipFilter> FILTER =
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
    public static final ArchiveOption<SevenZipFilterChain> FILTERS =
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
    public static final ArchiveOption<Integer> SOLID_FILE_COUNT =
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
    public static final ArchiveOption<Long> SPLIT_SIZE =
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
    public static final ArchiveOption<Boolean> ENCRYPT_HEADERS =
            ArchiveOption.of(
                    "arkivo.7z",
                    "encryptHeaders",
                    Boolean.class,
                    SevenZipArkivoFileSystem::booleanOptionValue
            );


    /// Creates a 7z archive file system base instance.
    protected SevenZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }


    /// Opens a 7z archive file system.
    public static SevenZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArchiveOptions.EMPTY);
    }


    /// Opens a 7z archive file system with options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode. `CREATE` additionally allows a missing source path.
    public static SevenZipArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoFileSystemProvider.instance().openPath(path, options);
    }


    /// Opens a read-only 7z archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static SevenZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }


    /// Opens a 7z archive file system directly from one owned seekable channel with options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`. The returned file system owns and closes the channel in all modes.
    public static SevenZipArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static SevenZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }


    /// Opens a 7z archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`.
    public static SevenZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config;
        try {
            config = SevenZipArkivoFileSystemConfig.fromOptions(options);
            if (config.archiveWritable()) {
                if (!config.archiveUpdate()) {
                    throw new UnsupportedOperationException(
                            "7z channel sources support writes only through read/write update mode"
                    );
                }
                if (config.commitTarget() == null) {
                    throw new IllegalArgumentException(
                            "7z channel-source update mode requires ArkivoFileSystem.COMMIT_TARGET"
                    );
                }
            }
        } catch (RuntimeException | Error exception) {
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                null,
                source,
                config
        );
    }


    /// Opens a multi-volume 7z archive file system.
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ArchiveOptions.EMPTY);
    }


    /// Opens a multi-volume 7z archive file system with options.
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromOptions(options);
        if (config.archiveWritable()) {
            throw new UnsupportedOperationException("7z volume sources cannot be opened with write archive options");
        }
        return new SevenZipArkivoFileSystemImpl(SevenZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }


    /// Opens a complete-rewrite update over a multi-volume source and transactional volume target.
    ///
    /// The returned file system owns the source after this method returns successfully. Closing a changed file system
    /// assembles a new archive and commits every output volume; failures roll back the target transaction.
    public static SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return update(source, target, splitSize, ArchiveOptions.EMPTY);
    }


    /// Opens a complete-rewrite update over explicit multi-volume input and output with options.
    ///
    /// Archive open options, `SPLIT_SIZE`, and `COMMIT_TARGET` are determined by this factory and must not be supplied
    /// in the options.
    public static SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (options.contains(ArkivoFileSystem.OPEN_OPTIONS)) {
            throw new IllegalArgumentException("7z volume update open options are determined by the factory");
        }
        if (options.contains(SPLIT_SIZE)) {
            throw new IllegalArgumentException("7z volume update splitSize must be provided as the factory argument");
        }
        if (options.contains(ArkivoFileSystem.COMMIT_TARGET)) {
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
    public static SevenZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, ArchiveOptions.EMPTY);
    }


    /// Creates a forward-only 7z file system over a transactional volume target with options.
    ///
    /// The complete archive is assembled in a local seekable temporary file before volumes are published.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory and must not be supplied in the options.
    public static SevenZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (options.contains(ArkivoFileSystem.OPEN_OPTIONS)) {
            throw new IllegalArgumentException("7z volume target open options are determined by the factory");
        }
        if (options.contains(SPLIT_SIZE)) {
            throw new IllegalArgumentException("7z volume target splitSize must be provided as the factory argument");
        }
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterOptions(options);
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                target,
                splitSize,
                config
        );
    }


    /// Returns the major 7z format version stored in the signature header.
    public abstract int majorVersion();

    /// Returns the minor 7z format version stored in the signature header.
    public abstract int minorVersion();

    /// Returns the offset of the next header relative to the first byte after the signature header.
    public abstract long nextHeaderOffset();

    /// Returns the size in bytes of the next header.
    public abstract long nextHeaderSize();

    /// Returns the expected CRC-32 value of the next header bytes.
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
