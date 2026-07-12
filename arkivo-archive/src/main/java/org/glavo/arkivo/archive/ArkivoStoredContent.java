// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.util.Set;

/// Provides seekable access to one staged archive entry body.
@NotNullByDefault
public interface ArkivoStoredContent extends AutoCloseable {
    /// Opens a channel over the staged content with the given open options.
    SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException;

    /// Returns the current size of the staged content.
    long size() throws IOException;

    /// Releases this staged content and removes any temporary files owned by it.
    @Override
    void close() throws IOException;
}
