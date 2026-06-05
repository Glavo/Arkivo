// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoStreamingEntry;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Implements a ZIP streaming entry descriptor.
@NotNullByDefault
public final class ZipArkivoStreamingEntryImpl implements ZipArkivoStreamingEntry {
    /// The decoded entry path.
    private final Path path;

    /// The decoded ZIP entry path text.
    private final String pathText;

    /// Whether this entry represents a directory.
    private final boolean directory;

    /// Creates a ZIP streaming entry descriptor.
    public ZipArkivoStreamingEntryImpl(Path path, String pathText, boolean directory) {
        this.path = Objects.requireNonNull(path, "path");
        this.pathText = Objects.requireNonNull(pathText, "pathText");
        this.directory = directory;
    }

    /// Returns the decoded entry path.
    @Override
    public Path path() {
        return path;
    }

    /// Returns the decoded ZIP entry path text using `/` separators.
    @Override
    public String pathText() {
        return pathText;
    }

    /// Returns whether this entry represents a directory.
    @Override
    public boolean directory() {
        return directory;
    }
}
