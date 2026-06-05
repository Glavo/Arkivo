// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an operation that may be able to mutate the source archive directly.
@NotNullByDefault
public interface ArkivoSourceMutationRequest {
    /// Returns the archive entry path affected by the operation.
    String path();

    /// Returns whether the operation changes only fixed-length fields or byte ranges.
    boolean fixedLengthPatch();

    /// Returns whether the editor implementation can apply the operation directly to the source archive.
    boolean directMutationSupported();
}
