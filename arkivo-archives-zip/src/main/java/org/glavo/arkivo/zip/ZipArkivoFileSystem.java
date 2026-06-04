// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem extends ArkivoFileSystem {
    /// The environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for a fixed `byte[]` password value.
    public static final ArkivoFileSystemOption<byte[]> PASSWORD =
            ArkivoFileSystemOption.of("password", byte[].class, ZipArkivoFileSystem::passwordOptionValue);

    /// The environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArkivoFileSystemOption.of(
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystem::defaultEncryptionOptionValue
            );

    /// The environment option for a `Long` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Long> SPLIT_SIZE =
            ArkivoFileSystemOption.of("splitSize", Long.class, ZipArkivoFileSystem::splitSizeOptionValue);

    /// The environment option for a `ZipEntryNameEncoding` value that controls entry name decoding.
    public static final ArkivoFileSystemOption<ZipEntryNameEncoding> ENTRY_NAME_ENCODING =
            ArkivoFileSystemOption.of(
                    "entryNameEncoding",
                    ZipEntryNameEncoding.class,
                    ZipArkivoFileSystem::entryNameEncodingOptionValue
            );

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system, or `null` for split volume sources.
    private final @Nullable Path archivePath;

    /// The split volume source backing this file system, or `null` for single archive paths.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates a ZIP archive file system instance.
    ZipArkivoFileSystem(
            ZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            ZipArkivoFileSystemConfig config
    ) {
        super(config.storageAccess());
        if (archivePath == null && volumes == null) {
            throw new IllegalArgumentException("archivePath or volumes must be set");
        }
        if (archivePath != null && volumes != null) {
            throw new IllegalArgumentException("archivePath and volumes cannot both be set");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath;
        this.volumes = volumes;
        this.config = Objects.requireNonNull(config, "config");
        this.rootPath = ZipArkivoPath.root(this);
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

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a split ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoFileSystem(ZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }

    /// Returns the archive path backing this file system, or `null` for split volume sources.
    public @Nullable Path archivePath() {
        return archivePath;
    }

    /// Returns the split volume source backing this file system, or `null` for single archive paths.
    public @Nullable ArkivoVolumeSource volumes() {
        return volumes;
    }

    /// Returns the parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig config() {
        return config;
    }

    /// Returns the root path for this ZIP file system.
    ZipArkivoPath rootPath() {
        return rootPath;
    }

    /// Returns the number of bytes stored before the first ZIP local file header.
    public long preambleSize() throws IOException {
        checkOpen();
        throw new UnsupportedOperationException("ZIP preamble parsing is not implemented yet");
    }

    /// Opens a read-only channel over the bytes stored before the first ZIP local file header.
    public SeekableByteChannel openPreambleChannel() throws IOException {
        checkOpen();
        throw new UnsupportedOperationException("ZIP preamble access is not implemented yet");
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and any owned volume source.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        if (volumes != null) {
            volumes.close();
        }
    }

    /// Returns whether this ZIP file system is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return !config.storageAccess().writable();
    }

    /// Returns the ZIP entry path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directory path.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        return List.of(rootPath);
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        return List.of();
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "zip");
    }

    /// Returns a path inside this ZIP file system.
    @Override
    public Path getPath(String first, String... more) {
        checkOpen();
        return ZipArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("ZIP path matchers are not implemented yet");
    }

    /// Returns a user principal lookup service for this ZIP file system.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("ZIP user principals are not implemented yet");
    }

    /// Opens a watch service for this ZIP file system.
    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("ZIP watch services are not supported");
    }

    /// Requires this file system to be open.
    void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Converts a raw password option value.
    private static byte[] passwordOptionValue(Object value) {
        if (value instanceof byte[] password) {
            return password;
        }
        throw new IllegalArgumentException("Expected byte[] for key: password");
    }

    /// Converts a raw default encryption option value.
    private static ZipEncryption defaultEncryptionOptionValue(Object value) {
        if (value instanceof ZipEncryption encryption) {
            return encryption;
        }
        if (value instanceof String stringValue) {
            return ZipEncryption.of(stringValue);
        }
        throw new IllegalArgumentException("Expected ZipEncryption or String for key: defaultEncryption");
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
        throw new IllegalArgumentException("Expected Long, compatible integral number, or String for key: splitSize");
    }

    /// Converts a raw entry name encoding option value.
    private static ZipEntryNameEncoding entryNameEncodingOptionValue(Object value) {
        if (value instanceof ZipEntryNameEncoding encoding) {
            return encoding;
        }
        if (value instanceof String stringValue) {
            return ZipEntryNameEncoding.parse(stringValue);
        }
        throw new IllegalArgumentException("Expected ZipEntryNameEncoding or String for key: entryNameEncoding");
    }
}
