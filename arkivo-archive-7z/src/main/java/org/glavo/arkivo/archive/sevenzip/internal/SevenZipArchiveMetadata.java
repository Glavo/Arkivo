// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Stores parsed metadata for a 7z archive.
@NotNullByDefault
public final class SevenZipArchiveMetadata {
    /// The fixed 7z signature header.
    private final SevenZipSignatureHeader signatureHeader;

    /// The parsed archive entries.
    private final @Unmodifiable List<SevenZipEntryMetadata> entries;

    /// Creates parsed 7z archive metadata.
    ///
    /// @param signatureHeader the validated fixed signature header
    /// @param entries         the parsed entries to snapshot in archive order
    public SevenZipArchiveMetadata(
            SevenZipSignatureHeader signatureHeader,
            List<SevenZipEntryMetadata> entries
    ) {
        this.signatureHeader = Objects.requireNonNull(signatureHeader, "signatureHeader");
        this.entries = List.copyOf(entries);
    }

    /// Returns the fixed 7z signature header.
    ///
    /// @return the validated signature header
    public SevenZipSignatureHeader signatureHeader() {
        return signatureHeader;
    }

    /// Returns the parsed archive entries.
    ///
    /// @return the immutable entries in archive order
    public @Unmodifiable List<SevenZipEntryMetadata> entries() {
        return entries;
    }
}
