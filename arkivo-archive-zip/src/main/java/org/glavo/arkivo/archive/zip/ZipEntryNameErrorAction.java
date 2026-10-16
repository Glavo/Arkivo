// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Controls character-decoding failures in entry names during read-only ZIP operations.
///
/// These actions do not suppress malformed headers, invalid paths, duplicate decoded names, or read-limit failures.
/// Comments are retained as bytes and are unaffected. Creation and update operations do not accept recovery actions.
@NotNullByDefault
public enum ZipEntryNameErrorAction {
    /// Reports character-decoding failures as an [java.io.IOException]. This is the default.
    REPORT,

    /// Omits entries whose names throw [java.nio.charset.CharacterCodingException].
    /// Skipped entries still count toward entry and metadata limits. Indexed reading does not read their bodies.
    /// Streaming reading advances over their bodies using the ordinary entry-skipping rules; entries with unknown
    /// compressed sizes may require decompression and a password. Unsupported methods, corrupt bodies encountered
    /// during that traversal, and read-limit failures still terminate the operation.
    /// Password requests for skipped streaming entries use a diagnostic name because the original name is unavailable.
    SKIP,

    /// Replaces malformed or unmappable input with `U+FFFD` when a built-in decoder converts a name.
    /// This includes UTF-8 flags and matching Info-ZIP Unicode path fields, but not malformed extra-field structure.
    /// Replacement is lossy and can cause duplicate names; indexed reading rejects such duplicates.
    /// This action does not change charset selection or the streaming reader's UTF-8/CP437 fallback.
    /// Custom legacy decoders retain their own conversion policy and are not retried after failure.
    REPLACE
}
