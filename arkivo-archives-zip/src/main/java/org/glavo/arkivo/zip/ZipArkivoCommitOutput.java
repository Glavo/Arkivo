// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

/// Provides access to an assembled archive output before it is committed.
@NotNullByDefault
public interface ZipArkivoCommitOutput extends AutoCloseable {
    /// Returns the path that receives the assembled archive bytes.
    Path path();

    /// Opens a channel to write assembled archive bytes.
    SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException;

    /// Publishes the assembled archive according to the owning target's commit policy.
    void commit() throws IOException;

    /// Abandons the assembled archive and removes temporary output when possible.
    void rollback() throws IOException;

    /// Closes this output object without committing it.
    @Override
    void close() throws IOException;
}
