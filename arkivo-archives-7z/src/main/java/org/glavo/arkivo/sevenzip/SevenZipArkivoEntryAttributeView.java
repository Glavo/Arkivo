// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides 7z-specific attributes for an archive entry.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "7z";
    }

    /// Reads the 7z-specific entry attributes.
    @Override
    SevenZipArkivoEntryAttributes readAttributes() throws IOException;
}
