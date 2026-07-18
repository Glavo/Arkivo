// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/// Describes an archive format supported by Arkivo.
///
/// Implementations are immutable identities safe for concurrent use. Operational capabilities are exposed through
/// nested subinterfaces such as [FileSystem] and [StreamingReader].
@NotNullByDefault
public interface ArkivoFormat {
    /// Returns the stable format name.
    ///
    /// @return the non-blank stable name used for registry lookup
    String name();

    /// Returns alternative stable names accepted for this format.
    ///
    /// @return immutable non-blank aliases used for registry lookup
    default @Unmodifiable List<String> aliases() {
        return List.of();
    }

    /// Returns common file extensions for archives of this format, without leading dots.
    ///
    /// @return immutable extension strings without leading dots
    default @Unmodifiable List<String> fileExtensions() {
        return List.of(name());
    }

    /// Returns the preferred number of leading bytes requested by generic format detection.
    ///
    /// A format may recognize a prefix containing fewer bytes. The returned value must not be negative.
    ///
    /// @return the preferred non-negative probe byte count
    default int probeSize() {
        return 0;
    }

    /// Returns whether the remaining bytes of the given prefix identify this archive format.
    ///
    /// This operation must not change the prefix position, limit, mark, or byte order.
    ///
    /// @param prefix the archive prefix to inspect, from its current position to its limit
    /// @return {@code true} if the available prefix identifies this format
    default boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return false;
    }

    /// Describes an archive format that can read entries from a forward-only source.
    ///
    /// The configured [ReadableByteChannel] factory is the implementation contract. Stream and path factories are
    /// convenience adapters that open or wrap a channel before dispatching to that contract.
    @NotNullByDefault
    interface StreamingReader extends ArkivoFormat {
        /// Opens a streaming reader from a path and takes ownership of the opened channel when successful.
        ///
        /// @param path the archive path
        /// @return a new owning forward-only reader
        /// @throws IOException if the path cannot be opened or the reader cannot be initialized
        default ArkivoStreamingReader openStreamingReader(Path path) throws IOException {
            return openStreamingReader(path, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a configured streaming reader from a path and takes ownership of the opened channel when successful.
        ///
        /// Formats with conventional multi-volume storage may override this method to discover and open every physical
        /// volume associated with the path.
        ///
        /// @param path    the archive path or a format-specific volume-identifying path
        /// @param options the read and lifecycle options
        /// @return a new owning forward-only reader
        /// @throws IOException if archive storage cannot be opened or the reader cannot be initialized
        default ArkivoStreamingReader openStreamingReader(
                Path path,
                ArchiveReadOptions options
        ) throws IOException {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(options, "options");
            ReadableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
            try {
                return openStreamingReader(source, options);
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    source.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    if (exception != closeException) {
                        exception.addSuppressed(closeException);
                    }
                }
                throw exception;
            }
        }

        /// Opens a streaming reader and takes ownership of the input stream when successful.
        ///
        /// @param source the input stream whose ownership is transferred to the returned reader
        /// @return a new owning forward-only reader
        /// @throws IOException if the reader cannot be initialized
        default ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
            return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a streaming reader with options and takes ownership of the input stream when successful.
        ///
        /// @param source  the input stream whose ownership is transferred to the returned reader
        /// @param options the read and lifecycle options
        /// @return a new owning forward-only reader
        /// @throws IOException if the reader cannot be initialized
        default ArkivoStreamingReader openStreamingReader(
                InputStream source,
                ArchiveReadOptions options
        ) throws IOException {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(options, "options");
            return openStreamingReader(StreamChannelAdapters.readableChannel(source), options);
        }

        /// Opens a streaming reader and takes ownership of the readable channel when successful.
        ///
        /// @param source the channel whose ownership is transferred to the returned reader
        /// @return a new owning forward-only reader
        /// @throws IOException if the reader cannot be initialized
        default ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
            return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a streaming reader with options and takes ownership of the channel when successful.
        ///
        /// @param source  the channel whose ownership is transferred to the returned reader
        /// @param options the read and lifecycle options
        /// @return a new owning forward-only reader
        /// @throws IOException if the reader cannot be initialized
        ArkivoStreamingReader openStreamingReader(
                ReadableByteChannel source,
                ArchiveReadOptions options
        ) throws IOException;
    }

    /// Describes an archive format that can stream entries from a multi-volume source.
    @NotNullByDefault
    interface VolumeStreamingReader extends StreamingReader {
        /// Opens a multi-volume streaming reader with default options.
        ///
        /// @param source the volume source whose ownership is transferred to the returned reader
        /// @return a new owning multi-volume forward-only reader
        /// @throws IOException if the archive or reader cannot be opened
        default ArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
            return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a configured multi-volume streaming reader and takes ownership of the source when successful.
        ///
        /// The reader closes all channels it opens and the volume source itself. A format may process physical volume
        /// boundaries rather than treating the volumes as byte-for-byte concatenated storage.
        ///
        /// @param source  the volume source whose ownership is transferred to the returned reader
        /// @param options the read and lifecycle options
        /// @return a new owning multi-volume forward-only reader
        /// @throws IOException if the archive or reader cannot be opened
        ArkivoStreamingReader openStreamingReader(
                ArkivoVolumeSource source,
                ArchiveReadOptions options
        ) throws IOException;
    }

    /// Describes an archive format that can write entries to a forward-only target.
    ///
    /// The configured [WritableByteChannel] factory is the implementation contract. Stream factories are convenience
    /// adapters that wrap a channel before dispatching to that contract.
    @NotNullByDefault
    interface StreamingWriter extends ArkivoFormat {
        /// Opens a streaming writer and takes ownership of the output stream when successful.
        ///
        /// @param target the output stream whose ownership is transferred to the returned writer
        /// @return a new owning forward-only writer
        /// @throws IOException if the writer cannot be initialized
        default ArkivoStreamingWriter openStreamingWriter(OutputStream target) throws IOException {
            return openStreamingWriter(target, ArchiveCreateOptions.DEFAULT);
        }

        /// Opens a streaming writer with options and takes ownership of the output stream when successful.
        ///
        /// @param target  the output stream whose ownership is transferred to the returned writer
        /// @param options the archive creation options
        /// @return a new owning forward-only writer
        /// @throws IOException if the writer cannot be initialized
        default ArkivoStreamingWriter openStreamingWriter(
                OutputStream target,
                ArchiveCreateOptions options
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(options, "options");
            return openStreamingWriter(StreamChannelAdapters.writableChannel(target), options);
        }

        /// Opens a streaming writer and takes ownership of the writable channel when successful.
        ///
        /// @param target the channel whose ownership is transferred to the returned writer
        /// @return a new owning forward-only writer
        /// @throws IOException if the writer cannot be initialized
        default ArkivoStreamingWriter openStreamingWriter(WritableByteChannel target) throws IOException {
            return openStreamingWriter(target, ArchiveCreateOptions.DEFAULT);
        }

        /// Opens a streaming writer with options and takes ownership of the channel when successful.
        ///
        /// @param target  the channel whose ownership is transferred to the returned writer
        /// @param options the archive creation options
        /// @return a new owning forward-only writer
        /// @throws IOException if the writer cannot be initialized
        ArkivoStreamingWriter openStreamingWriter(
                WritableByteChannel target,
                ArchiveCreateOptions options
        ) throws IOException;
    }

    /// Describes an archive format that can stream entries to transactional multi-volume output.
    @NotNullByDefault
    interface VolumeStreamingWriter extends StreamingWriter {
        /// Opens a multi-volume streaming writer with the requested maximum physical volume size.
        ///
        /// @param target    the transactional destination for the output volumes
        /// @param splitSize the positive maximum physical volume size in bytes
        /// @return a new transactional multi-volume writer
        /// @throws IOException              if the output transaction or writer cannot be opened
        /// @throws IllegalArgumentException if `splitSize` is not positive
        default ArkivoStreamingWriter openStreamingWriter(
                ArkivoVolumeTarget target,
                long splitSize
        ) throws IOException {
            return openStreamingWriter(target, splitSize, ArchiveCreateOptions.DEFAULT);
        }

        /// Opens a configured multi-volume streaming writer with the requested maximum physical volume size.
        ///
        /// A successful writer owns the output transaction opened from the target. Closing the writer commits the final
        /// archive; setup or finalization failure rolls back unpublished output.
        ///
        /// @param target    the transactional destination for the output volumes
        /// @param splitSize the positive maximum physical volume size in bytes
        /// @param options   the archive creation options
        /// @return a new transactional multi-volume writer
        /// @throws IOException              if the output transaction or writer cannot be opened
        /// @throws IllegalArgumentException if `splitSize` is not positive
        ArkivoStreamingWriter openStreamingWriter(
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveCreateOptions options
        ) throws IOException;
    }

    /// Describes an archive format that can expose a random-access NIO file system.
    ///
    /// A repeatable source opens independent logical archive views. A directly supplied seekable channel instead
    /// defines logical archive offset zero at its current position.
    @NotNullByDefault
    interface FileSystem extends ArkivoFormat {
        /// Returns the URI scheme used by the installed NIO file-system provider.
        ///
        /// @return the format-specific URI scheme
        default String uriScheme() {
            return "arkivo+" + name();
        }

        /// Opens an archive file system from a path.
        ///
        /// @param path the path to the archive backing
        /// @return a new read-only archive file system
        /// @throws IOException if the archive cannot be opened or decoded
        default ArkivoFileSystem open(Path path) throws IOException {
            return open(path, ArchiveReadOptions.DEFAULT);
        }

        /// Opens an archive file system from a path with options.
        ///
        /// The default implementation opens one read-only seekable channel. Formats may override this method to support
        /// path-specific storage layouts or write modes.
        ///
        /// @param path    the path to the archive backing
        /// @param options the read and lifecycle options
        /// @return a new archive file system that owns resources opened for `path`
        /// @throws IOException if the archive cannot be opened or decoded
        default ArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(options, "options");
            SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
            try {
                return open(source, options);
            } catch (IOException | RuntimeException | Error exception) {
                if (source.isOpen()) {
                    try {
                        source.close();
                    } catch (IOException | RuntimeException | Error closeException) {
                        if (exception != closeException) {
                            exception.addSuppressed(closeException);
                        }
                    }
                }
                throw exception;
            }
        }

        /// Opens a read-only file system from an owned repeatable seekable channel source.
        ///
        /// @param source the repeatable source whose ownership is transferred to the returned file system
        /// @return a new read-only archive file system
        /// @throws IOException if the archive cannot be opened or decoded
        default ArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
            return open(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a file system from an owned repeatable seekable channel source with options.
        ///
        /// @param source  the repeatable source whose ownership is transferred to the returned file system
        /// @param options the read and lifecycle options
        /// @return a new archive file system
        /// @throws IOException if the archive cannot be opened or decoded
        ArkivoFileSystem open(
                ArkivoSeekableChannelSource source,
                ArchiveReadOptions options
        ) throws IOException;

        /// Opens a read-only file system from one owned seekable channel.
        ///
        /// @param source the channel whose ownership is transferred to the returned file system
        /// @return a new read-only archive file system whose logical offset zero is the channel's initial position
        /// @throws IOException if the archive cannot be opened or decoded
        default ArkivoFileSystem open(SeekableByteChannel source) throws IOException {
            return open(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a file system from one owned seekable channel with options.
        ///
        /// @param source  the channel whose ownership is transferred to the returned file system
        /// @param options the read and lifecycle options
        /// @return a new archive file system whose logical offset zero is the channel's initial position
        /// @throws IOException if the archive cannot be opened or decoded
        ArkivoFileSystem open(
                SeekableByteChannel source,
                ArchiveReadOptions options
        ) throws IOException;

        /// Describes a format that supports path-backed creation and complete-rewrite updates.
        ///
        /// This capability is independent of multi-volume support. Read-only formats implement only the enclosing
        /// interface, while writable formats opt into this subinterface.
        @NotNullByDefault
        interface Writable extends FileSystem {
            /// Creates a new path-backed archive file system with default options.
            ///
            /// @param path the path at which to create the archive
            /// @return a new writable archive file system
            /// @throws IOException if the archive backing cannot be created
            default ArkivoFileSystem create(Path path) throws IOException {
                return create(path, ArchiveCreateOptions.DEFAULT);
            }

            /// Creates a new path-backed archive file system with options.
            ///
            /// The operation fails rather than replacing an existing archive unless the format documents a stricter
            /// rule.
            ///
            /// @param path    the path at which to create the archive
            /// @param options the creation and lifecycle options
            /// @return a new writable archive file system
            /// @throws IOException if the archive backing cannot be created
            ArkivoFileSystem create(Path path, ArchiveCreateOptions options) throws IOException;

            /// Opens a complete-rewrite update of an existing path-backed archive with default options.
            ///
            /// @param path the existing archive path
            /// @return a writable file system that publishes a complete replacement on successful close
            /// @throws IOException if the archive cannot be opened for update
            default ArkivoFileSystem update(Path path) throws IOException {
                return update(path, ArchiveUpdateOptions.DEFAULT);
            }

            /// Opens a complete-rewrite update of an existing path-backed archive with options.
            ///
            /// The returned file system publishes changes transactionally when it closes successfully.
            ///
            /// @param path    the existing archive path
            /// @param options the update, publication, read-limit, and lifecycle options
            /// @return a writable file system that publishes a complete replacement on successful close
            /// @throws IOException if the archive cannot be opened for update
            ArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException;
        }
    }

    /// Describes an archive format that can expose multiple physical volumes as one file system.
    @NotNullByDefault
    interface VolumeFileSystem extends FileSystem {
        /// Opens a read-only file system from an owned volume source.
        ///
        /// @param source the volume source whose ownership is transferred to the returned file system
        /// @return a new read-only multi-volume archive file system
        /// @throws IOException if the archive cannot be opened or decoded
        default ArkivoFileSystem open(ArkivoVolumeSource source) throws IOException {
            return open(source, ArchiveReadOptions.DEFAULT);
        }

        /// Opens a file system from an owned volume source with options.
        ///
        /// @param source  the volume source whose ownership is transferred to the returned file system
        /// @param options the read and lifecycle options
        /// @return a new multi-volume archive file system
        /// @throws IOException if the archive cannot be opened or decoded
        ArkivoFileSystem open(
                ArkivoVolumeSource source,
                ArchiveReadOptions options
        ) throws IOException;

        /// Describes a format that supports multi-volume creation and complete-rewrite updates.
        @NotNullByDefault
        interface Writable extends VolumeFileSystem {
            /// Opens a complete-rewrite update from an owned volume source to a transactional volume target.
            ///
            /// @param source    the volume source whose ownership is transferred to the returned file system
            /// @param target    the transactional destination for replacement volumes
            /// @param splitSize the positive maximum output volume size in bytes
            /// @return a writable archive file system that publishes replacement volumes on successful close
            /// @throws IOException              if the source or output transaction cannot be opened
            /// @throws IllegalArgumentException if `splitSize` is not positive
            default ArkivoFileSystem update(
                    ArkivoVolumeSource source,
                    ArkivoVolumeTarget target,
                    long splitSize
            ) throws IOException {
                return update(source, target, splitSize, ArchiveUpdateOptions.DEFAULT);
            }

            /// Opens a complete-rewrite update with options.
            ///
            /// The format owns the source after successful setup and publishes through the target on close.
            ///
            /// @param source    the volume source whose ownership is transferred to the returned file system
            /// @param target    the transactional destination for replacement volumes
            /// @param splitSize the positive maximum output volume size in bytes
            /// @param options   the update, publication, read-limit, and lifecycle options
            /// @return a writable archive file system that publishes replacement volumes on successful close
            /// @throws IOException              if the source or output transaction cannot be opened
            /// @throws IllegalArgumentException if `splitSize` is not positive
            ArkivoFileSystem update(
                    ArkivoVolumeSource source,
                    ArkivoVolumeTarget target,
                    long splitSize,
                    ArchiveUpdateOptions options
            ) throws IOException;

            /// Creates a writable file system over a transactional volume target.
            ///
            /// @param target    the transactional destination for the new archive volumes
            /// @param splitSize the positive maximum output volume size in bytes
            /// @return a new writable multi-volume archive file system
            /// @throws IOException              if the output transaction cannot be opened
            /// @throws IllegalArgumentException if `splitSize` is not positive
            default ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
                return create(target, splitSize, ArchiveCreateOptions.DEFAULT);
            }

            /// Creates a writable file system over a transactional volume target with options.
            ///
            /// @param target    the transactional destination for the new archive volumes
            /// @param splitSize the positive maximum output volume size in bytes
            /// @param options   the creation and lifecycle options
            /// @return a new writable multi-volume archive file system
            /// @throws IOException              if the output transaction cannot be opened
            /// @throws IllegalArgumentException if `splitSize` is not positive
            ArkivoFileSystem create(
                    ArkivoVolumeTarget target,
                    long splitSize,
                    ArchiveCreateOptions options
            ) throws IOException;
        }
    }

    /// Describes an archive format that can discover conventional multi-volume storage from a path.
    ///
    /// A format defines which path identifies the archive layout. For example, ZIP conventionally uses the final `.zip`
    /// path, while numbered 7z and modern RAR layouts use their first physical volume.
    @NotNullByDefault
    interface PathVolume extends ArkivoFormat {
        /// Discovers the ordered physical paths of a conventional multi-volume archive.
        ///
        /// The returned list starts with logical volume zero, is immutable, and contains at least two paths. This method
        /// returns `null` when the path does not identify a recognized multi-volume layout.
        ///
        /// @param path the candidate archive path
        /// @return ordered physical volume paths, or `null` if `path` does not identify a split layout
        /// @throws IOException if the surrounding storage cannot be inspected
        @Nullable
        @Unmodifiable
        List<Path> discoverVolumePaths(Path path) throws IOException;

        /// Opens a path-backed volume source, using one physical volume when no split layout is discovered.
        ///
        /// @param path the archive path or one member recognized by this format's volume convention
        /// @return a new source over the discovered volumes, or over `path` alone when no split layout is found
        /// @throws IOException           if volume discovery fails
        /// @throws IllegalStateException if discovery returns fewer than two paths
        default ArkivoVolumeSource openVolumeSource(Path path) throws IOException {
            Objects.requireNonNull(path, "path");
            @Nullable @Unmodifiable List<Path> volumePaths = discoverVolumePaths(path);
            if (volumePaths == null) {
                return ArkivoVolumeSource.of(List.of(path));
            }
            if (volumePaths.size() < 2) {
                throw new IllegalStateException("Discovered multi-volume paths must contain at least two paths");
            }
            return ArkivoVolumeSource.of(volumePaths);
        }
    }
}
