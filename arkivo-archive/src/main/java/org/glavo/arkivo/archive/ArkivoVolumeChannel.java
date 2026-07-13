// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Presents a finite sequence of archive volumes as one read-only logical seekable channel.
///
/// Opening the channel obtains every consecutive volume starting at index zero and snapshots its size. The returned
/// channel owns the opened volume channels, while the caller retains ownership of the ArkivoVolumeSource itself.
/// Backing volume content and sizes must remain stable until this channel closes.
@NotNullByDefault
public final class ArkivoVolumeChannel implements SeekableByteChannel {
    /// Opened volume channels in logical order.
    private final @Unmodifiable List<SeekableByteChannel> channels;

    /// Logical start offset of each volume.
    private final long @Unmodifiable [] offsets;

    /// Snapshotted size of each volume.
    private final long @Unmodifiable [] sizes;

    /// Whether each volume channel has been closed successfully.
    private final boolean[] channelClosed;

    /// Total logical size of all volumes.
    private final long size;

    /// Current logical position.
    private long position;

    /// Whether this logical channel accepts operations.
    private boolean open = true;

    /// Creates a logical channel from validated volume metadata.
    private ArkivoVolumeChannel(
            List<SeekableByteChannel> channels,
            long[] offsets,
            long[] sizes,
            long size
    ) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        this.offsets = Objects.requireNonNull(offsets, "offsets").clone();
        this.sizes = Objects.requireNonNull(sizes, "sizes").clone();
        this.channelClosed = new boolean[channels.size()];
        this.size = size;
    }

    /// Opens all consecutive volumes supplied from index zero.
    ///
    /// At least one volume must be present. Any channels opened before setup fails are closed, and cleanup failures are
    /// suppressed on the setup failure.
    public static ArkivoVolumeChannel open(ArkivoVolumeSource source) throws IOException {
        Objects.requireNonNull(source, "source");

        ArrayList<SeekableByteChannel> channels = new ArrayList<>();
        ArrayList<Long> offsets = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();
        long logicalSize = 0L;
        long index = 0L;
        try {
            while (true) {
                SeekableByteChannel channel = source.openVolume(index);
                if (channel == null) {
                    break;
                }
                channels.add(channel);

                long volumeSize = channel.size();
                if (volumeSize < 0L) {
                    throw new IOException("Archive volume size is negative: " + index);
                }
                offsets.add(logicalSize);
                sizes.add(volumeSize);
                try {
                    logicalSize = Math.addExact(logicalSize, volumeSize);
                } catch (ArithmeticException exception) {
                    throw new IOException("Logical archive size is too large", exception);
                }

                if (index == Long.MAX_VALUE) {
                    throw new IOException("Archive has too many volumes");
                }
                index++;
            }
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(channels, exception);
            throw exception;
        }

        if (channels.isEmpty()) {
            throw new IOException("Archive volume source did not provide volume zero");
        }
        return new ArkivoVolumeChannel(channels, toLongArray(offsets), toLongArray(sizes), logicalSize);
    }

    /// Returns the number of opened physical volumes.
    public long volumeCount() throws IOException {
        ensureOpen();
        return channels.size();
    }

    /// Returns the logical start offset of one physical volume.
    public long volumeStartOffset(long volumeIndex) throws IOException {
        ensureOpen();
        return offsets[checkedVolumeIndex(volumeIndex)];
    }

    /// Returns the snapshotted size of one physical volume.
    public long volumeSize(long volumeIndex) throws IOException {
        ensureOpen();
        return sizes[checkedVolumeIndex(volumeIndex)];
    }

    /// Reads across physical volume boundaries from the current logical position.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (!destination.hasRemaining()) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }

        int totalRead = 0;
        while (destination.hasRemaining() && position < size) {
            int volumeIndex = volumeIndex(position);
            long localPosition = position - offsets[volumeIndex];
            long volumeRemaining = sizes[volumeIndex] - localPosition;
            SeekableByteChannel channel = channels.get(volumeIndex);
            channel.position(localPosition);

            int originalLimit = destination.limit();
            int chunkSize = (int) Math.min(destination.remaining(), volumeRemaining);
            destination.limit(destination.position() + chunkSize);
            int read;
            try {
                read = channel.read(destination);
            } finally {
                destination.limit(originalLimit);
            }

            if (read < 0) {
                throw new EOFException("Archive volume ended before its declared size: " + volumeIndex);
            }
            if (read == 0) {
                return totalRead > 0 ? totalRead : 0;
            }
            position += read;
            totalRead += read;
        }
        return totalRead;
    }

    /// Rejects writes because a logical archive volume channel is read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the current logical position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the current logical position, including positions beyond the logical end.
    @Override
    public ArkivoVolumeChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the snapshotted total logical size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    /// Rejects truncation because a logical archive volume channel is read-only.
    @Override
    public ArkivoVolumeChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if (newSize < 0L) {
            throw new IllegalArgumentException("newSize must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether the logical channel accepts operations.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes every opened volume channel, retrying channels left open by an earlier failed close.
    @Override
    public void close() throws IOException {
        if (!open && allChannelsClosed()) {
            return;
        }
        open = false;

        Throwable failure = null;
        for (int index = 0; index < channels.size(); index++) {
            if (channelClosed[index]) {
                continue;
            }
            SeekableByteChannel channel = channels.get(index);
            try {
                channel.close();
                channelClosed[index] = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (!channel.isOpen()) {
                    channelClosed[index] = true;
                }
                failure = mergeFailure(failure, exception);
            }
        }
        throwFailure(failure);
    }

    /// Returns the physical volume containing a logical position known to be before the logical end.
    private int volumeIndex(long logicalPosition) {
        int low = 0;
        int high = offsets.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (offsets[middle] <= logicalPosition) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low - 1;
    }

    /// Returns one physical volume index after validating its public long representation.
    private int checkedVolumeIndex(long volumeIndex) throws IOException {
        if (volumeIndex < 0L || volumeIndex >= channels.size()) {
            throw new IOException("Archive volume is not available: " + volumeIndex);
        }
        return (int) volumeIndex;
    }

    /// Requires the logical channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Returns whether all opened physical channels have completed cleanup.
    private boolean allChannelsClosed() {
        for (boolean closed : channelClosed) {
            if (!closed) {
                return false;
            }
        }
        return true;
    }

    /// Converts boxed long metadata to a primitive array.
    private static long[] toLongArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /// Closes channels opened before setup failed without replacing the setup failure.
    private static void closeAfterOpenFailure(List<SeekableByteChannel> channels, Throwable failure) {
        for (SeekableByteChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
    }

    /// Adds one cleanup failure to a previously collected failure.
    private static Throwable mergeFailure(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    /// Throws a collected failure while preserving checked and unchecked types.
    private static void throwFailure(Throwable failure) throws IOException {
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
