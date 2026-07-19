// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoStreamingSource;
import org.glavo.arkivo.archive.internal.ArchiveSizeLimitChannel;
import org.glavo.arkivo.archive.internal.PrefixReplayReadableByteChannel;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.internal.TemporaryArchiveSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Discovers installed official archive formats and opens their unified file-system and streaming APIs.
///
/// Detection methods borrow caller-supplied sources and restore seekable positions before returning. Factory overloads
/// that accept a closeable source or target validate their arguments before taking ownership. A successful factory
/// transfers that ownership to the returned file system, reader, or writer; lookup, capability, detection, and setup
/// failures close the endpoint without hiding the primary failure.
///
/// Paths and volume targets are configuration values rather than owned endpoints. A selected multi-volume format owns
/// only the output transaction it opens from an ArkivoVolumeTarget.
@NotNullByDefault
public final class ArkivoFormats {
    /// Immutable catalog of official format modules present when this class is initialized.
    private static final Catalog INSTALLED_FORMATS = Catalog.loadBuiltins();

    /// Creates no instances.
    private ArkivoFormats() {
    }

    /// Returns all installed official archive formats.
    ///
    /// The result is fixed when this class is initialized. Implementations not listed by Arkivo are ignored even if they
    /// implement [ArkivoFormat].
    ///
    /// @return the immutable format list in deterministic official order
    public static @Unmodifiable List<ArkivoFormat> installed() {
        return INSTALLED_FORMATS.formats();
    }

    /// Returns the archive format with the given stable name or alias, ignoring case.
    ///
    /// @param name the stable format name or alias
    /// @return the matching installed format, or {@code null} if none matches
    public static @Nullable ArkivoFormat find(String name) {
        return INSTALLED_FORMATS.find(name);
    }

    /// Returns the archive format with the given stable name or alias, ignoring case.
    ///
    /// @param name the stable format name or alias
    /// @return the matching installed format
    /// @throws IllegalArgumentException when no matching format is installed
    public static ArkivoFormat require(String name) {
        return INSTALLED_FORMATS.require(name);
    }

    /// Returns the matching installed archive format with the largest requested probe size.
    ///
    /// The supplied buffer is not modified.
    ///
    /// @param prefix the archive prefix to inspect, from its current position to its limit
    /// @return the best matching installed format, or {@code null} if none matches
    public static @Nullable ArkivoFormat detect(ByteBuffer prefix) {
        return INSTALLED_FORMATS.detect(prefix);
    }

