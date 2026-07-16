// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.spi;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Represents a forward-only source after one optional outer transformation probe.
///
/// Instances have resource identity rather than value identity. A result owns its logical channel until `takeChannel()`
/// transfers ownership or `close()` closes it.
@NotNullByDefault
public final class ArkivoStreamingSource implements AutoCloseable {
    /// Whether the provider recognized and transformed the source.
    private final boolean transformed;

    /// The logical source that owns and replaces the provider input, or `null` after transfer or closure.
    private @Nullable ReadableByteChannel channel;

    /// Creates a streaming source result and takes ownership of its logical channel.
    public ArkivoStreamingSource(boolean transformed, ReadableByteChannel channel) {
        this.transformed = transformed;
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /// Returns whether the provider recognized and transformed the source.
    public boolean transformed() {
        return transformed;
    }

    /// Transfers and returns the logical source that replaces the provider input.
    ///
    /// @throws IllegalStateException when the channel was already transferred or closed
    public synchronized ReadableByteChannel takeChannel() {
        @Nullable ReadableByteChannel current = channel;
        if (current == null) {
            throw new IllegalStateException("Streaming source was already transferred or closed");
        }
        channel = null;
        return current;
    }

    /// Closes the logical result channel unless its ownership was transferred.
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
