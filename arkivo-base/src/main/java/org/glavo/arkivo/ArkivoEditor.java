// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Edits an existing archive and commits the accumulated changes explicitly.
@NotNullByDefault
public interface ArkivoEditor<E extends ArkivoEntry<?>, O extends ArkivoEntryOptions> extends Closeable {
    /// Adds a new archive entry from the given channel.
    void add(ReadableByteChannel source, O options) throws IOException;

    /// Replaces an existing archive entry with the given decoded path.
    void replace(String path, ReadableByteChannel source, O options) throws IOException;

    /// Replaces an existing archive entry with the given raw encoded path.
    void replace(byte @Unmodifiable [] rawPath, ReadableByteChannel source, O options) throws IOException;

    /// Removes archive entries with the given decoded path.
    void remove(String path) throws IOException;

    /// Removes archive entries with the given raw encoded path.
    void remove(byte @Unmodifiable [] rawPath) throws IOException;

    /// Returns a read-only view of the current archive state.
    ArkivoReader<E> reader() throws IOException;

    /// Commits the accumulated changes to the archive.
    void commit() throws IOException;
}
