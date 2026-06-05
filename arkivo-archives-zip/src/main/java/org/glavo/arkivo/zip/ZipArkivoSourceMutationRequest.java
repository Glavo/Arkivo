// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an operation that may be able to mutate the source ZIP archive directly.
@NotNullByDefault
public interface ZipArkivoSourceMutationRequest {
    /// Returns the ZIP entry path affected by the operation.
    String path();

    /// Returns whether the operation changes only fixed-length fields or byte ranges.
    boolean fixedLengthPatch();

    /// Returns whether the editor implementation can apply the operation directly to the source archive.
    boolean directMutationSupported();
}
