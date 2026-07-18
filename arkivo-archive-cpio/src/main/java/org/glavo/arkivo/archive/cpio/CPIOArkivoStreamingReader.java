// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.internal.CPIOArkivoStreamingReaderImpl;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads CPIO entries from a forward-only stream.
///
/// `next()` recognizes each supported header dialect and positions the reader at the next entry until the required
/// `TRAILER!!!` record. Attributes returned for a cursor position are detached snapshots. The entry body may be opened
/// once; advancing closes it before consuming the next header and its dialect-specific padding. Truncated input or EOF
/// before the trailer fails with `IOException` rather than acting as a normal end of archive.
///
/// A successfully returned reader owns and closes the supplied stream or channel. Closing an entry body does not close
/// the reader. The reader is a stateful cursor; callers must serialize cursor operations.
@NotNullByDefault
public abstract sealed class CPIOArkivoStreamingReader extends ArkivoStreamingReader
        permits CPIOArkivoStreamingReaderImpl {
    /// Creates a streaming CPIO reader base instance.
    protected CPIOArkivoStreamingReader() {
    }

    /// Opens a streaming CPIO reader from an input stream.
    ///
    /// @param source the forward-only archive input; ownership transfers to the returned reader
    /// @return a reader positioned before the first CPIO entry
    public static CPIOArkivoStreamingReader open(InputStream source) {
        return open(source, CPIOArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming CPIO reader from an input stream.
    ///
    /// @param source the forward-only archive input; ownership transfers to the returned reader
    /// @param options the read configuration and limits
    /// @return a reader positioned before the first CPIO entry
    public static CPIOArkivoStreamingReader open(InputStream source, CPIOArchiveOptions.Read options) {
        return new CPIOArkivoStreamingReaderImpl(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(options, "options")
        );
    }

    /// Opens a streaming CPIO reader from a readable channel.
    ///
    /// @param source the forward-only archive channel; ownership transfers to the returned reader
    /// @return a reader positioned before the first CPIO entry
    public static CPIOArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, CPIOArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming CPIO reader from a readable channel.
    ///
    /// @param source the forward-only archive channel; ownership transfers to the returned reader
    /// @param options the read configuration and limits
    /// @return a reader positioned before the first CPIO entry
    public static CPIOArkivoStreamingReader open(
            ReadableByteChannel source,
            CPIOArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        return open(StreamChannelAdapters.inputStream(source), options);
    }
}
