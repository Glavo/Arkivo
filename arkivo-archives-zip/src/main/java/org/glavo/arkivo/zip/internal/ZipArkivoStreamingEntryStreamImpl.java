// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingEntryStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.channels.ReadableByteChannel;
import java.util.Iterator;
import java.util.NoSuchElementException;
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

    /// Whether an iterator has already been created.
    private boolean iteratorCreated;

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

    /// Returns the single iterator for this streaming entry stream.
    @Override
    public synchronized Iterator<ZipArkivoEntryAttributes> iterator() {
        ensureOpenUnchecked();
        if (iteratorCreated) {
            throw new IllegalStateException("ZIP streaming entry stream iterator has already been created");
        }
        iteratorCreated = true;
        return new EntryIterator();
    }

    /// Requires this entry stream to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming entry stream is closed");
        }
    }

    /// Requires this entry stream to be open.
    private void ensureOpenUnchecked() {
        if (!open) {
            throw new IllegalStateException("ZIP streaming entry stream is closed");
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
                nextAttributes = ZipArkivoStreamingEntryStreamImpl.this.next();
                prefetched = true;
                if (nextAttributes == null) {
                    finished = true;
                }
            } catch (IOException exception) {
                throw new DirectoryIteratorException(exception);
            }
        }
    }
}
