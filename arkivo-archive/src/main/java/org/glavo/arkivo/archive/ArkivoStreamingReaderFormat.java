// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Describes an archive format that can read entries from a forward-only source.
///
/// The configured ReadableByteChannel factory is the implementation contract. Stream and path factories are convenience
/// adapters that open or wrap a channel before dispatching to that contract.
@NotNullByDefault
public interface ArkivoStreamingReaderFormat extends ArkivoFormat {
    /// Opens a streaming reader from a path and takes ownership of the opened channel when successful.
    default ArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return openStreamingReader(path, ArchiveOptions.EMPTY);
    }

    /// Opens a configured streaming reader from a path and takes ownership of the opened channel when successful.
    ///
    /// Formats with conventional multi-volume storage may override this method to discover and open every physical
    /// volume associated with the path.
    default ArkivoStreamingReader openStreamingReader(
            Path path,
            ArchiveOptions options
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
    default ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return openStreamingReader(source, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming reader with options and takes ownership of the input stream when successful.
    default ArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return openStreamingReader(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming reader and takes ownership of the readable channel when successful.
    default ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming reader with options and takes ownership of the channel when successful.
    ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveOptions options
    ) throws IOException;
}
