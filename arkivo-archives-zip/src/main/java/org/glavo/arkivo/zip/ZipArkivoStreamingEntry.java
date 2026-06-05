// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoStreamingEntryImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Describes the current entry returned by a forward-only ZIP streaming reader.
@NotNullByDefault
public sealed interface ZipArkivoStreamingEntry permits ZipArkivoStreamingEntryImpl {
    /// Returns the decoded entry path.
    Path path();

    /// Returns the decoded ZIP entry path text using `/` separators.
    String pathText();

    /// Returns whether this entry represents a directory.
    boolean directory();
}
