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

    /// Returns the first item with the given name.
    @Nullable I first(ArkivoName name) throws IOException;

    /// Returns all items with the given name.
    @Unmodifiable List<I> all(ArkivoName name) throws IOException;

    /// Opens a channel for reading the item contents.
    ReadableByteChannel openChannel(I info) throws IOException;
}
