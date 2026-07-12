// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Creates transactional output for a multi-volume archive.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoVolumeTarget {
    /// Opens a new independent multi-volume output transaction.
    ArkivoVolumeOutput openOutput() throws IOException;
}
