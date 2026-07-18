// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl;
import org.glavo.arkivo.archive.zip.internal.ZipVolumeReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Reads ZIP local file records from a forward-only stream.
///
/// `next()` positions the cursor at the next local file header without requiring the central directory. Attributes are
/// detached snapshots of metadata available from that header. The entry body may be opened once; advancing closes it,
/// validates and consumes any remaining body and data descriptor, and then parses the next local record. After
/// `next()` returns `false`, no current entry exists.
///
/// A successfully returned reader owns the supplied stream, channel, or volume source and every volume channel it
/// opens. Reader close releases them; closing an entry body does not close the reader. The cursor is stateful, and its
/// configured thread-safety strategy does not make a single cursor position independently consumable by multiple
/// callers.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingReader extends ArkivoStreamingReader
        permits ZipArkivoStreamingReaderImpl {
    /// Creates a streaming ZIP reader base instance.
    protected ZipArkivoStreamingReader() {
    }

    /// Opens a streaming ZIP reader from a final archive path and discovers conventional split volumes.
    ///
    /// This call discovers and opens all consecutive physical volumes and snapshots their sizes, so it may block. The
    /// returned cursor is positioned before the first entry.
    ///
    /// @param path the final archive path, conventionally ending in `.zip`
    /// @return a new open streaming reader
    /// @throws NullPointerException if `path` is `null`
    /// @throws IOException if volume discovery or channel setup fails
    public static ZipArkivoStreamingReader open(Path path) throws IOException {
        return open(path, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming ZIP reader from a final archive path and discovers conventional split volumes.
    ///
    /// This call discovers and opens all consecutive physical volumes and snapshots their sizes, so it may block. The
    /// returned cursor is positioned before the first entry.
    ///
    /// @param path the final archive path, conventionally ending in `.zip`
    /// @param options the immutable read configuration
    /// @return a new open streaming reader
    /// @throws NullPointerException if `path` or `options` is `null`
    /// @throws IOException if volume discovery or channel setup fails
    public static ZipArkivoStreamingReader open(
            Path path,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return open(ZipArkivoFormat.instance().openVolumeSource(path), options);
    }

    /// Opens a streaming ZIP reader from a multi-volume source.
    ///
    /// This call opens all consecutive physical volumes and snapshots their sizes, so it may block. A successful return
    /// transfers ownership of the source and opened channels to the reader.
    ///
    /// @param source the finite repeatable volume source
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` is `null`
    /// @throws IOException if volume zero is absent or a volume cannot be opened or inspected
    public static ZipArkivoStreamingReader open(ArkivoVolumeSource source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from a multi-volume source with options.
    ///
    /// The returned reader owns the source and every physical volume channel it opens.
    /// Physical sizes are snapshotted during this call, which may block; later entry reads advance one logical position
    /// across the concatenated volumes.
    ///
    /// @param source the finite repeatable volume source
    /// @param options the immutable read configuration
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` or `options` is `null`
    /// @throws IOException if volume zero is absent or a volume cannot be opened or inspected
    public static ZipArkivoStreamingReader open(
            ArkivoVolumeSource source,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        ZipVolumeReadableByteChannel channel = new ZipVolumeReadableByteChannel(source);
        try {
            return new ZipArkivoStreamingReaderImpl(channel, config);
        } catch (RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error closeException) {
                if (exception != closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }

    /// Opens a streaming ZIP reader from an input stream.
    ///
    /// Reading begins at the stream's current read point. A successful return transfers ownership to the reader; entry
    /// traversal advances the stream and may block according to its implementation.
    ///
    /// @param source the input stream positioned at the first ZIP local record
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` is `null`
    public static ZipArkivoStreamingReader open(InputStream source) {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from an input stream with options.
    ///
    /// Reading begins at the stream's current read point. A successful return transfers ownership to the reader; entry
    /// traversal advances the stream and may block according to its implementation.
    ///
    /// @param source the input stream positioned at the first ZIP local record
    /// @param options the immutable read configuration
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` or `options` is `null`
    public static ZipArkivoStreamingReader open(InputStream source, ZipArchiveOptions.Read options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming ZIP reader from a readable channel.
    ///
    /// No repositioning is attempted. For a seekable channel, reading begins at and advances its current position;
    /// otherwise traversal consumes the channel's current input state. A successful return transfers channel ownership
    /// to the reader, and later reads may block according to the channel implementation.
    ///
    /// @param source the readable channel positioned at the first ZIP local record
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` is `null`
    public static ZipArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from a readable channel with options.
    ///
    /// No repositioning is attempted. For a seekable channel, reading begins at and advances its current position;
    /// otherwise traversal consumes the channel's current input state. A successful return transfers channel ownership
    /// to the reader, and later reads may block according to the channel implementation.
    ///
    /// @param source the readable channel positioned at the first ZIP local record
    /// @param options the immutable read configuration
    /// @return a new open streaming reader positioned before the first entry
    /// @throws NullPointerException if `source` or `options` is `null`
    public static ZipArkivoStreamingReader open(
            ReadableByteChannel source,
            ZipArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        return new ZipArkivoStreamingReaderImpl(source, config);
    }
}
