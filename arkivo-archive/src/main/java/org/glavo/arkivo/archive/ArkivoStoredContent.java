// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.util.Set;

/// Provides seekable access to one archive entry body retained by an indexed file system.
///
/// A content object may own staged bytes or describe a lazy read-only view over the original archive. Each opened
/// channel is independently positioned and caller-owned. Closing the content releases only resources retained directly
/// by that object; channels already returned to callers retain their own lifecycle.
@NotNullByDefault
public interface ArkivoStoredContent extends AutoCloseable {
    /// Opens a channel over the retained content with the given open options.
    ///
    /// @param options the options for this channel open operation
    /// @return a new caller-owned channel over the retained content
    /// @throws IOException if the retained content cannot be opened
    SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException;

    /// Returns the current logical size of the retained content.
    ///
    /// @return the current byte count
    /// @throws IOException if the content size cannot be obtained
    long size() throws IOException;

    /// Releases resources retained by this content and removes any temporary files it owns.
    ///
    /// @throws IOException if owned backing resources cannot be released
    @Override
    void close() throws IOException;
}
