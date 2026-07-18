// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Describes an archive format that can expose a random-access NIO file system.
///
/// A repeatable source opens independent logical archive views. A directly supplied seekable channel instead defines
/// logical archive offset zero at its current position.
@NotNullByDefault
public interface ArkivoFileSystemFormat extends ArkivoFormat {
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
    /// @param path the path to the archive backing
    /// @param options the read and lifecycle options
    /// @return a new archive file system that owns resources opened for {@code path}
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
    /// @param source the repeatable source whose ownership is transferred to the returned file system
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
    /// @param source the channel whose ownership is transferred to the returned file system
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
    interface Writable extends ArkivoFileSystemFormat {
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
        /// The operation fails rather than replacing an existing archive unless the format documents a stricter rule.
        ///
        /// @param path the path at which to create the archive
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
        /// @param path the existing archive path
        /// @param options the update, publication, read-limit, and lifecycle options
        /// @return a writable file system that publishes a complete replacement on successful close
        /// @throws IOException if the archive cannot be opened for update
        ArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException;
    }
}
