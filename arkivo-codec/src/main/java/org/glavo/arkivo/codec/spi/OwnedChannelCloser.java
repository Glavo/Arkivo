// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.Objects;

/// Tracks completion of an owned channel close independently from codec lifecycle state.
///
/// Codec implementations use this helper so a failed endpoint close can be retried without repeating stream
/// finalization or native-resource release.
@NotNullByDefault
public final class OwnedChannelCloser {
    /// The backing channel.
    private final Channel channel;

    /// Whether this context owns the backing channel.
    private final ResourceOwnership ownership;

    /// Whether the ownership obligation has completed.
    private boolean complete;

    /// Creates a close tracker for a backing channel and ownership policy.
    ///
    /// @param channel the backing channel whose ownership obligation is tracked
    /// @param ownership whether this tracker must close `channel`
    public OwnedChannelCloser(Channel channel, ResourceOwnership ownership) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Completes endpoint closure, retrying a previous failed attempt.
    ///
    /// @throws IOException if closing an owned channel fails
    public synchronized void close() throws IOException {
        closeAfter(null);
    }

    /// Completes endpoint closure and then rethrows an earlier lifecycle failure.
    ///
    /// A close failure is added to `primaryFailure` when one is present. The ownership obligation remains incomplete so
    /// a later call can retry the endpoint close without repeating codec finalization.
    ///
    /// @param primaryFailure an earlier lifecycle failure to rethrow after applying ownership, or `null` if none
    /// @throws IOException if the primary failure or an unsuppressed close failure is an `IOException`
    public synchronized void closeAfter(@Nullable Throwable primaryFailure) throws IOException {
        if (!complete) {
            if (ownership == ResourceOwnership.BORROWED) {
                complete = true;
            } else {
                try {
                    channel.close();
                    complete = true;
                } catch (IOException | RuntimeException | Error exception) {
                    if (primaryFailure == null) {
                        rethrow(exception);
                    } else if (primaryFailure != exception) {
                        primaryFailure.addSuppressed(exception);
                    }
                }
            }
        }
        if (primaryFailure != null) {
            rethrow(primaryFailure);
        }
    }

    /// Returns whether the ownership obligation has completed.
    ///
    /// @return whether the channel was borrowed or an owned channel was closed successfully
    public synchronized boolean isComplete() {
        return complete;
    }

    /// Rethrows a close failure with its original checked or unchecked type.
    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }
}
