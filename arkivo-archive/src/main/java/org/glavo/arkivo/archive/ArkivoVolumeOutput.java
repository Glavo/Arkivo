// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/// Receives one assembled multi-volume archive before publishing or abandoning it.
///
/// Volume channels are opened once in ascending zero-based index order. The caller owns each returned channel and closes
/// it before opening the next volume or finishing the output. The output is stateful and not safe for concurrent use.
///
/// A successful [#commit(long)] is the only operation that publishes the assembled archive. Until then, callers must use
/// [#rollback()] or [#close()] to abandon staging state. Neither transaction completion method closes a volume channel
/// still held by the caller.
@NotNullByDefault
public interface ArkivoVolumeOutput extends AutoCloseable {
    /// Opens the writable channel for the next zero-based volume index.
    ///
    /// `index` must be zero for the first call and increase by one for each subsequent call. The caller must close the
    /// returned channel before requesting another volume or completing the transaction.
    ///
    /// @param index the next zero-based volume index
    /// @return a new caller-owned channel for the unpublished physical volume
    /// @throws IOException if the output is finished or staging storage cannot be opened
    /// @throws IllegalArgumentException if {@code index} is not the next sequential volume index
    WritableByteChannel openVolume(long index) throws IOException;

    /// Publishes all opened volumes and identifies the last volume in the logical archive.
    ///
    /// `finalVolumeIndex` must identify the last successfully opened volume. On success no further volumes can be opened
    /// and closing the output has no effect. If publication fails, call [#rollback()] or [#close()] to retry cleanup.
    ///
    /// @param finalVolumeIndex the zero-based index of the last opened volume
    /// @throws IOException if staged volumes cannot be published or cleanup after failed publication is incomplete
    /// @throws IllegalArgumentException if the index does not identify the last opened volume
    void commit(long finalVolumeIndex) throws IOException;

    /// Abandons all opened volumes and removes unpublished output when possible.
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
