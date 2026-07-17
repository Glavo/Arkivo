// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the protected archive material for which a password is requested.
@NotNullByDefault
public enum PasswordPurpose {
    /// A credential used for archive-wide encryption without a more specific protected material.
    ARCHIVE,

    /// Archive metadata such as encrypted headers or entry names.
    ARCHIVE_METADATA,

    /// Archive content that cannot be attributed to one logical entry, such as a solid folder.
    ARCHIVE_CONTENT,

    /// The content of one logical archive entry.
    ENTRY_CONTENT
}