    /// Detects an installed archive format from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    ///
    /// @param channel the borrowed seekable channel to probe
    /// @return the best matching installed format, or {@code null} if none matches
    /// @throws IOException if prefix bytes cannot be read or the original channel position cannot be restored
    public static @Nullable ArkivoFormat detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        int probeSize = INSTALLED_FORMATS.probeSize();
        long originalPosition = channel.position();
        @Nullable Throwable failure = null;
        try {
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = channel.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Archive format probe made no progress");
                }
            }
            prefix.flip();
            return INSTALLED_FORMATS.detect(prefix);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed archive format from the beginning of the given path.
    ///
    /// @param path the archive path to probe
    /// @return the best matching installed format, or {@code null} if none matches
    /// @throws IOException if the path cannot be opened or read
    public static @Nullable ArkivoFormat detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Detects an installed archive format through one independently opened seekable channel.
    ///
    /// This method closes the probe channel but does not take ownership of the repeatable source.
    ///
    /// @param source the borrowed repeatable source to probe
    /// @return the best matching installed format, or {@code null} if none matches
    /// @throws IOException if a probe channel cannot be opened, read, restored, or closed
    public static @Nullable ArkivoFormat detect(ArkivoSeekableChannelSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        try (SeekableByteChannel channel = source.openChannel()) {
            return detect(channel);
        }
    }

    /// Detects an installed archive format from the first independently opened archive volume.
    ///
    /// This method closes the probe channel but does not take ownership of the volume source.
    ///
    /// @param source the borrowed volume source whose volume zero is probed
    /// @return the best matching installed format, or {@code null} if volume zero is absent or no format matches
    /// @throws IOException if volume zero cannot be opened, read, restored, or closed
    public static @Nullable ArkivoFormat detect(ArkivoVolumeSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        try (@Nullable SeekableByteChannel channel = source.openVolume(0L)) {
            return channel == null ? null : detect(channel);
        }
    }

    /// Detects and opens a read-only archive file system from one owned seekable channel.
    ///
    /// Detection starts at and restores the channel's current position. That position then becomes logical archive offset
    /// zero for the opened file system.
    ///
    /// @param source the seekable channel whose ownership is transferred after argument validation
    /// @return a new read-only archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(SeekableByteChannel source) throws IOException {
        return openFileSystem(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive file system from one owned seekable channel with options.
    ///
    /// After argument validation, this method takes ownership of the channel and closes it when detection or setup fails.
    ///
    /// @param source  the seekable channel whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            SeekableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try {
            @Nullable ArkivoFormat format = detect(source);
            if (format == null) {
                return openTransformedFileSystem(source, options);
            }
            if (!(format instanceof ArkivoFormat.FileSystem fileSystemFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support file-system access: " + format.name()
                );
            }
            return fileSystemFormat.open(source, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Detects and opens an archive file system from a path.
    ///
    /// @param path the archive path
    /// @return a new read-only archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(Path path) throws IOException {
        return openFileSystem(path, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive file system from a path with options.
    ///
    /// @param path    the archive path
    /// @param options the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoFormat format = detect(path);
        if (format == null) {
            format = detectDiscoveredPathVolumeFormat(path);
        }
        if (format == null) {
            TransformedArchive transformed = detectTransformedArchive(
                    Files.newByteChannel(path, StandardOpenOption.READ),
                    options
            );
            if (transformed.outerCompressionLayers() == 1L
                    && transformed.format() instanceof ArkivoFormat.FileSystem.OuterCompressed outerCompressed) {
                return outerCompressed.open(path, options);
            }
            return openTransformedFileSystem(Files.newByteChannel(path, StandardOpenOption.READ), options);
        }
        if (!(format instanceof ArkivoFormat.FileSystem fileSystemFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support file-system access: " + format.name()
            );
        }
        return fileSystemFormat.open(path, options);
    }

    /// Opens an owning archive file system for the named installed format from one seekable channel.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the channel whose ownership is transferred after argument validation
    /// @return a new read-only archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an owning archive file system with options for the named installed format.
    ///
    /// After argument validation, this method owns the source and closes it when format lookup or setup fails.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the channel whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try {
            ArkivoFormat.FileSystem format = requireFileSystemFormat(formatName);
            return format.open(source, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Opens an archive file system for the named installed format from a path.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the archive path
    /// @return a new read-only archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(String formatName, Path path) throws IOException {
        return openFileSystem(formatName, path, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an archive file system with options for the named installed format from a path.
    ///
    /// This method bypasses signature detection and delegates path storage handling to the selected format.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the archive path
    /// @param options    the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireFileSystemFormat(formatName).open(path, options);
    }

    /// Detects and opens a read-only archive file system from an owned repeatable seekable source.
    ///
    /// @param source the repeatable source whose ownership is transferred after argument validation
    /// @return a new read-only archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(ArkivoSeekableChannelSource source) throws IOException {
        return openFileSystem(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive file system from an owned repeatable seekable source with options.
    ///
    /// After argument validation, this method owns the source and closes it when detection or setup fails.
    ///
    /// @param source  the repeatable source whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            ArkivoSeekableChannelSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedSeekableSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                TransformedArchive transformed = detectTransformedArchive(owned.openChannel(), options);
                if (transformed.outerCompressionLayers() == 1L
                        && transformed.format() instanceof ArkivoFormat.FileSystem.OuterCompressed outerCompressed) {
                    return outerCompressed.open(owned, options);
                }
                ArkivoFileSystem fileSystem;
                try (SeekableByteChannel channel = owned.openChannel()) {
                    fileSystem = openTransformedFileSystem(channel, options);
                }
                closeSupersededSource(owned, fileSystem);
                return fileSystem;
            }
            if (!(format instanceof ArkivoFormat.FileSystem fileSystemFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support file-system access: " + format.name()
                );
            }
            return fileSystemFormat.open(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Detects and opens a read-only archive file system from an owned volume source.
    ///
    /// @param source the volume source whose ownership is transferred after argument validation
    /// @return a new read-only multi-volume archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume file-system access
    public static ArkivoFileSystem openFileSystem(ArkivoVolumeSource source) throws IOException {
        return openFileSystem(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive file system from an owned volume source with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or setup fails.
    ///
    /// @param source  the volume source whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options
    /// @return a new multi-volume archive file system
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume file-system access
    public static ArkivoFileSystem openFileSystem(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                @Nullable SeekableByteChannel channel = owned.openVolume(0L);
                if (channel == null) {
                    throw new IOException("Unrecognized archive format");
                }
                ArkivoFileSystem fileSystem;
                try (channel) {
                    fileSystem = openTransformedFileSystem(channel, options);
                }
                closeSupersededSource(owned, fileSystem);
                return fileSystem;
            }
            if (!(format instanceof ArkivoFormat.VolumeFileSystem volumeFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support multi-volume file-system access: " + format.name()
                );
            }
            return volumeFormat.open(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Opens a read-only archive file system for the named format from an owned repeatable seekable source.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the repeatable source whose ownership is transferred after argument validation
    /// @return a new read-only archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoSeekableChannelSource source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an archive file system with options for the named format from a repeatable seekable source.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the repeatable source whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoSeekableChannelSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedSeekableSource owned = OwnedArchiveSources.own(source);
        try {
            return requireFileSystemFormat(formatName).open(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Opens a read-only multi-volume file system for the named format from an owned volume source.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @return a new read-only multi-volume archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support multi-volume file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoVolumeSource source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a multi-volume file system with options for the named format from an owned volume source.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new multi-volume archive file system
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support multi-volume file-system access
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            return requireVolumeFileSystemFormat(formatName).open(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Detects and opens a complete-rewrite update of an existing path-backed archive.
    ///
    /// @param path the existing archive path
    /// @return a writable archive file system that publishes a replacement on successful close
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened for update
    /// @throws UnsupportedOperationException if the detected format does not support path-backed updates
    public static ArkivoFileSystem updateFileSystem(Path path) throws IOException {
        return updateFileSystem(path, ArchiveUpdateOptions.DEFAULT);
    }

    /// Detects and opens a complete-rewrite update of an existing path-backed archive with options.
    ///
    /// @param path    the existing archive path
    /// @param options the update, publication, read-limit, and lifecycle options
    /// @return a writable archive file system that publishes a replacement on successful close
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened for update
    /// @throws UnsupportedOperationException if the detected format does not support path-backed updates
    public static ArkivoFileSystem updateFileSystem(
            Path path,
            ArchiveUpdateOptions options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoFormat format = detect(path);
        if (format == null) {
            TransformedArchive transformed = detectTransformedArchive(
                    Files.newByteChannel(path, StandardOpenOption.READ),
                    options.readOptions()
            );
            format = transformed.format();
            if (transformed.outerCompressionLayers() != 1L
                    || !(format instanceof ArkivoFormat.FileSystem.OuterCompressed)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support updating an outer-compressed source: " + format.name()
                );
            }
        }
        if (!(format instanceof ArkivoFormat.FileSystem.Writable writableFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support path-backed file-system updates: " + format.name()
            );
        }
        return writableFormat.update(path, options);
    }

    /// Opens a complete-rewrite update for the named path-backed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the existing archive path
    /// @return a writable archive file system that publishes a replacement on successful close
    /// @throws IOException                   if the archive cannot be opened for update
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support path-backed updates
    public static ArkivoFileSystem updateFileSystem(String formatName, Path path) throws IOException {
        return updateFileSystem(formatName, path, ArchiveUpdateOptions.DEFAULT);
    }

    /// Opens a complete-rewrite update for the named path-backed archive format with options.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the existing archive path
    /// @param options    the update, publication, read-limit, and lifecycle options
    /// @return a writable archive file system that publishes a replacement on successful close
    /// @throws IOException                   if the archive cannot be opened for update
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support path-backed updates
    public static ArkivoFileSystem updateFileSystem(
            String formatName,
            Path path,
            ArchiveUpdateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireWritableFileSystemFormat(formatName).update(path, options);
    }

    /// Creates a new path-backed archive file system for the named format.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the path at which to create the archive
    /// @return a new writable archive file system
    /// @throws IOException                   if the archive backing cannot be created
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support path-backed creation
    public static ArkivoFileSystem createFileSystem(String formatName, Path path) throws IOException {
        return createFileSystem(formatName, path, ArchiveCreateOptions.DEFAULT);
    }

    /// Creates a new path-backed archive file system for the named format with options.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the path at which to create the archive
    /// @param options    the creation and lifecycle options
    /// @return a new writable archive file system
    /// @throws IOException                   if the archive backing cannot be created
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support path-backed creation
    public static ArkivoFileSystem createFileSystem(
            String formatName,
            Path path,
            ArchiveCreateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireWritableFileSystemFormat(formatName).create(path, options);
    }

    /// Detects and opens a complete-rewrite update from owned volumes to a transactional volume target.
    ///
    /// @param source    the volume source whose ownership is transferred after argument validation
    /// @param target    the transactional destination for the replacement volumes
    /// @param splitSize the positive maximum size of each output volume in bytes
    /// @return a writable archive file system that publishes replacement volumes on successful close
    /// @throws IOException                   if the format cannot be detected or the update cannot be opened
    /// @throws IllegalArgumentException      if {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume updates
    public static ArkivoFileSystem updateFileSystem(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return updateFileSystem(source, target, splitSize, ArchiveUpdateOptions.DEFAULT);
    }

    /// Detects and opens a complete-rewrite multi-volume update with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or setup fails. The selected format controls target transaction publication.
    ///
    /// @param source    the volume source whose ownership is transferred after argument validation
    /// @param target    the transactional destination for the replacement volumes
    /// @param splitSize the positive maximum size of each output volume in bytes
    /// @param options   the update, publication, read-limit, and lifecycle options
    /// @return a writable archive file system that publishes replacement volumes on successful close
    /// @throws IOException                   if the format cannot be detected or the update cannot be opened
    /// @throws IllegalArgumentException      if {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume updates
    public static ArkivoFileSystem updateFileSystem(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveUpdateOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoFormat.VolumeFileSystem.Writable volumeFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support multi-volume file-system updates: " + format.name()
                );
            }
            return volumeFormat.update(owned, target, splitSize, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Opens a complete-rewrite multi-volume update for the named installed format.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @param target     the transactional destination for the replacement volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @return a writable archive file system that publishes replacement volumes on successful close
    /// @throws IOException                   if the update cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume updates
    public static ArkivoFileSystem updateFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return updateFileSystem(formatName, source, target, splitSize, ArchiveUpdateOptions.DEFAULT);
    }

    /// Opens a complete-rewrite multi-volume update for the named format with options.
    ///
    /// After argument validation, this method owns the source and closes it when lookup or setup fails. Signature
    /// detection is bypassed.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @param target     the transactional destination for the replacement volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @param options    the update, publication, read-limit, and lifecycle options
    /// @return a writable archive file system that publishes replacement volumes on successful close
    /// @throws IOException                   if the update cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume updates
    public static ArkivoFileSystem updateFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveUpdateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            return requireWritableVolumeFileSystemFormat(formatName).update(owned, target, splitSize, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Creates a writable multi-volume file system for the named installed format.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the transactional destination for the new volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @return a new writable multi-volume archive file system
    /// @throws IOException                   if the output transaction cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume creation
    public static ArkivoFileSystem createFileSystem(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return createFileSystem(formatName, target, splitSize, ArchiveCreateOptions.DEFAULT);
    }

    /// Creates a writable multi-volume file system for the named format with options.
    ///
    /// The selected format opens and owns any output transaction created from the target.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the transactional destination for the new volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @param options    the creation and lifecycle options
    /// @return a new writable multi-volume archive file system
    /// @throws IOException                   if the output transaction cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume creation
    public static ArkivoFileSystem createFileSystem(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        return requireWritableVolumeFileSystemFormat(formatName).create(target, splitSize, options);
    }

    /// Detects and opens a streaming reader from a path.
    ///
    /// @param path the archive path or one member of a discoverable volume set
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return openStreamingReader(path, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens a streaming reader from a path with options.
    ///
    /// Conventional multi-volume layouts are discovered before the path is treated as one forward-only file. The
    /// single-file fallback retains archive detection and installed outer source transformations.
    ///
    /// @param path    the archive path or one member of a discoverable volume set
    /// @param options the read and lifecycle options
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoStreamingReader volumeReader = openDiscoveredPathVolumeReader(path, options);
        if (volumeReader != null) {
            return volumeReader;
        }
        return openStreamingReader(Files.newByteChannel(path, StandardOpenOption.READ), options);
    }

    /// Opens a streaming reader for the named format from a path.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the archive path or one member of a format-specific volume set
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            Path path
    ) throws IOException {
        return openStreamingReader(formatName, path, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a configured streaming reader for the named format from a path.
    ///
    /// Signature detection and the optional compression bridge are bypassed. The selected format controls
    /// path-specific volume discovery.
    ///
    /// @param formatName the installed format name or alias
    /// @param path       the archive path or one member of a format-specific volume set
    /// @param options    the read and lifecycle options
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireStreamingReaderFormat(formatName).openStreamingReader(path, options);
    }

    /// Detects and opens a streaming reader from an owned multi-volume source.
    ///
    /// @param source the volume source whose ownership is transferred after argument validation
    /// @return a new owning multi-volume forward-only reader
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume forward-only reading
    public static ArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
        return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens a streaming reader from an owned multi-volume source with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or reader setup fails.
    ///
    /// @param source  the volume source whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options
    /// @return a new owning multi-volume forward-only reader
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support multi-volume forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                @Nullable SeekableByteChannel channel = owned.openVolume(0L);
                if (channel == null) {
                    throw new IOException("Unrecognized archive format");
                }
                ArkivoStreamingReader reader = openStreamingReader(channel, options);
                closeSupersededSource(owned, reader);
                return reader;
            }
            if (!(format instanceof ArkivoFormat.VolumeStreamingReader readerFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support multi-volume forward-only reading: " + format.name()
                );
            }
            return readerFormat.openStreamingReader(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Opens a streaming reader for the named format from an owned multi-volume source.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @return a new owning multi-volume forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support multi-volume forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ArkivoVolumeSource source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a configured streaming reader for the named format from an owned multi-volume source.
    ///
    /// Signature detection is bypassed. After argument validation, this method owns the source and closes it when format
    /// lookup, capability validation, or reader setup fails.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the volume source whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new owning multi-volume forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support multi-volume forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            return requireVolumeStreamingReaderFormat(formatName)
                    .openStreamingReader(owned, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Detects and opens an archive from a forward-only input stream.
    ///
    /// After argument validation, this method takes ownership of the source. The returned reader receives every logical
    /// archive byte after optional outer source transformation.
    ///
    /// @param source the input stream whose ownership is transferred after argument validation
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive from a forward-only input stream with options.
    ///
    /// @param source  the input stream whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return openStreamingReader(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Detects and opens an archive from a forward-only readable channel.
    ///
    /// @param source the channel whose ownership is transferred after argument validation
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the format cannot be detected or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
    }

    /// Detects and opens an archive from a forward-only readable channel with options.
    ///
    /// Raw archive recognition runs before the optional official compression bridge, so a valid archive always wins over
    /// a coincidental outer-wrapper signature. After argument validation, this method owns and closes the source on
    /// setup failure.
    ///
    /// @param source  the channel whose ownership is transferred after argument validation
    /// @param options the read and lifecycle options propagated through outer transformations
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the format cannot be detected, a transformation fails, or the archive cannot be opened
    /// @throws UnsupportedOperationException if the detected format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ReadableByteChannel current = source;
        long outerCompressionLayers = 0L;
        try {
            while (true) {
                ArchiveProbe archiveProbe = probeArchive(current);
                current = archiveProbe.channel();
                @Nullable ArkivoFormat format = archiveProbe.format();
                if (format != null) {
                    return openDetectedArchive(format, current, options);
                }

                @Nullable ArkivoStreamingSource transformed = OptionalCompressionSupport.probe(current, options);
                if (transformed == null) {
                    throw new IOException("Unrecognized archive format");
                }
                current = transformed.takeChannel();
                if (!transformed.transformed()) {
                    throw new IOException("Unrecognized archive format");
                }
                outerCompressionLayers = acceptOuterCompressionLayer(outerCompressionLayers, options.limits());
                current = ArchiveSizeLimitChannel.wrap(
                        current,
                        options.limits().maximumDecodedArchiveSize()
                );
            }
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(current, exception);
            throw exception;
        }
    }

    /// Opens an owning streaming reader for the named installed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the channel whose ownership is transferred after argument validation
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ReadableByteChannel source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an owning streaming reader with options for the named installed archive format.
    ///
    /// This method bypasses signature detection and the optional compression bridge. After argument validation, it owns the
    /// source and closes it when format lookup or reader setup fails.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the channel whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try {
            return requireStreamingReaderFormat(formatName).openStreamingReader(source, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Opens an owning streaming reader over an input stream for the named installed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the input stream whose ownership is transferred after argument validation
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            InputStream source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an owning streaming reader over an input stream with options.
    ///
    /// After argument validation, the stream is adapted to the channel-first named reader factory.
    ///
    /// @param formatName the installed format name or alias
    /// @param source     the input stream whose ownership is transferred after argument validation
    /// @param options    the read and lifecycle options
    /// @return a new owning forward-only reader
    /// @throws IOException                   if the archive cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only reading
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            InputStream source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return openStreamingReader(
                formatName,
                StreamChannelAdapters.readableChannel(source),
                options
        );
    }

    /// Opens an owning streaming writer for the named installed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the channel whose ownership is transferred after argument validation
    /// @return a new owning forward-only writer
    /// @throws IOException                   if the writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return openStreamingWriter(formatName, target, ArchiveCreateOptions.DEFAULT);
    }

    /// Opens an owning streaming writer with options for the named installed archive format.
    ///
    /// After argument validation, this method owns the target and closes it when format lookup or writer setup fails.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the channel whose ownership is transferred after argument validation
    /// @param options    the creation options
    /// @return a new owning forward-only writer
    /// @throws IOException                   if the writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target,
            ArchiveCreateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        try {
            return requireStreamingWriterFormat(formatName).openStreamingWriter(target, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(target, exception);
            throw exception;
        }
    }

    /// Opens an owning streaming writer over an output stream for the named installed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the output stream whose ownership is transferred after argument validation
    /// @return a new owning forward-only writer
    /// @throws IOException                   if the writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            OutputStream target
    ) throws IOException {
        return openStreamingWriter(formatName, target, ArchiveCreateOptions.DEFAULT);
    }

    /// Opens an owning streaming writer over an output stream with options.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the output stream whose ownership is transferred after argument validation
    /// @param options    the creation options
    /// @return a new owning forward-only writer
    /// @throws IOException                   if the writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName}
    /// @throws UnsupportedOperationException if the named format does not support forward-only writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            OutputStream target,
            ArchiveCreateOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        return openStreamingWriter(
                formatName,
                StreamChannelAdapters.writableChannel(target),
                options
        );
    }

    /// Opens a transactional multi-volume streaming writer for the named installed archive format.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the transactional destination for the output volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @return a new transactional multi-volume writer
    /// @throws IOException                   if the output transaction or writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return openStreamingWriter(formatName, target, splitSize, ArchiveCreateOptions.DEFAULT);
    }

    /// Opens a transactional multi-volume streaming writer with options.
    ///
    /// Argument and capability validation occur before the selected format can open a target transaction. The writer
    /// controls transaction commit and rollback after setup succeeds.
    ///
    /// @param formatName the installed format name or alias
    /// @param target     the transactional destination for the output volumes
    /// @param splitSize  the positive maximum size of each output volume in bytes
    /// @param options    the creation options
    /// @return a new transactional multi-volume writer
    /// @throws IOException                   if the output transaction or writer cannot be opened
    /// @throws IllegalArgumentException      if no installed format has {@code formatName} or {@code splitSize} is not positive
    /// @throws UnsupportedOperationException if the named format does not support multi-volume writing
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        return requireVolumeStreamingWriterFormat(formatName)
                .openStreamingWriter(target, splitSize, options);
    }

    /// Decodes installed outer compression layers and opens a detected archive file system from a temporary source.
    private static ArkivoFileSystem openTransformedFileSystem(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        ReadableByteChannel current = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        long outerCompressionLayers = 0L;
        try {
            while (true) {
                ArchiveProbe archiveProbe = probeArchive(current);
                current = archiveProbe.channel();
                @Nullable ArkivoFormat format = archiveProbe.format();
                if (format != null) {
                    if (!(format instanceof ArkivoFormat.FileSystem fileSystemFormat)) {
                        throw new UnsupportedOperationException(
                                "Archive format does not support file-system access: " + format.name()
                        );
                    }
                    TemporaryArchiveSource materialized = TemporaryArchiveSource.materialize(
                            current,
                            options.limits().maximumDecodedArchiveSize()
                    );
                    try {
                        return fileSystemFormat.open(materialized, options);
                    } catch (IOException | RuntimeException | Error exception) {
                        closeAfterFailedOpen(materialized, exception);
                        throw exception;
                    }
                }

                @Nullable ArkivoStreamingSource transformed = OptionalCompressionSupport.probe(current, options);
                if (transformed == null) {
                    throw new IOException("Unrecognized archive format");
                }
                current = transformed.takeChannel();
                if (!transformed.transformed()) {
                    throw new IOException("Unrecognized archive format");
                }
                outerCompressionLayers = acceptOuterCompressionLayer(outerCompressionLayers, options.limits());
                current = ArchiveSizeLimitChannel.wrap(
                        current,
                        options.limits().maximumDecodedArchiveSize()
                );
            }
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(current, exception);
            throw exception;
        }
    }

    /// Decodes installed outer compression layers and returns the first recognized logical archive format.
    private static TransformedArchive detectTransformedArchive(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        ReadableByteChannel current = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        long outerCompressionLayers = 0L;
        try {
            while (true) {
                ArchiveProbe archiveProbe = probeArchive(current);
                current = archiveProbe.channel();
                @Nullable ArkivoFormat format = archiveProbe.format();
                if (format != null) {
                    current.close();
                    return new TransformedArchive(format, outerCompressionLayers);
                }

                @Nullable ArkivoStreamingSource transformed = OptionalCompressionSupport.probe(current, options);
                if (transformed == null) {
                    throw new IOException("Unrecognized archive format");
                }
                current = transformed.takeChannel();
                if (!transformed.transformed()) {
                    throw new IOException("Unrecognized archive format");
                }
                outerCompressionLayers = acceptOuterCompressionLayer(outerCompressionLayers, options.limits());
                current = ArchiveSizeLimitChannel.wrap(
                        current,
                        options.limits().maximumDecodedArchiveSize()
                );
            }
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(current, exception);
            throw exception;
        }
    }

    /// Describes a format recognized after decoding one or more outer compression layers.
    ///
    /// @param format the recognized logical archive format
    /// @param outerCompressionLayers the positive number of decoded outer layers
    @NotNullByDefault
    private record TransformedArchive(ArkivoFormat format, long outerCompressionLayers) {
        /// Validates the transformed-format result.
        private TransformedArchive {
            Objects.requireNonNull(format, "format");
            if (outerCompressionLayers <= 0L) {
                throw new IllegalArgumentException("outerCompressionLayers must be positive");
            }
        }
    }

    /// Accounts for one decoded outer compression layer.
    private static long acceptOuterCompressionLayer(long previous, ArchiveReadLimits limits)
            throws ArkivoReadLimitException {
        long actual = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
        long maximum = limits.maximumOuterCompressionLayers();
        if (maximum >= 0L && actual > maximum) {
            throw new ArkivoReadLimitException(
                    ArkivoReadLimitKind.OUTER_COMPRESSION_LAYERS,
                    maximum,
                    actual,
                    null
            );
        }
        return actual;
    }

    /// Closes a source superseded by a materialized file system, closing that file system if source cleanup fails.
    private static void closeSupersededSource(Closeable source, Closeable replacement) throws IOException {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error exception) {
            try {
                replacement.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (exception != cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    /// Opens a detected forward-only format over the logical source.
    private static ArkivoStreamingReader openDetectedArchive(
            ArkivoFormat format,
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        if (!(format instanceof ArkivoFormat.StreamingReader readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only reading: " + format.name()
            );
        }
        return readerFormat.openStreamingReader(source, options);
    }

    /// Discovers and opens a conventional path-backed multi-volume reader, or returns `null` when none match.
    private static @Nullable ArkivoStreamingReader openDiscoveredPathVolumeReader(
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        for (ArkivoFormat candidate : installed()) {
            if (!(candidate instanceof ArkivoFormat.PathVolume pathFormat)) {
                continue;
            }
            @Nullable @Unmodifiable List<Path> volumePaths = pathFormat.discoverVolumePaths(path);
            if (volumePaths == null) {
                continue;
            }
            if (volumePaths.size() < 2) {
                throw new IllegalStateException(
                        "Discovered multi-volume paths must contain at least two paths: " + candidate.name()
                );
            }

            OwnedArchiveSources.OwnedVolumeSource source =
                    OwnedArchiveSources.own(ArkivoVolumeSource.of(volumePaths));
            try {
                @Nullable ArkivoFormat detected = detect(source);
                if (detected == null || !detected.name().equals(candidate.name())) {
                    source.close();
                    continue;
                }
                if (!(candidate instanceof ArkivoFormat.VolumeStreamingReader readerFormat)) {
                    throw new UnsupportedOperationException(
                            "Archive format does not support multi-volume forward-only reading: " + candidate.name()
                    );
                }
                return readerFormat.openStreamingReader(source, options);
            } catch (IOException | RuntimeException | Error exception) {
                closeOwnedSourceAfterFailedOpen(source, exception);
                throw exception;
            }
        }
        return null;
    }

    /// Detects a conventional path-backed multi-volume archive from its first physical volume.
    private static @Nullable ArkivoFormat detectDiscoveredPathVolumeFormat(Path path) throws IOException {
        for (ArkivoFormat candidate : installed()) {
            if (!(candidate instanceof ArkivoFormat.PathVolume pathFormat)
                    || !(candidate instanceof ArkivoFormat.VolumeFileSystem)) {
                continue;
            }
            @Nullable @Unmodifiable List<Path> volumePaths = pathFormat.discoverVolumePaths(path);
            if (volumePaths == null) {
                continue;
            }
            if (volumePaths.size() < 2) {
                throw new IllegalStateException(
                        "Discovered multi-volume paths must contain at least two paths: " + candidate.name()
                );
            }

            @Nullable ArkivoFormat detected = detect(ArkivoVolumeSource.of(volumePaths));
            if (detected != null && detected.name().equals(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }

    /// Returns the named installed format after requiring streaming reader support.
    private static ArkivoFormat.StreamingReader requireStreamingReaderFormat(String formatName) {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IllegalArgumentException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoFormat.StreamingReader readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only reading: " + format.name()
            );
        }
        return readerFormat;
    }

    /// Returns the named installed format after requiring streaming writer support.
    private static ArkivoFormat.StreamingWriter requireStreamingWriterFormat(String formatName) {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IllegalArgumentException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoFormat.StreamingWriter writerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only writing: " + format.name()
            );
        }
        return writerFormat;
    }

    /// Returns the named installed format after requiring file-system support.
    private static ArkivoFormat.FileSystem requireFileSystemFormat(String formatName) {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IllegalArgumentException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoFormat.FileSystem fileSystemFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support file-system access: " + format.name()
            );
        }
        return fileSystemFormat;
    }

    /// Returns the named installed format after requiring path-backed write support.
    private static ArkivoFormat.FileSystem.Writable requireWritableFileSystemFormat(
            String formatName
    ) {
        ArkivoFormat.FileSystem format = requireFileSystemFormat(formatName);
        if (!(format instanceof ArkivoFormat.FileSystem.Writable writableFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support path-backed file-system writes: " + format.name()
            );
        }
        return writableFormat;
    }

    /// Returns the named installed format after requiring multi-volume file-system support.
    private static ArkivoFormat.VolumeFileSystem requireVolumeFileSystemFormat(
            String formatName
    ) {
        ArkivoFormat.FileSystem format = requireFileSystemFormat(formatName);
        if (!(format instanceof ArkivoFormat.VolumeFileSystem volumeFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume file-system access: " + format.name()
            );
        }
        return volumeFormat;
    }

    /// Returns the named installed format after requiring multi-volume write support.
    private static ArkivoFormat.VolumeFileSystem.Writable requireWritableVolumeFileSystemFormat(
            String formatName
    ) {
        ArkivoFormat.VolumeFileSystem format = requireVolumeFileSystemFormat(formatName);
        if (!(format instanceof ArkivoFormat.VolumeFileSystem.Writable writableFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume file-system writes: " + format.name()
            );
        }
        return writableFormat;
    }

    /// Returns the named installed format after requiring multi-volume streaming reader support.
    private static ArkivoFormat.VolumeStreamingReader requireVolumeStreamingReaderFormat(
            String formatName
    ) {
        ArkivoFormat.StreamingReader format = requireStreamingReaderFormat(formatName);
        if (!(format instanceof ArkivoFormat.VolumeStreamingReader readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume forward-only reading: " + format.name()
            );
        }
        return readerFormat;
    }

    /// Returns the named installed format after requiring multi-volume streaming writer support.
    private static ArkivoFormat.VolumeStreamingWriter requireVolumeStreamingWriterFormat(
            String formatName
    ) {
        ArkivoFormat.StreamingWriter format = requireStreamingWriterFormat(formatName);
        if (!(format instanceof ArkivoFormat.VolumeStreamingWriter writerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume forward-only writing: " + format.name()
            );
        }
        return writerFormat;
    }

    /// Rejects non-positive output volume sizes.
    private static void requirePositiveSplitSize(long splitSize) {
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
    }

    /// Reads and replays the archive-format detection prefix from a logical source.
    private static ArchiveProbe probeArchive(ReadableByteChannel source) throws IOException {
        ByteBuffer prefix = ByteBuffer.allocate(INSTALLED_FORMATS.probeSize());
        while (prefix.hasRemaining()) {
            int read = source.read(prefix);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("Archive stream probe made no progress");
            }
        }
        prefix.flip();
        return new ArchiveProbe(
                INSTALLED_FORMATS.detect(prefix),
                new PrefixReplayReadableByteChannel(prefix, source)
        );
    }

    /// Stores one archive-format probe and its prefix-replaying source.
    ///
    /// @param format  detected format, or `null` when no format matched
    /// @param channel source that replays all bytes consumed by the probe
    private record ArchiveProbe(
            @Nullable ArkivoFormat format,
            ReadableByteChannel channel
    ) {
        /// Validates an archive probe.
        private ArchiveProbe {
            Objects.requireNonNull(channel, "channel");
        }
    }

    /// Closes a consumed endpoint after setup fails without hiding the primary failure.
    private static void closeAfterFailedOpen(Closeable endpoint, Throwable failure) {
        if (endpoint instanceof Channel channel && !channel.isOpen()) {
            return;
        }
        try {
            endpoint.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Closes an owned archive source only when the selected format has not already attempted cleanup.
    private static void closeOwnedSourceAfterFailedOpen(
            OwnedArchiveSources.OwnedVolumeSource source,
            Throwable failure
    ) {
        if (!source.closeAttempted()) {
            closeAfterFailedOpen(source, failure);
        }
    }

    /// Restores a probed channel position without hiding an earlier probe failure.
    private static void restorePosition(
            SeekableByteChannel channel,
            long position,
            @Nullable Throwable failure
    ) throws IOException {
        try {
            channel.position(position);
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }

    /// Stores and validates the installed official archive-format catalog.
    ///
    /// The implementation list is intentionally closed. Classes that are absent represent official optional modules
    /// that were not installed; arbitrary implementations of [ArkivoFormat] are never discovered.
    @NotNullByDefault
    static final class Catalog {
        /// Official format singleton classes in deterministic detection order.
        private static final @Unmodifiable List<String> FORMAT_CLASS_NAMES = List.of(
                "org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat",
                "org.glavo.arkivo.archive.ar.ArArkivoFormat",
                "org.glavo.arkivo.archive.cpio.CPIOArkivoFormat",
                "org.glavo.arkivo.archive.rar.RarArkivoFormat",
                "org.glavo.arkivo.archive.tar.TarArkivoFormat",
                "org.glavo.arkivo.archive.zip.ZipArkivoFormat"
        );

        /// Formats in deterministic official order, with repeated logical identities included once.
        private final @Unmodifiable List<ArkivoFormat> formats;

        /// Formats indexed by normalized stable names and aliases.
        private final @Unmodifiable Map<String, ArkivoFormat> formatsByName;

        /// The largest preferred signature prefix requested by any format.
        private final int probeSize;

        /// Validates and indexes supplied format descriptors.
        private Catalog(List<ArkivoFormat> formats) {
            ArrayList<ArkivoFormat> copiedFormats = new ArrayList<>(formats.size());
            Set<FormatIdentity> seenFormats = new HashSet<>();
            LinkedHashMap<String, ArkivoFormat> formatsByName = new LinkedHashMap<>();
            int maximumProbeSize = 0;
            for (ArkivoFormat format : formats) {
                ArkivoFormat checkedFormat = Objects.requireNonNull(format, "archive format");
                FormatIdentity identity = new FormatIdentity(
                        checkedFormat.getClass(),
                        normalizeAndValidateName(checkedFormat.name())
                );
                if (!seenFormats.add(identity)) {
                    continue;
                }

                registerName(formatsByName, checkedFormat.name(), checkedFormat);
                for (String alias : checkedFormat.aliases()) {
                    registerName(formatsByName, alias, checkedFormat);
                }
                int formatProbeSize = checkedFormat.probeSize();
                if (formatProbeSize < 0) {
                    throw new IllegalStateException(
                            "Archive format probe size must not be negative: " + checkedFormat.name()
                    );
                }
                maximumProbeSize = Math.max(maximumProbeSize, formatProbeSize);
                copiedFormats.add(checkedFormat);
            }
            this.formats = List.copyOf(copiedFormats);
            this.formatsByName = Map.copyOf(formatsByName);
            this.probeSize = maximumProbeSize;
        }

        /// Loads every present official format through its immutable singleton accessor.
        ///
        /// @return an immutable catalog of the official format modules present to this module's class loader
        static Catalog loadBuiltins() {
            ArrayList<ArkivoFormat> formats = new ArrayList<>(FORMAT_CLASS_NAMES.size());
            for (String className : FORMAT_CLASS_NAMES) {
                @Nullable ArkivoFormat format = loadFormat(className);
                if (format != null) {
                    formats.add(format);
                }
            }
            return new Catalog(formats);
        }

        /// Creates a catalog from explicit descriptors for internal validation and tests.
        ///
        /// @param formats the descriptors to validate and index in preferred order
        /// @return an immutable catalog containing each logical identity at most once
        static Catalog of(Iterable<? extends ArkivoFormat> formats) {
            Objects.requireNonNull(formats, "formats");
            ArrayList<ArkivoFormat> copiedFormats = new ArrayList<>();
            for (ArkivoFormat format : formats) {
                copiedFormats.add(Objects.requireNonNull(format, "archive format"));
            }
            return new Catalog(copiedFormats);
        }

        /// Returns formats in deterministic official order.
        ///
        /// @return the immutable ordered format list
        @Unmodifiable List<ArkivoFormat> formats() {
            return formats;
        }

        /// Returns the named format or null when no stable name or alias matches.
        ///
        /// @param name the case-insensitive stable name or alias to look up
        /// @return the matching format, or {@code null} if no format matches
        @Nullable ArkivoFormat find(String name) {
            Objects.requireNonNull(name, "name");
            return formatsByName.get(normalizeName(name));
        }

        /// Returns the named format.
        ///
        /// @param name the case-insensitive stable name or alias to look up
        /// @return the matching format
        /// @throws IllegalArgumentException when no stable name or alias matches
        ArkivoFormat require(String name) {
            @Nullable ArkivoFormat format = find(name);
            if (format == null) {
                throw new IllegalArgumentException("Unknown archive format: " + name);
            }
            return format;
        }

        /// Returns the matching format with the largest preferred probe size.
        ///
        /// The supplied buffer is not modified.
        ///
        /// @param prefix the archive prefix to test, from its current position to its limit
        /// @return the best matching format, or {@code null} if no format recognizes the prefix
        @Nullable ArkivoFormat detect(ByteBuffer prefix) {
            Objects.requireNonNull(prefix, "prefix");
            @Nullable ArkivoFormat detected = null;
            int detectedProbeSize = -1;
            for (ArkivoFormat format : formats) {
                if (format.matches(prefix.asReadOnlyBuffer()) && format.probeSize() > detectedProbeSize) {
                    detected = format;
                    detectedProbeSize = format.probeSize();
                }
            }
            return detected;
        }

        /// Returns the largest preferred signature prefix requested by an installed official format.
        ///
        /// @return the maximum indexed [ArkivoFormat#probeSize()] value
        int probeSize() {
            return probeSize;
        }

        /// Loads one known singleton class, or returns null when its optional module is absent.
        private static @Nullable ArkivoFormat loadFormat(String className) {
            try {
                Class<?> formatClass = Class.forName(className);
                Method instanceMethod = formatClass.getMethod("instance");
                if (!Modifier.isStatic(instanceMethod.getModifiers())) {
                    throw new IllegalStateException("Built-in archive format instance() is not static: " + className);
                }
                Object value = instanceMethod.invoke(null);
                if (!(value instanceof ArkivoFormat format)) {
                    throw new IllegalStateException(
                            "Built-in archive format has an incompatible instance: " + className
                    );
                }
                return format;
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
                throw new IllegalStateException("Failed to load built-in archive format: " + className, exception);
            }
        }

        /// Registers one stable name or alias after validating it for unambiguous lookup.
        private static void registerName(
                Map<String, ArkivoFormat> formatsByName,
                String name,
                ArkivoFormat format
        ) {
            String normalizedName = normalizeAndValidateName(name);
            @Nullable ArkivoFormat previous = formatsByName.putIfAbsent(normalizedName, format);
            if (previous != null && previous != format) {
                throw new IllegalStateException(
                        "Ambiguous archive format name or alias " + name
                                + ": " + previous.name() + " and " + format.name()
                );
            }
        }

        /// Normalizes one lookup name without using the process locale.
        private static String normalizeName(String name) {
            return name.toLowerCase(Locale.ROOT);
        }

        /// Validates and normalizes one stable name or alias.
        private static String normalizeAndValidateName(String name) {
            Objects.requireNonNull(name, "format name");
            if (name.isBlank()) {
                throw new IllegalStateException("Archive format names and aliases must not be blank");
            }
            return normalizeName(name);
        }

        /// Identifies one logical official format.
        ///
        /// @param implementation the concrete format implementation class
        /// @param name the normalized stable format name
        @NotNullByDefault
        private record FormatIdentity(
                Class<? extends ArkivoFormat> implementation,
                String name
        ) {
            /// Validates one logical format identity.
            private FormatIdentity {
                Objects.requireNonNull(implementation, "implementation");
                Objects.requireNonNull(name, "name");
            }
        }
    }
}
