// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens 7z archives as NIO file systems.
@NotNullByDefault
public abstract sealed class SevenZipArkivoFileSystem extends ArkivoFileSystem permits SevenZipArkivoFileSystemImpl {
    /// The environment option for an `ArkivoPasswordProvider` value.
    ///
    /// Read operations use the provider to decrypt encrypted data and headers. Write operations request one archive
    /// password, strictly interpret its bytes as UTF-16LE, and encrypt every non-empty entry data stream with
    /// 7z AES-256/SHA-256. Content encryption does not hide entry names or other header metadata.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("arkivo.7z", "passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for the default `SevenZipCompression` used by non-empty output entries.
    ///
    /// Values may be a complete compression object, a `SevenZipCompressionMethod`, or a stable method name string.
    /// The default remains `SevenZipCompression.copy()`.
    public static final ArkivoFileSystemOption<SevenZipCompression> COMPRESSION =
            ArkivoFileSystemOption.of(
                    "arkivo.7z",
                    "compression",
                    SevenZipCompression.class,
                    SevenZipArkivoFileSystem::compressionOptionValue
            );

    /// The environment option for an optional `SevenZipFilter` applied before output compression.
    ///
    /// Values may be a complete filter object, a `SevenZipFilterMethod`, or a stable method name string. No filter is
    /// applied by default.
    public static final ArkivoFileSystemOption<SevenZipFilter> FILTER =
            ArkivoFileSystemOption.of(
                    "arkivo.7z",
                    "filter",
                    SevenZipFilter.class,
                    SevenZipArkivoFileSystem::filterOptionValue
            );

    /// The environment option for the maximum `Long` byte size of each numbered output volume.
    ///
    /// Path-backed split output requires a conventional first-volume path such as `archive.7z.001`.
    public static final ArkivoFileSystemOption<Long> SPLIT_SIZE =
            ArkivoFileSystemOption.of(
                    "arkivo.7z",
                    "splitSize",
                    Long.class,
                    SevenZipArkivoFileSystem::splitSizeOptionValue
            );

    /// The environment option for whether new archives should encrypt metadata headers.
    ///
    /// Header encryption requires `PASSWORD_PROVIDER` and hides entry names and other metadata in an AES-encrypted
    /// encoded header. Non-empty entry data remains encrypted by the same password provider.
    public static final ArkivoFileSystemOption<Boolean> ENCRYPT_HEADERS =
            ArkivoFileSystemOption.of(
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
        return open(path, Map.of());
    }

    /// Opens a 7z archive file system with environment options.
    public static SevenZipArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return SevenZipArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static SevenZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static SevenZipArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        return open((ArkivoVolumeSource) source, environment);
    }

    /// Opens a multi-volume 7z archive file system.
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a multi-volume 7z archive file system with environment options.
    public static SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);
        if (config.archiveWritable()) {
            throw new UnsupportedOperationException("7z volume sources cannot be opened with write archive options");
        }
        return new SevenZipArkivoFileSystemImpl(SevenZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }

    /// Creates a forward-only 7z file system that publishes split output to a transactional volume target.
    ///
    /// The complete archive is assembled in a local seekable temporary file before volumes are published because 7z
    /// finalization rewrites header data.
    ///
    /// The target is opened when the file system closes. A successful close commits every volume; failure rolls back
    /// unpublished output.
    public static SevenZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return create(target, splitSize, Map.of());
    }

    /// Creates a forward-only 7z file system over a transactional volume target with environment options.
    ///
    /// The complete archive is assembled in a local seekable temporary file before volumes are published.
    ///
    /// Archive open options and `SPLIT_SIZE` are determined by this factory and must not be supplied in the environment.
    public static SevenZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");
        if (splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (environment.containsKey(ArkivoFileSystem.OPEN_OPTIONS.key())) {
            throw new IllegalArgumentException("7z volume target open options are determined by the factory");
        }
        if (environment.containsKey(SPLIT_SIZE.key())) {
            throw new IllegalArgumentException("7z volume target splitSize must be provided as the factory argument");
        }
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(environment);
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
}
