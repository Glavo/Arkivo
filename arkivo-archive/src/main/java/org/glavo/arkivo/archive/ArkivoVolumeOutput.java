// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/// Receives the volumes of one archive and completes or abandons its output.
///
/// Volume channels are opened once in ascending zero-based index order. The caller owns each returned channel and closes
/// it before opening the next volume or finishing the output. The output is stateful and not safe for concurrent use.
///
/// [#commit(long)] completes the output according to the target's publication policy. A target may stage volumes until
/// commit or write directly to their destination. Directly written bytes may be visible before commit and may not be
/// recoverable by [#rollback()]. This interface does not guarantee atomic publication of multiple volumes.
/// Callers must close every opened volume channel before committing, rolling back, or closing the output.
@NotNullByDefault
public interface ArkivoVolumeOutput extends AutoCloseable {
    /// Opens the writable channel for the next zero-based volume index.
    ///
    /// `index` must be zero for the first call and increase by one for each subsequent call. The caller must close the
    /// returned channel before requesting another volume or completing the transaction.
    ///
    /// @param index the next zero-based volume index
    /// @return a new caller-owned channel for the physical volume
    /// @throws IOException if the output is finished or staging storage cannot be opened
    /// @throws IllegalArgumentException if {@code index} is not the next sequential volume index
    WritableByteChannel openVolume(long index) throws IOException;

    /// Completes the archive output and identifies its last volume.
    ///
    /// `finalVolumeIndex` must identify the last successfully opened volume. On success no further volumes can be opened
    /// and closing the output has no effect. If publication fails, call [#rollback()] or [#close()] to retry cleanup.
    /// A failure does not imply that no output was published; the target defines whether earlier changes can be undone.
    ///
    /// @param finalVolumeIndex the zero-based index of the last opened volume
    /// @throws IOException if this output is finished, volumes cannot be published, or cleanup is incomplete
    /// @throws IllegalArgumentException if the index does not identify the last opened volume
    void commit(long finalVolumeIndex) throws IOException;

    /// Abandons the output and releases temporary storage when possible.
    ///
    /// This operation does not guarantee restoration of the destination. Direct-write targets cannot generally undo
    /// bytes already written, and staged targets may fail while restoring previously published output.
    ///
    /// Repeated calls after cleanup completes or a successful commit have no effect. A caller may retry this method when
    /// an earlier call reports incomplete cleanup.
    ///
    /// @throws IOException if unpublished output cannot be removed or previous output cannot be restored
    void rollback() throws IOException;

    /// Closes this output, rolling it back when it has not been committed.
    ///
    /// Repeated calls after cleanup has completed have no effect; calls after failed cleanup retry rollback work.
    ///
    /// @throws IOException if rollback of uncommitted output fails
    @Override
    void close() throws IOException;
}
