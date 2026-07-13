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
import java.util.Map;
import java.util.Objects;

/// Describes an archive format that can read entries from a forward-only source.
///
/// The configured ReadableByteChannel factory is the implementation contract. Stream and path factories are convenience
/// adapters that open or wrap a channel before dispatching to that contract.
@NotNullByDefault
public interface ArkivoStreamingReaderFormat extends ArkivoFormat {
    /// Opens a streaming reader from a path and takes ownership of the opened channel when successful.
    default ArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return openStreamingReader(path, Map.of());
    }

    /// Opens a configured streaming reader from a path and takes ownership of the opened channel when successful.
    ///
    /// Formats with conventional multi-volume storage may override this method to discover and open every physical
    /// volume associated with the path.
    default ArkivoStreamingReader openStreamingReader(
            Path path,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ReadableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            return openStreamingReader(source, environment);
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
        return openStreamingReader(source, Map.of());
    }

    /// Opens a streaming reader with environment options and takes ownership of the input stream when successful.
    default ArkivoStreamingReader openStreamingReader(
            InputStream source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return openStreamingReader(StreamChannelAdapters.readableChannel(source), environment);
    }

    /// Opens a streaming reader and takes ownership of the readable channel when successful.
    default ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, Map.of());
    }

    /// Opens a streaming reader with environment options and takes ownership of the channel when successful.
    ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException;
}
