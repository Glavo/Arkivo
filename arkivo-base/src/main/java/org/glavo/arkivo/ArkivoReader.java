// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/// Reads archive entries without modifying the archive.
@NotNullByDefault
public interface ArkivoReader<E extends ArkivoEntry<?>> extends Closeable {
    /// Returns all known archive entries.
    @Unmodifiable List<E> entries() throws IOException;

    /// Returns the first entry with the given decoded path.
    @Nullable E first(String path) throws IOException;

    /// Returns the first entry with the given raw encoded path.
    @Nullable E first(byte @Unmodifiable [] rawPath) throws IOException;

    /// Returns all entries with the given decoded path.
    @Unmodifiable List<E> all(String path) throws IOException;

    /// Returns all entries with the given raw encoded path.
    @Unmodifiable List<E> all(byte @Unmodifiable [] rawPath) throws IOException;
}
