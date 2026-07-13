// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/// Describes an archive format that can expose a random-access NIO file system.
@NotNullByDefault
public interface ArkivoFileSystemFormat extends ArkivoFormat {
    /// Opens an archive file system from a path.
    default ArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens an archive file system from a path with environment options.
    ///
    /// The default implementation opens one read-only seekable channel. Formats may override this method to support
    /// path-specific storage layouts or write modes.
    default ArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            return open(source, environment);
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
        return open(source, Map.of());
    }

    /// Opens a read-only file system from an owned repeatable seekable channel source with environment options.
    ArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException;

    /// Opens a read-only file system from one owned seekable channel.
    default ArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a read-only file system from one owned seekable channel with environment options.
    ArkivoFileSystem open(
            SeekableByteChannel source,
            Map<String, ?> environment
    ) throws IOException;
}
