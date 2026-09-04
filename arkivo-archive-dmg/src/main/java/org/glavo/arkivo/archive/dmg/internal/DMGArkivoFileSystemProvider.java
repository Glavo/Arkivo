// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.DMGArchiveOptions;
import org.glavo.arkivo.archive.dmg.DMGArkivoFileSystem;
import org.glavo.arkivo.archive.dmg.DMGArkivoFormat;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Provides JDK file-system provider entry points for DMG images.
@NotNullByDefault
public final class DMGArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by this provider.
    public static final String SCHEME = DMGArkivoFormat.instance().uriScheme();

    /// The environment option selecting a present partition index.
    private static final ArchiveOption<Integer> PARTITION_INDEX =
            ArchiveOption.of("arkivo.dmg", "partitionIndex", Integer.class);

    /// The shared provider used by public convenience factories.
    private static final DMGArkivoFileSystemProvider INSTANCE = new DMGArkivoFileSystemProvider();

    /// URI-opened file systems registered by normalized backing URI.
    private final ArkivoFileSystemProviderSupport.Registry<DMGArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

    /// Creates a DMG provider.
    public DMGArkivoFileSystemProvider() {
    }

    /// Returns the shared convenience-factory provider.
    ///
    /// @return the process-wide provider used by strongly typed factories
    public static DMGArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the DMG provider URI scheme.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens and registers a path-backed DMG from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        ArkivoFileSystemProviderSupport.ParsedUri parsed = parseUri(uri, false);
        DMGArchiveOptions options = readOptions(ArchiveOptions.fromEnvironment(environment));
        return fileSystems.open(parsed.archiveUri(), closeAction -> openPath(
                parsed.archivePath(),
                parsed.archiveUri(),
                options,
                closeAction
        ));
    }

    /// Opens a path-backed DMG selected through the installed format catalog.
    @Override
    public DMGArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, DMGArkivoFormat.instance());
        return openPath(path, readOptions(ArchiveOptions.fromEnvironment(environment)));
    }

    /// Opens a path-backed DMG with strongly typed options.
    ///
    /// @param path the DMG path
    /// @param options the read and partition-selection options
    /// @return a new unregistered read-only file system
    /// @throws IOException if the image or selected HFS Plus volume cannot be opened or parsed
    public DMGArkivoFileSystem openPath(Path path, DMGArchiveOptions options) throws IOException {
        return openPath(path, path.toUri().normalize(), options, () -> {
        });
    }

    /// Opens one path-backed source with its provider identity and close callback.
    private DMGArkivoFileSystem openPath(
            Path path,
            URI archiveUri,
            DMGArchiveOptions options,
            Runnable closeAction
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Path normalized = path.toAbsolutePath().normalize();
        ArkivoSeekableChannelSource source = () -> java.nio.file.Files.newByteChannel(
                normalized,
                StandardOpenOption.READ
        );
        return DMGArkivoFileSystemImpl.open(this, source, archiveUri, options, closeAction);
    }

    /// Returns a registered URI-opened DMG file system.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside a registered URI-opened DMG file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsed = parseUri(uri, true);
        return fileSystems.require(parsed.archiveUri()).getPath(
                Objects.requireNonNull(parsed.entryPath(), "entryPath")
        );
    }

    /// Opens a read-only byte channel for a DMG entry.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return readFileSystem(path).newByteChannel(
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(path, options),
                options,
                attributes
        );
    }

    /// Opens a read-only input stream for a DMG entry.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                options
        );
    }

    /// Rejects output streams because DMG support is read-only.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream over immediate DMG entry children.
    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        return readFileSystem(directory).newDirectoryStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(directory),
                filter
        );
    }

    /// Rejects directory creation because DMG support is read-only.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) {
        throw new ReadOnlyFileSystemException();
    }

    /// Rejects deletion because DMG support is read-only.
    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    /// Copies one DMG entry through common NIO copy semantics.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        readFileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// Rejects moves because DMG support is read-only.
    @Override
    public void move(Path source, Path target, CopyOption... options) {
        readFileSystem(source);
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two paths identify the same DMG entry.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns `false` after verifying that the DMG entry exists.
    @Override
    public boolean isHidden(Path path) throws IOException {
        readFileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the HFS Plus file store for a DMG path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return readFileSystem(path).fileStore(path);
    }

    /// Checks read-only access to a DMG entry.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        readFileSystem(path).checkAccess(path, modes);
    }

    /// Returns a standard attribute view for a DMG entry.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        return readFileSystem(path).getFileAttributeView(path, type, options);
    }

    /// Reads a typed attribute snapshot for a DMG entry.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(
            Path path,
            Class<A> type,
            LinkOption... options
    ) throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                type,
                options
        );
    }

    /// Reads named attributes for a DMG entry.
    @Override
    public Map<String, Object> readAttributes(
            Path path,
            String attributes,
            LinkOption... options
    ) throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attributes,
                options
        );
    }

    /// Reads an HFS Plus symbolic-link target.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return readFileSystem(link).readSymbolicLink(link);
    }

    /// Rejects attribute mutation because DMG support is read-only.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Converts raw NIO environment options into the public DMG option model.
    private static DMGArchiveOptions readOptions(ArchiveOptions options) {
        ArkivoFileSystemThreadSafety threadSafety = options.getOrDefault(
                ArchiveEnvironmentOptions.THREAD_SAFETY,
                ArkivoFileSystemThreadSafety.CONCURRENT_READ
        );
        ArchiveReadLimits limits = options.getOrDefault(
                ArchiveEnvironmentOptions.READ_LIMITS,
                ArchiveReadLimits.DEFAULT
        );
        Set<OpenOption> openOptions = options.getOrDefault(
                ArchiveEnvironmentOptions.OPEN_OPTIONS,
                Set.of(StandardOpenOption.READ)
        );
        for (OpenOption option : openOptions) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("DMG archive file systems are read-only");
            }
        }
        int partitionIndex = options.getOrDefault(
                PARTITION_INDEX,
                DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX
        );
        ArchiveReadOptions common = new ArchiveReadOptions(threadSafety, null, null, null, limits);
        return new DMGArchiveOptions(common, partitionIndex);
    }

    /// Returns the DMG implementation owning a path.
    private static DMGArkivoFileSystemImpl readFileSystem(Path path) {
        if (path.getFileSystem() instanceof DMGArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Parses a provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "DMG", requireEntryPath);
    }
}
