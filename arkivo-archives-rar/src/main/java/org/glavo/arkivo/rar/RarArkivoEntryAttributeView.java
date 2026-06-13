// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides RAR-specific attributes for an archive entry.
@NotNullByDefault
public interface RarArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "rar";
    }

    /// Reads the RAR-specific entry attributes.
    @Override
    RarArkivoEntryAttributes readAttributes() throws IOException;
}
