// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingEntryStream;
import org.glavo.arkivo.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.util.Iterator;
import java.util.Objects;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The public entry stream, or `null` until it is opened.
    private @Nullable ZipArkivoStreamingEntryStreamImpl entries;

    /// Whether this reader is open.
    private boolean open = true;

    /// Creates a ZIP streaming reader.
    public ZipArkivoStreamingReaderImpl(ReadableByteChannel source, ZipArkivoFileSystemConfig config) {
        this.fileSystem = new StreamingZipArkivoReadFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(config, "config")
        );
    }

    /// Creates a ZIP streaming reader.
    public ZipArkivoStreamingReaderImpl(InputStream source, ZipArkivoFileSystemConfig config) {
        this.fileSystem = new StreamingZipArkivoReadFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens the single entry stream for this reader.
    @Override
    public synchronized ZipArkivoStreamingEntryStream openEntryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        if (entries != null) {
            throw new IllegalStateException("ZIP streaming entry stream has already been opened");
        }
        ZipArkivoStreamingEntryStreamImpl entryStream =
                new ZipArkivoStreamingEntryStreamImpl(fileSystem, fileSystem.openEntryStream());
        entries = entryStream;
        return entryStream;
    }

    /// Opens a readable channel for the current file entry.
    @Override
    public synchronized ReadableByteChannel openChannel() throws IOException {
        return entryStream().openChannel();
    }

    /// Returns the single iterator over ZIP entry attributes.
    @Override
    public synchronized Iterator<ZipArkivoEntryAttributes> iterator() {
        try {
            return openEntryStream().iterator();
        } catch (IOException exception) {
            throw new DirectoryIteratorException(exception);
        }
    }

    /// Closes this streaming reader.
    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        ZipArkivoStreamingEntryStreamImpl entryStream = entries;
        try {
            if (entryStream != null) {
                entryStream.close();
            }
        } finally {
            fileSystem.close();
        }
    }

    /// Returns the opened entry stream.
    private ZipArkivoStreamingEntryStreamImpl entryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        ZipArkivoStreamingEntryStreamImpl entryStream = entries;
        if (entryStream == null) {
            throw new IOException("ZIP streaming entry stream has not been opened");
        }
        return entryStream;
    }
}
