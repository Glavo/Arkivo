// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.Channel;

/// Allows an internal channel decorator to propagate abortive closure through ordinary ownership boundaries.
@NotNullByDefault
public interface ForceCloseableChannel extends Channel {
    /// Force-closes a channel, propagating through decorators that recognize ownership boundaries.
    ///
    /// @param channel the channel to close
    /// @throws IOException if the endpoint cannot be closed
    static void forceClose(Channel channel) throws IOException {
        if (channel instanceof ForceCloseableChannel forceCloseableChannel) {
            forceCloseableChannel.forceClose();
        } else {
            channel.close();
        }
    }

    /// Closes this channel and the endpoint required to terminate any operation using it.
    ///
    /// @throws IOException if the endpoint cannot be closed
    void forceClose() throws IOException;
}
