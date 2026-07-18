// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

/// Provides path-backed storage for one assembled archive before it is committed.
///
/// The output is stateful and not safe for concurrent use. Channels returned by [#openChannel(Set)] are caller-owned and
/// must be closed before commit, rollback, or output close; transaction completion does not close them. The meaning of
/// publication and the ability to remove abandoned bytes depend on the [ArkivoCommitTarget] that created this output.
@NotNullByDefault
public interface ArkivoCommitOutput extends AutoCloseable {
    /// Returns the path that receives the assembled archive bytes before commit.
    ///
    /// This may be a staging path rather than the final published archive path.
    ///
    /// @return the assembly path
    Path path();

    /// Opens a caller-owned channel to the assembly path with the requested options.
    ///
    /// The option set is copied or consumed only for this open operation. The caller must close the channel before
    /// completing this output.
    ///
    /// @param options the options used to open the assembly path
    /// @return a new caller-owned channel positioned according to {@code options}
    /// @throws IOException if the assembly path cannot be opened
    SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException;

    /// Publishes the assembled archive according to the owning target's commit policy.
    ///
    /// After success, rollback and close have no effect. If this method throws, the caller may retry commit when the
    /// target permits it or abandon the output through [#rollback()].
    ///
    /// @throws IOException if publication fails or the assembled output cannot be finalized
    void commit() throws IOException;

    /// Abandons the assembled archive and removes temporary output when possible.
    ///
    /// Direct-write targets may have no separate staging artifact to remove. Repeated calls retry incomplete cleanup and
    /// otherwise have no effect.
    ///
    /// @throws IOException if temporary output cannot be removed or abandoned
    void rollback() throws IOException;

    /// Closes this output, rolling it back when it has not been committed.
    ///
    /// Repeated calls after cleanup completes have no effect. A caller may retry this method when an earlier call
    /// reports incomplete cleanup.
    ///
    /// @throws IOException if rollback of an uncommitted output fails
    @Override
    void close() throws IOException;
}
