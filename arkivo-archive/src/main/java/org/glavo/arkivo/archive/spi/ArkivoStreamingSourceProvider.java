// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.spi;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Probes and optionally transforms an outer wrapper around a forward-only archive source.
///
/// Implementations take ownership of `source` after argument validation. A nonmatching result must replay every byte
/// consumed while probing. A matching result must return the transformed logical source. The returned result owns that
/// logical channel until the caller transfers it with `takeChannel()`, and implementations close the input if probing
/// or transformation setup fails.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoStreamingSourceProvider {
    /// Probes one outer source wrapper and returns its replacement channel.
    ///
    /// @param source the channel whose ownership is transferred after argument validation
    /// @param options the archive-wide read limits and lifecycle options
    /// @return an owning result containing either a replaying untransformed source or the transformed logical source
    /// @throws IOException if probing or transformation setup fails
    ArkivoStreamingSource probe(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException;
}
