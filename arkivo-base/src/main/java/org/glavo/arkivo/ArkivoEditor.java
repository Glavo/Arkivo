// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Edits an existing archive and commits the accumulated changes explicitly.
@NotNullByDefault
public interface ArkivoEditor<I extends ArkivoInfo, S extends ArkivoInfoSpec> extends Closeable {
    /// Adds a new archive item from the given channel.
    void add(ReadableByteChannel source, S spec) throws IOException;

    /// Replaces an existing archive item with data read from the given channel.
    void replace(ArkivoName name, ReadableByteChannel source, S spec) throws IOException;

    /// Removes archive items with the given name.
    void remove(ArkivoName name) throws IOException;

    /// Returns a read-only view of the current archive state.
    ArkivoReader<I> reader() throws IOException;

    /// Commits the accumulated changes to the archive.
    void commit() throws IOException;
}
