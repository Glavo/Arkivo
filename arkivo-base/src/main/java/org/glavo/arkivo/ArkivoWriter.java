// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

/// Writes a new archive.
@NotNullByDefault
public interface ArkivoWriter<S extends ArkivoInfoSpec> extends Closeable {
    /// Adds a new item from a source channel.
    void add(ReadableByteChannel source, S spec) throws IOException;

    /// Adds a file system path under the given archive name.
    void add(Path source, ArkivoName name) throws IOException;
}
