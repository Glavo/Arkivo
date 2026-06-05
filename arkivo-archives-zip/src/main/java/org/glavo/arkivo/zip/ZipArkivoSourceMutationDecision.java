// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes how an editor should handle a potential direct source-file mutation.
@NotNullByDefault
public enum ZipArkivoSourceMutationDecision {
    /// Allows the editor to mutate the source archive directly.
    ALLOW,

    /// Requires the editor to rewrite the archive through its commit target.
    REWRITE,

    /// Rejects the requested operation.
    REJECT
}
