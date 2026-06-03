// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an archive format supported by Arkivo.
@NotNullByDefault
public interface ArkivoFormat {
    /// Returns the stable format name.
    String name();
}
