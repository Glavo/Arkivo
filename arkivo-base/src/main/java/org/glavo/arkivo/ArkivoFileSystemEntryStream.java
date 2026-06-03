// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/// Traverses archive entry paths in storage order.
@NotNullByDefault
public interface ArkivoFileSystemEntryStream extends Closeable {
    /// Returns the next entry path, or `null` when traversal is complete.
    @Nullable Path next() throws IOException;

    /// Closes this entry stream.
    @Override
    void close() throws IOException;
}
