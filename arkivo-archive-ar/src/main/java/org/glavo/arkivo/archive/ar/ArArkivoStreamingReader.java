// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.internal.ArArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads logical AR members from a forward-only stream.
///
/// `next()` skips archive symbol tables, resolves GNU filename-table references and BSD extended names, and positions
/// the reader at the next logical member. Attributes returned for that position are detached snapshots. The member body
/// may be opened once; advancing closes it before consuming the next header. After `next()` returns `false`, no current
/// member exists.
///
/// A successfully returned reader owns the supplied stream or channel and closes it when the reader closes. Closing an
/// entry body does not close the reader. The reader is a stateful cursor; callers must serialize cursor operations.
@NotNullByDefault
public abstract sealed class ArArkivoStreamingReader extends ArkivoStreamingReader
        permits ArArkivoStreamingReaderImpl {
    /// Creates a streaming AR reader base instance.
    protected ArArkivoStreamingReader() {
    }

    /// Opens a streaming AR reader from an input stream.
    ///
    /// @param source the forward-only archive input; ownership transfers to the returned reader
    /// @return a reader positioned before the first logical member
    public static ArArkivoStreamingReader open(InputStream source) {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming AR reader from an input stream with common archive read options.
    ///
    /// @param source the forward-only archive input; ownership transfers to the returned reader
    /// @param options the read configuration
    /// @return a reader positioned before the first logical member
    public static ArArkivoStreamingReader open(InputStream source, ArArchiveOptions.Read options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming AR reader from a readable channel.
    ///
    /// @param source the forward-only archive channel; ownership transfers to the returned reader
    /// @return a reader positioned before the first logical member
    public static ArArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, ArArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming AR reader from a readable channel with common archive read options.
    ///
    /// @param source the forward-only archive channel; ownership transfers to the returned reader
    /// @param options the read configuration
    /// @return a reader positioned before the first logical member
    public static ArArkivoStreamingReader open(ReadableByteChannel source, ArArchiveOptions.Read options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new ArArkivoStreamingReaderImpl(
                StreamChannelAdapters.inputStream(source),
                ArArkivoFileSystem.toLegacyOptions(options)
        );
    }
}
