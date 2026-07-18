// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeChannel;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Owns a ZIP volume source and exposes its physical volumes as one forward-only readable channel.
@NotNullByDefault
public final class ZipVolumeReadableByteChannel implements ReadableByteChannel {
    /// The owned volume source.
    private final ArkivoVolumeSource source;

    /// The logical read-only channel over all opened volumes.
    private final ArkivoVolumeChannel channel;

    /// Whether the logical channel has completed cleanup.
    private boolean channelClosed;

    /// Whether the volume source has completed cleanup.
    private boolean sourceClosed;

    /// Whether this wrapper accepts reads.
    private boolean open = true;

    /// Opens all source volumes and transfers successful reader ownership to this wrapper.
    ///
    /// Every consecutive volume channel is opened, its size is snapshotted, and physical byte offset zero becomes the
    /// volume's logical start. A setup failure closes channels already opened but leaves the source caller-owned.
    ///
    /// @param source the finite volume source to own after successful construction
    /// @throws NullPointerException if `source` is `null`
    /// @throws IOException if volume zero is absent or a volume cannot be opened, sized, or cleaned up after failure
    public ZipVolumeReadableByteChannel(ArkivoVolumeSource source) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.channel = ArkivoVolumeChannel.open(source);
    }

    /// Reads logical ZIP bytes across volume boundaries.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        return channel.read(destination);
    }

    /// Returns whether this wrapper accepts reads.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes the logical channel and owned source, retrying cleanup that previously failed.
    @Override
    public void close() throws IOException {
        if (!open && channelClosed && sourceClosed) {
            return;
        }
        open = false;

        @Nullable Throwable failure = null;
        if (!channelClosed) {
            try {
                channel.close();
                channelClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }
        if (!sourceClosed) {
            try {
                source.close();
                sourceClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        throwFailure(failure);
    }

    /// Requires this wrapper to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Throws one collected cleanup failure while preserving its type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
        throw new AssertionError(failure);
    }
}
