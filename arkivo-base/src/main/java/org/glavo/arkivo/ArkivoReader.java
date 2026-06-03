// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.List;

/// Reads archive metadata and item contents without modifying the archive.
@NotNullByDefault
public interface ArkivoReader<I extends ArkivoInfo> extends Closeable {
    /// Returns all known archive items.
    @Unmodifiable List<I> infos() throws IOException;

    /// Returns the first item with the given decoded path.
    @Nullable I first(String path) throws IOException;

    /// Returns the first item with the given raw encoded path.
    @Nullable I first(byte @Unmodifiable [] rawPath) throws IOException;

    /// Returns all items with the given decoded path.
    @Unmodifiable List<I> all(String path) throws IOException;

    /// Returns all items with the given raw encoded path.
    @Unmodifiable List<I> all(byte @Unmodifiable [] rawPath) throws IOException;

    /// Opens a channel for reading the item contents.
    ReadableByteChannel openChannel(I info) throws IOException;
}
