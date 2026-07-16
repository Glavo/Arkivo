// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.PrefixReplayReadableByteChannel;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.spi.ArkivoStreamingSource;
import org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/// Discovers archive formats and opens their unified file-system and streaming APIs.
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
    /// Creates no instances.
    private ArkivoFormats() {
    }

    /// Returns all archive formats visible to the current thread context class loader.
    public static @Unmodifiable List<ArkivoFormat> installed() {
        return ArchiveFormatRegistry.load().formats();
    }

    /// Returns the archive format with the given stable name or alias, ignoring case.
    public static @Nullable ArkivoFormat find(String name) {
        return ArchiveFormatRegistry.load().find(name);
    }

    /// Returns the archive format with the given stable name or alias, ignoring case.
    ///
    /// @throws IllegalArgumentException when no matching format is installed
    public static ArkivoFormat require(String name) {
        return ArchiveFormatRegistry.load().require(name);
    }

    /// Returns the matching installed archive format with the largest requested probe size.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable ArkivoFormat detect(ByteBuffer prefix) {
        return ArchiveFormatRegistry.load().detect(prefix);
    }

    /// Detects an installed archive format from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    public static @Nullable ArkivoFormat detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        @Unmodifiable List<ArkivoFormat> formats = installed();
        int probeSize = maxProbeSize(formats);
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
            return detect(prefix, formats);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed archive format from the beginning of the given path.
    public static @Nullable ArkivoFormat detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Detects an installed archive format through one independently opened seekable channel.
    ///
    /// This method closes the probe channel but does not take ownership of the repeatable source.
    public static @Nullable ArkivoFormat detect(ArkivoSeekableChannelSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        try (SeekableByteChannel channel = source.openChannel()) {
            return detect(channel);
        }
    }

    /// Detects an installed archive format from the first independently opened archive volume.
    ///
    /// This method closes the probe channel but does not take ownership of the volume source.
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
    public static ArkivoFileSystem openFileSystem(SeekableByteChannel source) throws IOException {
        return openFileSystem(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive file system from one owned seekable channel with options.
    ///
    /// After argument validation, this method takes ownership of the channel and closes it when detection or setup fails.
    public static ArkivoFileSystem openFileSystem(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try {
            @Nullable ArkivoFormat format = detect(source);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoFileSystemFormat fileSystemFormat)) {
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
    public static ArkivoFileSystem openFileSystem(Path path) throws IOException {
        return openFileSystem(path, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive file system from a path with options.
    public static ArkivoFileSystem openFileSystem(
            Path path,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoFormat format = detect(path);
        if (format == null) {
            format = detectDiscoveredPathVolumeFormat(path);
        }
        if (format == null) {
            throw new IOException("Unrecognized archive format");
        }
        if (!(format instanceof ArkivoFileSystemFormat fileSystemFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support file-system access: " + format.name()
            );
        }
        return fileSystemFormat.open(path, options);
    }

    /// Opens an owning archive file system for the named installed format from one seekable channel.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens an owning archive file system with options for the named installed format.
    ///
    /// After argument validation, this method owns the source and closes it when format lookup or setup fails.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try {
            ArkivoFileSystemFormat format = requireFileSystemFormat(formatName);
            return format.open(source, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Opens an archive file system for the named installed format from a path.
    public static ArkivoFileSystem openFileSystem(String formatName, Path path) throws IOException {
        return openFileSystem(formatName, path, ArchiveOptions.EMPTY);
    }

    /// Opens an archive file system with options for the named installed format from a path.
    ///
    /// This method bypasses signature detection and delegates path storage handling to the selected format.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            Path path,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireFileSystemFormat(formatName).open(path, options);
    }

    /// Detects and opens a read-only archive file system from an owned repeatable seekable source.
    public static ArkivoFileSystem openFileSystem(ArkivoSeekableChannelSource source) throws IOException {
        return openFileSystem(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive file system from an owned repeatable seekable source with options.
    ///
    /// After argument validation, this method owns the source and closes it when detection or setup fails.
    public static ArkivoFileSystem openFileSystem(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedSeekableSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoFileSystemFormat fileSystemFormat)) {
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
    public static ArkivoFileSystem openFileSystem(ArkivoVolumeSource source) throws IOException {
        return openFileSystem(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive file system from an owned volume source with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or setup fails.
    public static ArkivoFileSystem openFileSystem(
            ArkivoVolumeSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoVolumeFileSystemFormat volumeFormat)) {
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
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoSeekableChannelSource source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens an archive file system with options for the named format from a repeatable seekable source.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
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
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoVolumeSource source
    ) throws IOException {
        return openFileSystem(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens a multi-volume file system with options for the named format from an owned volume source.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArchiveOptions options
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

    /// Detects and opens a complete-rewrite update from owned volumes to a transactional volume target.
    public static ArkivoFileSystem updateFileSystem(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return updateFileSystem(source, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Detects and opens a complete-rewrite multi-volume update with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or setup fails. The selected format controls target transaction publication.
    public static ArkivoFileSystem updateFileSystem(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
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
            if (!(format instanceof ArkivoVolumeFileSystemFormat volumeFormat)) {
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
    public static ArkivoFileSystem updateFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return updateFileSystem(formatName, source, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a complete-rewrite multi-volume update for the named format with options.
    ///
    /// After argument validation, this method owns the source and closes it when lookup or setup fails. Signature
    /// detection is bypassed.
    public static ArkivoFileSystem updateFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            return requireVolumeFileSystemFormat(formatName).update(owned, target, splitSize, options);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Creates a writable multi-volume file system for the named installed format.
    public static ArkivoFileSystem createFileSystem(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return createFileSystem(formatName, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Creates a writable multi-volume file system for the named format with options.
    ///
    /// The selected format opens and owns any output transaction created from the target.
    public static ArkivoFileSystem createFileSystem(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        return requireVolumeFileSystemFormat(formatName).create(target, splitSize, options);
    }

    /// Detects and opens a streaming reader from a path.
    public static ArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return openStreamingReader(path, ArchiveOptions.EMPTY);
    }

    /// Detects and opens a streaming reader from a path with options.
    ///
    /// Conventional multi-volume layouts are discovered before the path is treated as one forward-only file. The
    /// single-file fallback retains archive detection and installed outer source transformations.
    public static ArkivoStreamingReader openStreamingReader(
            Path path,
            ArchiveOptions options
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
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            Path path
    ) throws IOException {
        return openStreamingReader(formatName, path, ArchiveOptions.EMPTY);
    }

    /// Opens a configured streaming reader for the named format from a path.
    ///
    /// Signature detection and outer source providers are bypassed. The selected format controls path-specific volume
    /// discovery.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            Path path,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return requireStreamingReaderFormat(formatName).openStreamingReader(path, options);
    }

    /// Detects and opens a streaming reader from an owned multi-volume source.
    public static ArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
        return openStreamingReader(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens a streaming reader from an owned multi-volume source with options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            @Nullable ArkivoFormat format = detect(owned);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoVolumeStreamingReaderFormat readerFormat)) {
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
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ArkivoVolumeSource source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens a configured streaming reader for the named format from an owned multi-volume source.
    ///
    /// Signature detection is bypassed. After argument validation, this method owns the source and closes it when format
    /// lookup, capability validation, or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ArkivoVolumeSource source,
            ArchiveOptions options
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
    public static ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return openStreamingReader(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive from a forward-only input stream with options.
    public static ArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return openStreamingReader(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Detects and opens an archive from a forward-only readable channel.
    public static ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, ArchiveOptions.EMPTY);
    }

    /// Detects and opens an archive from a forward-only readable channel with options.
    ///
    /// Raw archive recognition runs before optional source providers, so a valid archive always wins over a coincidental
    /// outer-wrapper signature. After argument validation, this method owns and closes the source on setup failure.
    public static ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ReadableByteChannel current = source;
        try {
            @Unmodifiable List<ArkivoFormat> formats = installed();
            ArchiveProbe rawProbe = probeArchive(current, formats);
            current = rawProbe.channel();
            @Nullable ArkivoFormat format = rawProbe.format();
            if (format != null) {
                return openDetectedArchive(format, current, options);
            }

            for (ArkivoStreamingSourceProvider provider : ServiceLoader.load(
                    ArkivoStreamingSourceProvider.class
            )) {
                ArkivoStreamingSource transformed = provider.probe(current, options);
                current = transformed.takeChannel();
                if (!transformed.transformed()) {
                    continue;
                }

                ArchiveProbe transformedProbe = probeArchive(current, formats);
                current = transformedProbe.channel();
                format = transformedProbe.format();
                if (format != null) {
                    return openDetectedArchive(format, current, options);
                }
            }
            throw new IOException("Unrecognized archive format");
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(current, exception);
            throw exception;
        }
    }

    /// Opens an owning streaming reader for the named installed archive format.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ReadableByteChannel source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens an owning streaming reader with options for the named installed archive format.
    ///
    /// This method bypasses signature detection and outer source providers. After argument validation, it owns the
    /// source and closes it when format lookup or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ReadableByteChannel source,
            ArchiveOptions options
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
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            InputStream source
    ) throws IOException {
        return openStreamingReader(formatName, source, ArchiveOptions.EMPTY);
    }

    /// Opens an owning streaming reader over an input stream with options.
    ///
    /// After argument validation, the stream is adapted to the channel-first named reader factory.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            InputStream source,
            ArchiveOptions options
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
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return openStreamingWriter(formatName, target, ArchiveOptions.EMPTY);
    }

    /// Opens an owning streaming writer with options for the named installed archive format.
    ///
    /// After argument validation, this method owns the target and closes it when format lookup or writer setup fails.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target,
            ArchiveOptions options
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
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            OutputStream target
    ) throws IOException {
        return openStreamingWriter(formatName, target, ArchiveOptions.EMPTY);
    }

    /// Opens an owning streaming writer over an output stream with options.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            OutputStream target,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        return openStreamingWriter(
                formatName,
                StreamChannelAdapters.writableChannel(target),
                options
        );
    }

    /// Opens a transactional multi-volume streaming writer for the named installed archive format.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return openStreamingWriter(formatName, target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a transactional multi-volume streaming writer with options.
    ///
    /// Argument and capability validation occur before the selected format can open a target transaction. The writer
    /// controls transaction commit and rollback after setup succeeds.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requirePositiveSplitSize(splitSize);
        return requireVolumeStreamingWriterFormat(formatName)
                .openStreamingWriter(target, splitSize, options);
    }

    /// Opens a detected forward-only format over the logical source.
    private static ArkivoStreamingReader openDetectedArchive(
            ArkivoFormat format,
            ReadableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        if (!(format instanceof ArkivoStreamingReaderFormat readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only reading: " + format.name()
            );
        }
        return readerFormat.openStreamingReader(source, options);
    }

    /// Discovers and opens a conventional path-backed multi-volume reader, or returns `null` when none match.
    private static @Nullable ArkivoStreamingReader openDiscoveredPathVolumeReader(
            Path path,
            ArchiveOptions options
    ) throws IOException {
        for (ArkivoFormat candidate : installed()) {
            if (!(candidate instanceof ArkivoPathVolumeFormat pathFormat)) {
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
                if (!(candidate instanceof ArkivoVolumeStreamingReaderFormat readerFormat)) {
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
            if (!(candidate instanceof ArkivoPathVolumeFormat pathFormat)
                    || !(candidate instanceof ArkivoVolumeFileSystemFormat)) {
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
    private static ArkivoStreamingReaderFormat requireStreamingReaderFormat(String formatName) throws IOException {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IOException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoStreamingReaderFormat readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only reading: " + format.name()
            );
        }
        return readerFormat;
    }

    /// Returns the named installed format after requiring streaming writer support.
    private static ArkivoStreamingWriterFormat requireStreamingWriterFormat(String formatName) throws IOException {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IOException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoStreamingWriterFormat writerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only writing: " + format.name()
            );
        }
        return writerFormat;
    }

    /// Returns the named installed format after requiring file-system support.
    private static ArkivoFileSystemFormat requireFileSystemFormat(String formatName) throws IOException {
        @Nullable ArkivoFormat format = find(formatName);
        if (format == null) {
            throw new IOException("Unknown archive format: " + formatName);
        }
        if (!(format instanceof ArkivoFileSystemFormat fileSystemFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support file-system access: " + format.name()
            );
        }
        return fileSystemFormat;
    }

    /// Returns the named installed format after requiring multi-volume file-system support.
    private static ArkivoVolumeFileSystemFormat requireVolumeFileSystemFormat(
            String formatName
    ) throws IOException {
        ArkivoFileSystemFormat format = requireFileSystemFormat(formatName);
        if (!(format instanceof ArkivoVolumeFileSystemFormat volumeFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume file-system access: " + format.name()
            );
        }
        return volumeFormat;
    }

    /// Returns the named installed format after requiring multi-volume streaming reader support.
    private static ArkivoVolumeStreamingReaderFormat requireVolumeStreamingReaderFormat(
            String formatName
    ) throws IOException {
        ArkivoStreamingReaderFormat format = requireStreamingReaderFormat(formatName);
        if (!(format instanceof ArkivoVolumeStreamingReaderFormat readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support multi-volume forward-only reading: " + format.name()
            );
        }
        return readerFormat;
    }

    /// Returns the named installed format after requiring multi-volume streaming writer support.
    private static ArkivoVolumeStreamingWriterFormat requireVolumeStreamingWriterFormat(
            String formatName
    ) throws IOException {
        ArkivoStreamingWriterFormat format = requireStreamingWriterFormat(formatName);
        if (!(format instanceof ArkivoVolumeStreamingWriterFormat writerFormat)) {
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
    private static ArchiveProbe probeArchive(
            ReadableByteChannel source,
            @Unmodifiable List<ArkivoFormat> formats
    ) throws IOException {
        ByteBuffer prefix = ByteBuffer.allocate(maxProbeSize(formats));
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
                detect(prefix, formats),
                new PrefixReplayReadableByteChannel(prefix, source)
        );
    }

    /// Stores one archive-format probe and its prefix-replaying source.
    ///
    /// @param format detected format, or `null` when no format matched
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

    /// Returns the matching format with the largest requested probe size.
    private static @Nullable ArkivoFormat detect(
            ByteBuffer prefix,
            @Unmodifiable List<ArkivoFormat> formats
    ) {
        @Nullable ArkivoFormat detected = null;
        int detectedProbeSize = -1;
        for (ArkivoFormat format : formats) {
            int probeSize = format.probeSize();
            if (probeSize < 0) {
                throw new IllegalStateException("Archive format probe size must not be negative: " + format.name());
            }
            if (format.matches(prefix.asReadOnlyBuffer())) {
                if (detected == null || probeSize > detectedProbeSize) {
                    detected = format;
                    detectedProbeSize = probeSize;
                }
            }
        }
        return detected;
    }

    /// Returns the largest preferred probe size declared by the installed formats.
    private static int maxProbeSize(@Unmodifiable List<ArkivoFormat> formats) {
        int maximum = 0;
        for (ArkivoFormat format : formats) {
            int size = format.probeSize();
            if (size < 0) {
                throw new IllegalStateException("Archive format probe size must not be negative: " + format.name());
            }
            maximum = Math.max(maximum, size);
        }
        return maximum;
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
}
