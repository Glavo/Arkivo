// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.DMGPartition;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Objects;

/// Implements an owned, repeatably openable flattened UDIF image.
@NotNullByDefault
public final class UDIFImage implements Closeable {
    /// The repeatable encoded source owned by this image.
    private final ArkivoSeekableChannelSource source;

    /// The immutable decoded block layout.
    private final UDIFLayout layout;

    /// The immutable operation-wide decoding limits.
    private final ArchiveReadLimits limits;

    /// The discovered immutable partition list.
    private final @Unmodifiable List<DMGPartition> partitions;

    /// Whether the image remains open for new channels.
    private boolean open = true;

    /// Creates a parsed image.
    private UDIFImage(
            ArkivoSeekableChannelSource source,
            UDIFLayout layout,
            ArchiveReadLimits limits,
            List<DMGPartition> partitions
    ) {
        this.source = source;
        this.layout = layout;
        this.limits = limits;
        this.partitions = List.copyOf(partitions);
    }

    /// Opens and validates one owned repeatable UDIF source.
    ///
    /// @param source the encoded source whose ownership is transferred
    /// @param options the common read options
    /// @return a parsed owning image
    /// @throws IOException if the source is malformed, unsupported, or exceeds a configured limit
    public static UDIFImage open(ArkivoSeekableChannelSource source, ArchiveReadOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(source, options, ArkivoReadLimitTracker.fromLimits(options.limits()));
    }

    /// Opens and validates one owned repeatable UDIF source with an existing operation-wide limit tracker.
    ///
    /// @param source the encoded source whose ownership is transferred
    /// @param options the common read options
    /// @param tracker the tracker shared by every metadata layer in the enclosing operation
    /// @return a parsed owning image
    /// @throws IOException if the source is malformed, unsupported, or exceeds a configured limit
    static UDIFImage open(
            ArkivoSeekableChannelSource source,
            ArchiveReadOptions options,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(tracker, "tracker");
        @Nullable Throwable failure = null;
        try {
            ArchiveReadLimits limits = options.limits();
            UDIFLayout layout;
            try (SeekableByteChannel encoded = source.openChannel()) {
                long sourceSize = encoded.size();
                if (sourceSize < UDIFConstants.TRAILER_SIZE) {
                    throw new IOException("Disk image is too short to contain a UDIF trailer");
                }
                byte[] trailerBytes = ChannelIO.readBytes(
                        encoded,
                        sourceSize - UDIFConstants.TRAILER_SIZE,
                        UDIFConstants.TRAILER_SIZE
                );
                UDIFTrailer trailer = UDIFTrailer.parse(trailerBytes, sourceSize);
                layout = UDIFLayout.read(encoded, trailer, limits, tracker);
            }

            List<DMGPartition> partitions;
            try (SeekableByteChannel encoded = source.openChannel();
                 SeekableByteChannel disk = UDIFBlockChannel.open(encoded, layout, limits)) {
                partitions = DMGPartitionTables.read(disk, tracker);
            }
            return new UDIFImage(source, layout, limits, partitions);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null) {
                try {
                    source.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    if (closeException != failure) {
                        failure.addSuppressed(closeException);
                    }
                }
            }
        }
    }

    /// Returns the decoded disk size.
    ///
    /// @return the decoded byte count
    public long size() {
        return layout.size();
    }

    /// Returns the discovered immutable partitions.
    ///
    /// @return partitions in table order
    public @Unmodifiable List<DMGPartition> partitions() {
        return partitions;
    }

    /// Opens a new decoded whole-disk channel.
    ///
    /// @return an independently positioned owning channel
    /// @throws IOException if the image is closed or the encoded source cannot be opened
    public synchronized SeekableByteChannel openChannel() throws IOException {
        ensureOpen();
        SeekableByteChannel encoded = source.openChannel();
        try {
            return UDIFBlockChannel.open(encoded, layout, limits);
        } catch (RuntimeException | Error exception) {
            try {
                encoded.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a new decoded channel restricted to one discovered partition.
    ///
    /// @param partition a partition belonging to this layout
    /// @return an independently positioned partition channel
    /// @throws IllegalArgumentException if the partition is not the value at its declared index
    /// @throws IOException if the image is closed or the encoded source cannot be opened
    public SeekableByteChannel openPartition(DMGPartition partition) throws IOException {
        Objects.requireNonNull(partition, "partition");
        int index = partition.index();
        if (index < 0 || index >= partitions.size() || !partitions.get(index).equals(partition)) {
            throw new IllegalArgumentException("partition does not belong to this DMG image");
        }
        return SlicedSeekableByteChannel.open(openChannel(), partition.offset(), partition.size());
    }

    /// Returns whether the image remains open.
    ///
    /// @return {@code true} before close succeeds
    public synchronized boolean isOpen() {
        return open;
    }

    /// Closes the repeatable encoded source.
    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        source.close();
        open = false;
    }

    /// Rejects opening channels after close.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
