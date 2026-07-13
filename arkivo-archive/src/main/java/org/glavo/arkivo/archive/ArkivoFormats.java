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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/// Discovers archive formats provided by Arkivo format modules.
@NotNullByDefault
public final class ArkivoFormats {
    /// Creates no instances.
    private ArkivoFormats() {
    }

    /// Returns all archive formats visible to the current class loader.
    public static @Unmodifiable List<ArkivoFormat> installed() {
        ArrayList<ArkivoFormat> formats = new ArrayList<>();
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            formats.add(format);
        }
        return List.copyOf(formats);
    }

    /// Returns the first archive format with the given stable name or alias, ignoring ASCII case.
    public static @Nullable ArkivoFormat find(String name) {
        Objects.requireNonNull(name, "name");
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
            for (String alias : format.aliases()) {
                if (alias.equalsIgnoreCase(name)) {
                    return format;
                }
            }
        }
        return null;
    }

    /// Returns the matching installed archive format with the largest requested probe size.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable ArkivoFormat detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return detect(prefix, installed());
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
        return openFileSystem(source, Map.of());
    }

    /// Detects and opens a read-only archive file system from one owned seekable channel with environment options.
    ///
    /// After argument validation, this method takes ownership of the channel and closes it when detection or setup fails.
    public static ArkivoFileSystem openFileSystem(
            SeekableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
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
            return fileSystemFormat.open(source, environment);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Detects and opens an archive file system from a path.
    public static ArkivoFileSystem openFileSystem(Path path) throws IOException {
        return openFileSystem(path, Map.of());
    }

    /// Detects and opens an archive file system from a path with environment options.
    public static ArkivoFileSystem openFileSystem(
            Path path,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        @Nullable ArkivoFormat format = detect(path);
        if (format == null) {
            throw new IOException("Unrecognized archive format");
        }
        if (!(format instanceof ArkivoFileSystemFormat fileSystemFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support file-system access: " + format.name()
            );
        }
        return fileSystemFormat.open(path, environment);
    }

    /// Opens an owning archive file system for the named installed format from one seekable channel.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source
    ) throws IOException {
        return openFileSystem(formatName, source, Map.of());
    }

    /// Opens an owning archive file system with environment options for the named installed format.
    ///
    /// After argument validation, this method owns the source and closes it when format lookup or setup fails.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            SeekableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        try {
            ArkivoFileSystemFormat format = requireFileSystemFormat(formatName);
            return format.open(source, environment);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Opens an archive file system for the named installed format from a path.
    public static ArkivoFileSystem openFileSystem(String formatName, Path path) throws IOException {
        return openFileSystem(formatName, path, Map.of());
    }

    /// Opens an archive file system with environment options for the named installed format from a path.
    ///
    /// This method bypasses signature detection and delegates path storage handling to the selected format.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            Path path,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return requireFileSystemFormat(formatName).open(path, environment);
    }

    /// Detects and opens a read-only archive file system from an owned repeatable seekable source.
    public static ArkivoFileSystem openFileSystem(ArkivoSeekableChannelSource source) throws IOException {
        return openFileSystem(source, Map.of());
    }

    /// Detects and opens a read-only archive file system from an owned repeatable seekable source with options.
    ///
    /// After argument validation, this method owns the source and closes it when detection or setup fails.
    public static ArkivoFileSystem openFileSystem(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
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
            return fileSystemFormat.open(owned, environment);
        } catch (IOException | RuntimeException | Error exception) {
            closeOwnedSourceAfterFailedOpen(owned, exception);
            throw exception;
        }
    }

    /// Detects and opens a read-only archive file system from an owned volume source.
    public static ArkivoFileSystem openFileSystem(ArkivoVolumeSource source) throws IOException {
        return openFileSystem(source, Map.of());
    }

    /// Detects and opens a read-only archive file system from an owned volume source with environment options.
    ///
    /// Detection uses volume zero. After argument validation, this method owns the source and closes it when detection,
    /// capability validation, or setup fails.
    public static ArkivoFileSystem openFileSystem(
            ArkivoVolumeSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
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
            return volumeFormat.open(owned, environment);
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
        return openFileSystem(formatName, source, Map.of());
    }

    /// Opens a read-only archive file system with options for the named format from a repeatable seekable source.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        OwnedArchiveSources.OwnedSeekableSource owned = OwnedArchiveSources.own(source);
        try {
            return requireFileSystemFormat(formatName).open(owned, environment);
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
        return openFileSystem(formatName, source, Map.of());
    }

    /// Opens a read-only multi-volume file system with options for the named format from an owned volume source.
    public static ArkivoFileSystem openFileSystem(
            String formatName,
            ArkivoVolumeSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        OwnedArchiveSources.OwnedVolumeSource owned = OwnedArchiveSources.own(source);
        try {
            return requireVolumeFileSystemFormat(formatName).open(owned, environment);
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
        return openStreamingReader(source, Map.of());
    }

    /// Detects and opens an archive from a forward-only input stream with environment options.
    public static ArkivoStreamingReader openStreamingReader(
            InputStream source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return openStreamingReader(StreamChannelAdapters.readableChannel(source), environment);
    }

    /// Detects and opens an archive from a forward-only readable channel.
    public static ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, Map.of());
    }

    /// Detects and opens an archive from a forward-only readable channel with environment options.
    ///
    /// Raw archive recognition runs before optional source providers, so a valid archive always wins over a coincidental
    /// outer-wrapper signature. After argument validation, this method owns and closes the source on setup failure.
    public static ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        ReadableByteChannel current = source;
        try {
            @Unmodifiable List<ArkivoFormat> formats = installed();
            ArchiveProbe rawProbe = probeArchive(current, formats);
            current = rawProbe.channel();
            @Nullable ArkivoFormat format = rawProbe.format();
            if (format != null) {
                return openDetectedArchive(format, current, environment);
            }

            for (ArkivoStreamingSourceProvider provider : ServiceLoader.load(
                    ArkivoStreamingSourceProvider.class
            )) {
                ArkivoStreamingSource transformed = provider.probe(current, environment);
                current = transformed.channel();
                if (!transformed.transformed()) {
                    continue;
                }

                ArchiveProbe transformedProbe = probeArchive(current, formats);
                current = transformedProbe.channel();
                format = transformedProbe.format();
                if (format != null) {
                    return openDetectedArchive(format, current, environment);
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
        return openStreamingReader(formatName, source, Map.of());
    }

    /// Opens an owning streaming reader with environment options for the named installed archive format.
    ///
    /// This method bypasses signature detection and outer source providers. After argument validation, it owns the
    /// source and closes it when format lookup or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        try {
            @Nullable ArkivoFormat format = find(formatName);
            if (format == null) {
                throw new IOException("Unknown archive format: " + formatName);
            }
            if (!(format instanceof ArkivoStreamingReaderFormat readerFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support forward-only reading: " + format.name()
                );
            }
            return readerFormat.openStreamingReader(source, environment);
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
        return openStreamingReader(formatName, source, Map.of());
    }

    /// Opens an owning streaming reader over an input stream with environment options.
    public static ArkivoStreamingReader openStreamingReader(
            String formatName,
            InputStream source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        try {
            @Nullable ArkivoFormat format = find(formatName);
            if (format == null) {
                throw new IOException("Unknown archive format: " + formatName);
            }
            if (!(format instanceof ArkivoStreamingReaderFormat readerFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support forward-only reading: " + format.name()
                );
            }
            return readerFormat.openStreamingReader(source, environment);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailedOpen(source, exception);
            throw exception;
        }
    }

    /// Opens an owning streaming writer for the named installed archive format.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return openStreamingWriter(formatName, target, Map.of());
    }

    /// Opens an owning streaming writer with environment options for the named installed archive format.
    ///
    /// After argument validation, this method owns the target and closes it when format lookup or writer setup fails.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            WritableByteChannel target,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");
        try {
            @Nullable ArkivoFormat format = find(formatName);
            if (format == null) {
                throw new IOException("Unknown archive format: " + formatName);
            }
            if (!(format instanceof ArkivoStreamingWriterFormat writerFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support forward-only writing: " + format.name()
                );
            }
            return writerFormat.openStreamingWriter(target, environment);
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
        return openStreamingWriter(formatName, target, Map.of());
    }

    /// Opens an owning streaming writer over an output stream with environment options.
    public static ArkivoStreamingWriter openStreamingWriter(
            String formatName,
            OutputStream target,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        return openStreamingWriter(
                formatName,
                StreamChannelAdapters.writableChannel(target),
                environment
        );
    }

    /// Opens a detected forward-only format over the logical source.
    private static ArkivoStreamingReader openDetectedArchive(
            ArkivoFormat format,
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        if (!(format instanceof ArkivoStreamingReaderFormat readerFormat)) {
            throw new UnsupportedOperationException(
                    "Archive format does not support forward-only reading: " + format.name()
            );
        }
        return readerFormat.openStreamingReader(source, environment);
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
