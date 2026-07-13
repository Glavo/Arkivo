// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.spi;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Describes a forward-only source after one optional outer transformation probe.
///
/// @param transformed whether the provider recognized and transformed the source
/// @param channel logical source that owns and replaces the provider input
@NotNullByDefault
public record ArkivoStreamingSource(
        boolean transformed,
        ReadableByteChannel channel
) {
    /// Validates a streaming source result.
    public ArkivoStreamingSource {
        Objects.requireNonNull(channel, "channel");
    }
}
