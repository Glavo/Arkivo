// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingEntryStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Implements a ZIP streaming entry stream.
@NotNullByDefault
public final class ZipArkivoStreamingEntryStreamImpl implements ZipArkivoStreamingEntryStream {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The internal entry stream.
    private final ArkivoFileSystemEntryStream entries;

    /// Whether this entry stream is open.
    private boolean open = true;

    /// Creates a ZIP streaming entry stream.
    ZipArkivoStreamingEntryStreamImpl(
            StreamingZipArkivoReadFileSystemImpl fileSystem,
            ArkivoFileSystemEntryStream entries
    ) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    /// Returns the next ZIP entry attributes, or `null` when no entries remain.
    @Override
    public synchronized @Nullable ZipArkivoEntryAttributes next() throws IOException {
        ensureOpen();
        if (entries.next() == null) {
            return null;
        }
        ZipArkivoEntryAttributes attributes = fileSystem.currentEntryAttributes();
        if (attributes == null) {
            throw new IOException("ZIP streaming reader did not expose the current entry");
        }
        return attributes;
    }

    /// Opens a readable channel for the current file entry.
    @Override
    public synchronized ReadableByteChannel openChannel() throws IOException {
        ensureOpen();
        return entries.openChannel();
    }

    /// Closes this streaming entry stream.
    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        entries.close();
    }

    /// Requires this entry stream to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming entry stream is closed");
        }
    }
}
