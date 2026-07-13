// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Stores a forward-only compression probe result and its prefix-replaying channel.
///
/// @param codec detected codec, or `null` when no installed codec matched
/// @param prefix bytes consumed from the source during detection
/// @param channel channel that returns every byte from the original probe position
@NotNullByDefault
public record CompressionProbeResult(
        @Nullable CompressionCodec codec,
        @UnmodifiableView ByteBuffer prefix,
        ReadableByteChannel channel
) {
    /// Validates and protects a compression probe result.
    public CompressionProbeResult {
        prefix = Objects.requireNonNull(prefix, "prefix").slice().asReadOnlyBuffer();
        Objects.requireNonNull(channel, "channel");
    }

    /// Returns an independent read-only view of bytes consumed during detection.
    @Override
    public @UnmodifiableView ByteBuffer prefix() {
        return prefix.asReadOnlyBuffer();
    }

    /// Returns whether an installed codec matched the probed prefix.
    public boolean detected() {
        return codec != null;
    }
}
