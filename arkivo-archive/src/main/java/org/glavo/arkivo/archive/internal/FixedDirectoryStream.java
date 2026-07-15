// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/// Implements a single-iterator directory stream over an immutable entry snapshot.
///
/// @param <T> directory entry type
@NotNullByDefault
public final class FixedDirectoryStream<T> implements DirectoryStream<T> {
    /// The immutable entries exposed by this stream.
    private final @Unmodifiable List<T> entries;

    /// The filter applied when the iterator is requested.
    private final Filter<? super T> filter;

    /// Whether this stream remains open.
    private boolean open = true;

    /// Whether the single permitted iterator has already been requested.
    private boolean iteratorReturned;

    /// Creates an unfiltered directory stream over the given entry snapshot.
    public FixedDirectoryStream(List<T> entries) {
        this(entries, entry -> true);
    }

    /// Creates a filtered directory stream over the given entry snapshot.
    public FixedDirectoryStream(List<T> entries, Filter<? super T> filter) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    /// Returns the single filtered iterator permitted by the directory-stream contract.
    @Override
    public Iterator<T> iterator() {
        if (!open) {
            throw new IllegalStateException("Directory stream is closed");
        }
        if (iteratorReturned) {
            throw new IllegalStateException("Directory stream iterator has already been returned");
        }
        iteratorReturned = true;

        ArrayList<T> accepted = new ArrayList<>(entries.size());
        for (T entry : entries) {
            try {
                if (filter.accept(entry)) {
                    accepted.add(entry);
                }
            } catch (IOException exception) {
                throw new DirectoryIteratorException(exception);
            }
        }
        return List.copyOf(accepted).iterator();
    }

    /// Closes this directory stream.
    @Override
    public void close() {
        open = false;
    }
}
