// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Describes an archive format that can write entries to a forward-only target.
///
/// The configured WritableByteChannel factory is the implementation contract. Stream factories are convenience adapters
/// that wrap a channel before dispatching to that contract.
@NotNullByDefault
public interface ArkivoStreamingWriterFormat extends ArkivoFormat {
    /// Opens a streaming writer and takes ownership of the output stream when successful.
    default ArkivoStreamingWriter openStreamingWriter(OutputStream target) throws IOException {
        return openStreamingWriter(target, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming writer with options and takes ownership of the output stream when successful.
    default ArkivoStreamingWriter openStreamingWriter(
            OutputStream target,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        return openStreamingWriter(StreamChannelAdapters.writableChannel(target), options);
    }

    /// Opens a streaming writer and takes ownership of the writable channel when successful.
    default ArkivoStreamingWriter openStreamingWriter(WritableByteChannel target) throws IOException {
        return openStreamingWriter(target, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming writer with options and takes ownership of the channel when successful.
    ArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel target,
            ArchiveOptions options
    ) throws IOException;
}
