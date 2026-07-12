// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Presents split 7z archive volumes as one logical seekable channel.
@NotNullByDefault
final class SevenZipVolumeChannel implements SeekableByteChannel {
    /// The opened volume channels in logical order.
    private final @Unmodifiable List<SeekableByteChannel> channels;

    /// The starting logical offset for each volume.
    private final long @Unmodifiable [] offsets;

    /// The size of each volume.
    private final long @Unmodifiable [] sizes;

    /// The total logical archive size.
    private final long size;

    /// The current logical channel position.
    private long position;

    /// Whether this channel is open.
    private boolean open = true;

    /// Creates a logical split-volume channel.
    private SevenZipVolumeChannel(
            ArrayList<SeekableByteChannel> channels,
            long[] offsets,
            long[] sizes,
            long size
    ) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        this.offsets = Objects.requireNonNull(offsets, "offsets").clone();
        this.sizes = Objects.requireNonNull(sizes, "sizes").clone();
        this.size = size;
    }

    /// Opens all available volumes from the source as one logical channel.
    static SeekableByteChannel open(ArkivoVolumeSource volumes) throws IOException {
        Objects.requireNonNull(volumes, "volumes");

        ArrayList<SeekableByteChannel> channels = new ArrayList<>();
        ArrayList<Long> offsets = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();
        long offset = 0L;
        long index = 0L;
        try {
            while (true) {
                SeekableByteChannel channel = volumes.openVolume(index);
                if (channel == null) {
                    break;
                }
                channels.add(channel);
                long volumeSize = channel.size();
                if (volumeSize < 0) {
                    throw new IOException("7z volume size is negative");
                }
                offsets.add(offset);
                sizes.add(volumeSize);
                try {
                    offset = Math.addExact(offset, volumeSize);
                } catch (ArithmeticException exception) {
                    throw new IOException("7z split archive size is too large", exception);
                }
                if (index == Long.MAX_VALUE) {
                    throw new IOException("7z split archive has too many volumes");
                }
                index++;
            }
        } catch (IOException | RuntimeException | Error exception) {
            closeOpenedChannels(channels, exception);
            throw exception;
        }

        if (channels.isEmpty()) {
            throw new IOException("7z volume source did not provide the first volume");
        }

        return new SevenZipVolumeChannel(channels, longs(offsets), longs(sizes), offset);
    }

    /// Reads bytes from the logical split-volume channel.
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
            if (volumeRemaining <= 0) {
                position = offsets[volumeIndex] + sizes[volumeIndex];
                continue;
            }

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
                position = offsets[volumeIndex] + sizes[volumeIndex];
                continue;
            }
            if (read == 0) {
                return totalRead > 0 ? totalRead : 0;
            }
            position += read;
            totalRead += read;
        }
        return totalRead > 0 ? totalRead : -1;
    }

    /// Writes are not supported for split 7z archive channels.
    @Override
    public int write(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        throw new NonWritableChannelException();
    }

    /// Returns the logical channel position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the logical channel position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the total logical archive size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    /// Truncation is not supported for split 7z archive channels.
    @Override
    public SeekableByteChannel truncate(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether this channel is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes all opened volume channels.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        Throwable failure = null;
        for (SeekableByteChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        if (failure != null) {
            throwFailure(failure);
        }
    }

    /// Returns the volume index containing the given logical position.
    private int volumeIndex(long targetPosition) throws IOException {
        int low = 0;
        int high = offsets.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            long start = offsets[middle];
            long end;
            try {
                end = Math.addExact(start, sizes[middle]);
            } catch (ArithmeticException exception) {
                throw new IOException("7z split archive volume offset is too large", exception);
            }
            if (targetPosition < start) {
                high = middle - 1;
            } else if (targetPosition >= end) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return offsets.length - 1;
    }

    /// Requires this channel to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Converts boxed long values to a primitive array.
    private static long[] longs(ArrayList<Long> values) {
        long[] result = new long[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /// Closes channels opened before a construction failure.
    private static void closeOpenedChannels(ArrayList<SeekableByteChannel> channels, Throwable failure) {
        for (SeekableByteChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Throws a collected close failure while preserving its type.
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }
}
