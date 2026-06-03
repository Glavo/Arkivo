// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Represents an archive entry handle bound to the reader that created it.
@NotNullByDefault
public interface ArkivoEntry<I extends ArkivoEntryInfo> {
    /// Returns the immutable metadata for this entry.
    I info();

    /// Opens a channel for reading this entry's contents.
    ReadableByteChannel openChannel() throws IOException;
}
