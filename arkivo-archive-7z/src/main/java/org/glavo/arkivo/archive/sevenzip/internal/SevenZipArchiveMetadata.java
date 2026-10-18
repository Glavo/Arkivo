// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Stores parsed metadata for a 7z archive.
///
/// @param signatureHeader the validated fixed signature header
/// @param entries the immutable parsed entries in archive order
@NotNullByDefault
record SevenZipArchiveMetadata(
        SevenZipSignatureHeader signatureHeader,
        @Unmodifiable List<SevenZipEntryMetadata> entries
) {
    /// Creates metadata with a snapshot of the parsed entries.
    SevenZipArchiveMetadata {
        Objects.requireNonNull(signatureHeader, "signatureHeader");
        entries = List.copyOf(entries);
    }
}
