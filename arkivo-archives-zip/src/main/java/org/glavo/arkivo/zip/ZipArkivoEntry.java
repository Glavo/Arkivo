// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEntry;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Represents a ZIP entry handle bound to the reader that created it.
@NotNullByDefault
public final class ZipArkivoEntry implements ArkivoEntry<ZipArkivoEntryInfo> {
    /// The reader that owns this entry handle.
    private final ZipArkivoReader reader;

    /// The immutable metadata for this entry.
    private final ZipArkivoEntryInfo info;

    /// Creates a ZIP entry handle.
    ZipArkivoEntry(ZipArkivoReader reader, ZipArkivoEntryInfo info) {
        this.reader = reader;
        this.info = info;
    }

    /// Returns the immutable metadata for this ZIP entry.
    @Override
    public ZipArkivoEntryInfo info() {
        return info;
    }

    /// Opens a channel for reading this ZIP entry's contents.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        return reader.openChannel(this);
    }
}
