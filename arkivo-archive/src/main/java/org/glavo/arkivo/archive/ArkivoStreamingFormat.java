// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Describes an archive format that can read entries from a forward-only source.
@NotNullByDefault
public interface ArkivoStreamingFormat extends ArkivoFormat {
    /// Opens a streaming reader and takes ownership of the input stream when successful.
    default ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return openStreamingReader(source, Map.of());
    }

    /// Opens a streaming reader with environment options and takes ownership of the input stream when successful.
    ArkivoStreamingReader openStreamingReader(
            InputStream source,
            Map<String, ?> environment
    ) throws IOException;

    /// Opens a streaming reader and takes ownership of the readable channel when successful.
    default ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        Objects.requireNonNull(source, "source");
        return openStreamingReader(Channels.newInputStream(source));
    }

    /// Opens a streaming reader with environment options and takes ownership of the channel when successful.
    default ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return openStreamingReader(Channels.newInputStream(source), environment);
    }
}
