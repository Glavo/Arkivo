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
    ///
    /// @param options the options for this channel open operation
    /// @return a new caller-owned channel over the staged content
    /// @throws IOException if the staged content cannot be opened
    SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException;

    /// Returns the current size of the staged content.
    ///
    /// @return the current byte count
    /// @throws IOException if the content size cannot be obtained
    long size() throws IOException;

    /// Releases this staged content and removes any temporary files owned by it.
    ///
    /// @throws IOException if owned backing resources cannot be released
    @Override
    void close() throws IOException;
}
