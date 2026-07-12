// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/// Receives one assembled multi-volume archive before publishing or abandoning it.
///
/// Volume channels are opened once in ascending zero-based index order. The caller owns each returned channel and closes
/// it before opening the next volume or finishing the output.
@NotNullByDefault
public interface ArkivoVolumeOutput extends AutoCloseable {
    /// Opens the writable channel for the next zero-based volume index.
    WritableByteChannel openVolume(long index) throws IOException;

    /// Publishes all opened volumes and identifies the last volume in the logical archive.
    void commit(long finalVolumeIndex) throws IOException;

    /// Abandons all opened volumes and removes unpublished output when possible.
    ///
    /// Repeated calls after rollback or a successful commit have no effect.
    void rollback() throws IOException;

    /// Closes this output, rolling it back when it has not been committed.
    ///
    /// Repeated calls after cleanup has completed have no effect.
    @Override
    void close() throws IOException;
}
