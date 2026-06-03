// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

/// Writes a new archive.
@NotNullByDefault
public interface ArkivoWriter<S extends ArkivoInfoSpec> extends Closeable {
    /// Adds a new item from a source channel.
    void add(ReadableByteChannel source, S spec) throws IOException;

    /// Adds a file system path under the given decoded archive path.
    void add(Path source, String path) throws IOException;

    /// Adds a file system path under the given raw encoded archive path.
    void add(Path source, byte @Unmodifiable [] rawPath) throws IOException;
}
