// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/// Creates writable channels for split archive volumes.
@NotNullByDefault
public interface ArkivoVolumeSink extends Closeable {
    /// Opens the writable channel for a zero-based volume index.
    WritableByteChannel openVolume(long index) throws IOException;

    /// Closes resources owned by this sink.
    @Override
    default void close() throws IOException {
    }
}
