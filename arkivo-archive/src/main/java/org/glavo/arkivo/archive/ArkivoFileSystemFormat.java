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
    default String uriScheme() {
        return "arkivo+" + name();
    }

    /// Opens an archive file system from a path.
    default ArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArchiveOptions.EMPTY);
    }

    /// Opens an archive file system from a path with options.
    ///
    /// The default implementation opens one read-only seekable channel. Formats may override this method to support
    /// path-specific storage layouts or write modes.
    default ArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
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
    default ArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a file system from an owned repeatable seekable channel source with options.
    ArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException;

    /// Opens a read-only file system from one owned seekable channel.
    default ArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a file system from one owned seekable channel with options.
    ArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException;
}
