// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Creates output for a multi-volume archive.
///
/// Passing a target to an Arkivo factory does not transfer ownership of the target itself. The selected format owns the
/// [ArkivoVolumeOutput] returned by [#openOutput()] and must commit or roll it back. The target determines whether bytes
/// are staged until commit or written directly to their destination.
///
/// [ArkivoPathVolumeTarget] provides staged publication using an [ArkivoVolumePathLayout] to name the output paths.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoVolumeTarget {
    /// Opens a new independent multi-volume output transaction owned by the caller.
    ///
    /// @return a new caller-owned output transaction
    /// @throws IOException if staging or destination state cannot be prepared
    ArkivoVolumeOutput openOutput() throws IOException;
}
