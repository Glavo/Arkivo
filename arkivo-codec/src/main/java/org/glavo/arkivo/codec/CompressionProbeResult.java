// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Represents a forward-only compression probe result and its prefix-replaying channel.
///
/// Instances have resource identity rather than value identity. A result owns its channel until `takeChannel()`
/// transfers ownership or `close()` closes it.
@NotNullByDefault
public final class CompressionProbeResult implements AutoCloseable {
    /// The detected format, or `null` when no installed format matched.
    private final @Nullable CompressionFormat format;

    /// An immutable private copy of bytes consumed during detection.
    private final @UnmodifiableView ByteBuffer prefix;

    /// The channel that returns every byte from the original probe position, or `null` after transfer or closure.
    private @Nullable ReadableByteChannel channel;

    /// Creates a result by copying the consumed prefix and taking ownership of the replaying channel.
    CompressionProbeResult(
            @Nullable CompressionFormat format,
            ByteBuffer prefix,
            ReadableByteChannel channel
    ) {
        this.format = format;
        ByteBuffer sourcePrefix = Objects.requireNonNull(prefix, "prefix").slice();
        ByteBuffer copiedPrefix = ByteBuffer.allocate(sourcePrefix.remaining());
        copiedPrefix.put(sourcePrefix).flip();
        this.prefix = copiedPrefix.asReadOnlyBuffer();
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /// Returns the detected format, or `null` when no installed format matched.
    public @Nullable CompressionFormat format() {
        return format;
    }

    /// Returns an independent read-only view of bytes consumed during detection.
    public @UnmodifiableView ByteBuffer prefix() {
        return prefix.asReadOnlyBuffer();
    }

    /// Transfers and returns the channel that replays the consumed prefix before the remaining source.
    ///
    /// @throws IllegalStateException when the channel was already transferred or closed
    public synchronized ReadableByteChannel takeChannel() {
        @Nullable ReadableByteChannel current = channel;
        if (current == null) {
            throw new IllegalStateException("Probe channel was already transferred or closed");
        }
        channel = null;
        return current;
    }

    /// Returns whether an installed format matched the probed prefix.
    public boolean detected() {
        return format != null;
    }

    /// Closes the prefix-replaying channel unless its ownership was transferred.
    ///
    /// A failed close retains ownership so a later call can retry cleanup.
    @Override
    public synchronized void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }
}
