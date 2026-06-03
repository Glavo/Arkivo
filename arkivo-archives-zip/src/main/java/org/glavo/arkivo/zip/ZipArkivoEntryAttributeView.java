// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides ZIP-specific attributes for an archive entry path.
@NotNullByDefault
public interface ZipArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "zip";
    }

    /// Reads the ZIP-specific entry attributes.
    @Override
    ZipArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the ZIP compression method requested for the entry.
    void setMethod(ZipMethod method) throws IOException;

    /// Sets the ZIP encryption method requested for the entry.
    void setEncryption(ZipEncryption encryption) throws IOException;
}
