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
    ///
    /// @param target the output stream whose ownership is transferred to the returned writer
    /// @return a new owning forward-only writer
    /// @throws IOException if the writer cannot be initialized
    default ArkivoStreamingWriter openStreamingWriter(OutputStream target) throws IOException {
        return openStreamingWriter(target, ArchiveCreateOptions.DEFAULT);
    }

    /// Opens a streaming writer with options and takes ownership of the output stream when successful.
    ///
    /// @param target the output stream whose ownership is transferred to the returned writer
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
    /// @param target the channel whose ownership is transferred to the returned writer
    /// @param options the archive creation options
    /// @return a new owning forward-only writer
    /// @throws IOException if the writer cannot be initialized
    ArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel target,
            ArchiveCreateOptions options
    ) throws IOException;
}
