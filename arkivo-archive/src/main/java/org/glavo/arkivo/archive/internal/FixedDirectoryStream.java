// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/// Implements a single-iterator directory stream over an immutable entry snapshot.
///
/// Filtering occurs during traversal. Iterator operations and close are serialized; closing preserves an accepted
/// lookahead entry but prevents further filter calls.
///
/// @param <T> directory entry type
@NotNullByDefault
public final class FixedDirectoryStream<T> implements DirectoryStream<T> {
    /// The immutable entries exposed by this stream.
    private final @Unmodifiable List<T> entries;

    /// The filter applied as entries are traversed.
    private final Filter<? super T> filter;

    /// Whether this stream remains open.
    private boolean open = true;

    /// Whether the single permitted iterator has already been requested.
    private boolean iteratorReturned;

    /// Creates an unfiltered directory stream over the given entry snapshot.
    ///
    /// @param entries the entries to copy in iteration order
    public FixedDirectoryStream(List<T> entries) {
        this(entries, entry -> true);
    }

    /// Creates a filtered directory stream over the given entry snapshot.
    ///
    /// @param entries the entries to copy in iteration order
    /// @param filter the filter evaluated at most once for each traversed entry
    public FixedDirectoryStream(List<T> entries, Filter<? super T> filter) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    /// Returns the single filtered iterator permitted by the directory-stream contract.
    @Override
    public synchronized Iterator<T> iterator() {
        if (!open) {
            throw new IllegalStateException("Directory stream is closed");
        }
        if (iteratorReturned) {
            throw new IllegalStateException("Directory stream iterator has already been returned");
        }
        iteratorReturned = true;

        return new SnapshotIterator();
    }

    /// Closes this directory stream.
    @Override
    public synchronized void close() {
        open = false;
    }

    /// Filters the snapshot one accepted entry at a time under the stream monitor.
    @NotNullByDefault
    private final class SnapshotIterator implements Iterator<T> {
        /// Indicates that no accepted entry is buffered.
        private static final int NO_ENTRY = -1;

        /// The next snapshot index whose filter has not been evaluated.
        private int cursor;

        /// The accepted lookahead index, or [#NO_ENTRY].
        private int nextIndex = NO_ENTRY;

        /// Finds and buffers the next accepted entry without consuming existing lookahead.
        @Override
        public boolean hasNext() {
            synchronized (FixedDirectoryStream.this) {
                if (nextIndex != NO_ENTRY) return true;
                while (open && cursor < entries.size()) {
                    int index = cursor++;
                    try {
                        if (filter.accept(entries.get(index))) {
                            nextIndex = index;
                            return true;
                        }
                    } catch (IOException exception) {
                        throw new DirectoryIteratorException(exception);
                    }
                }
                return false;
            }
        }

        /// Consumes the accepted lookahead, even if the stream was closed after it was buffered.
        @Override
        public T next() {
            synchronized (FixedDirectoryStream.this) {
                if (!hasNext()) throw new NoSuchElementException();
                T entry = entries.get(nextIndex);
                nextIndex = NO_ENTRY;
                return entry;
            }
        }
    }
}
