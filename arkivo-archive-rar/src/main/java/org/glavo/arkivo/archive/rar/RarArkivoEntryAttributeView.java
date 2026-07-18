// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides read-only RAR-specific attributes for an archive entry.
///
/// RAR file systems are read-only, and [#readAttributes()] returns a stable snapshot whose values remain valid after
/// subsequent operations on the file system.
@NotNullByDefault
public interface RarArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "rar";
    }

    /// Reads a stable snapshot of the RAR-specific entry attributes.
    @Override
    RarArkivoEntryAttributes readAttributes() throws IOException;
}
