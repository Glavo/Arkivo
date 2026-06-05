// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The internal entry stream, or `null` until iteration starts.
    private @Nullable ArkivoFileSystemEntryStream entries;

    /// Whether an iterator has already been created.
    private boolean iteratorCreated;

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

    /// Opens a readable channel for the current file entry.
    @Override
    public synchronized ReadableByteChannel openChannel() throws IOException {
        return entryStream().openChannel();
    }

    /// Returns the single iterator over ZIP entry attributes.
    @Override
    public synchronized Iterator<ZipArkivoEntryAttributes> iterator() {
        ensureOpenUnchecked();
        if (iteratorCreated) {
            throw new IllegalStateException("ZIP streaming reader iterator has already been created");
        }
        iteratorCreated = true;
        return new EntryIterator();
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

    /// Returns the opened entry stream.
    private ArkivoFileSystemEntryStream entryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        ArkivoFileSystemEntryStream entryStream = entries;
        if (entryStream == null) {
            throw new IOException("ZIP streaming reader has not advanced to an entry");
        }
        return entryStream;
    }

    /// Returns the internal entry stream, opening it if needed.
    private ArkivoFileSystemEntryStream openedEntryStream() throws IOException {
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

    /// Requires this reader to be open.
    private void ensureOpenUnchecked() {
        if (!open) {
            throw new IllegalStateException("ZIP streaming reader is closed");
        }
    }

    /// Iterates ZIP entry attributes in storage order.
    private final class EntryIterator implements Iterator<ZipArkivoEntryAttributes> {
        /// The prefetched next entry attributes, or `null` when no entry is prefetched.
        private @Nullable ZipArkivoEntryAttributes nextAttributes;

        /// Whether the next entry has been prefetched.
        private boolean prefetched;

        /// Whether the stream has reached the end.
        private boolean finished;

        /// Creates an entry iterator.
        private EntryIterator() {
        }

        /// Returns whether another entry is available.
        @Override
        public boolean hasNext() {
            prefetch();
            return !finished;
        }

        /// Returns the next entry attributes.
        @Override
        public ZipArkivoEntryAttributes next() {
            prefetch();
            if (finished) {
                throw new NoSuchElementException();
            }
            ZipArkivoEntryAttributes attributes = Objects.requireNonNull(nextAttributes, "nextAttributes");
            nextAttributes = null;
            prefetched = false;
            return attributes;
        }

        /// Prefetches the next entry attributes.
        private void prefetch() {
            if (prefetched || finished) {
                return;
            }
            try {
                if (openedEntryStream().next() == null) {
                    finished = true;
                    prefetched = true;
                    return;
                }
                ZipArkivoEntryAttributes attributes = fileSystem.currentEntryAttributes();
                if (attributes == null) {
                    throw new IOException("ZIP streaming reader did not expose the current entry");
                }
                nextAttributes = attributes;
                prefetched = true;
            } catch (IOException exception) {
                throw new DirectoryIteratorException(exception);
            }
        }
    }
}
