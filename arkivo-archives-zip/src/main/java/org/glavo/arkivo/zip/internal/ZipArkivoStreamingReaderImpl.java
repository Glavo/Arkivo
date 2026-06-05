// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoStreamingEntry;
import org.glavo.arkivo.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The internal entry stream, or `null` until the first entry is requested.
    private @Nullable ArkivoFileSystemEntryStream entries;

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

    /// Returns the next entry, or `null` when the ZIP stream is exhausted.
    @Override
    public synchronized @Nullable ZipArkivoStreamingEntry next() throws IOException {
        ArkivoFileSystemEntryStream entryStream = entryStream();
        Path path = entryStream.next();
        if (path == null) {
            return null;
        }

        String pathText = fileSystem.currentEntryPathText();
        if (pathText == null) {
            throw new IOException("ZIP streaming reader did not expose the current entry");
        }
        boolean directory = fileSystem.currentEntryDirectory();
        String normalizedText = directory ? pathText.substring(0, pathText.length() - 1) : pathText;
        return new ZipArkivoStreamingEntryImpl(Path.of(normalizedText), pathText, directory);
    }

    /// Opens a readable channel for the current entry.
    @Override
    public synchronized ReadableByteChannel openChannel() throws IOException {
        return entryStream().openChannel();
    }

    /// Closes this streaming reader.
    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        ArkivoFileSystemEntryStream entryStream = entries;
        try {
            if (entryStream != null) {
                entryStream.close();
            }
        } finally {
            fileSystem.close();
        }
    }

    /// Returns the internal entry stream.
    private ArkivoFileSystemEntryStream entryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        ArkivoFileSystemEntryStream entryStream = entries;
        if (entryStream == null) {
            entryStream = fileSystem.openEntryStream();
            entries = entryStream;
        }
        return entryStream;
    }
}
