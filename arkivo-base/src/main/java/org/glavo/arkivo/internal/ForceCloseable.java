// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.Closeable;

/// Propagates abortive closure through internal stream and channel ownership boundaries.
@NotNullByDefault
public interface ForceCloseable extends Closeable {
    /// Force-closes a resource, or invokes ordinary closure when it has no distinct abort operation.
    ///
    /// @param resource the resource to close
    /// @throws IOException if the endpoint cannot be closed
    static void forceClose(Closeable resource) throws IOException {
        if (resource instanceof ForceCloseable forceCloseable) {
            forceCloseable.forceClose();
        } else {
            resource.close();
        }
    }

    /// Closes this resource without finishing or validating unread content.
    ///
    /// Endpoint closure follows the resource's ownership and interruption contracts. When endpoint closure is required,
    /// implementations must initiate it before waiting for an active read, write, or graceful close. A borrowed endpoint
    /// may remain open, in which case cancellation need not unblock an operation waiting on that endpoint.
    /// Implementations must not start additional data transfer to finish or validate unread content. Failed endpoint
    /// cleanup remains retryable by subsequent close calls.
    ///
    /// @throws IOException if the endpoint cannot be closed
    void forceClose() throws IOException;
}
