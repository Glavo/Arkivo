// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.internal.UDIFImage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/// Opens one flattened Apple UDIF image as a read-only random-access disk.
///
/// An image owns the supplied channel source. Each channel returned by [#openChannel()] or [#openPartition(DMGPartition)]
/// has an independent logical position and must be closed by its caller. Callers must close derived channels before
/// closing the image; closing the image prevents new channels and may invalidate channels backed by a shared physical
/// source.
///
/// The current implementation accepts unencrypted, single-segment flattened UDIF images. It decodes raw, sparse, ADC,
/// zlib, BZip2, and XZ-encoded runs. An unsupported run encoding is reported when bytes from that run are requested.
@NotNullByDefault
public final class DMGImage implements Closeable {
    /// The owned UDIF implementation.
    private final UDIFImage image;

    /// Creates one public image facade.
    private DMGImage(UDIFImage image) {
        this.image = Objects.requireNonNull(image, "image");
    }

    /// Opens a path-backed image with default archive read options.
    ///
    /// @param path the DMG path
    /// @return a new owning image
    /// @throws IOException if the image cannot be opened or parsed
    public static DMGImage open(Path path) throws IOException {
        return open(path, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a path-backed image with common archive read options.
    ///
    /// Independently opened channels are owned by their callers. The image itself retains no open path channel between
    /// operations.
    ///
    /// @param path the DMG path
    /// @param options the resource limits applied while parsing and decoding the image
    /// @return a new owning image
    /// @throws IOException if the image cannot be opened or parsed
    public static DMGImage open(Path path, ArchiveReadOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Path normalized = path.toAbsolutePath().normalize();
        ArkivoSeekableChannelSource source = () ->
                Files.newByteChannel(normalized, StandardOpenOption.READ);
        return open(source, options);
    }

    /// Opens an image over one owned seekable channel with default options.
    ///
    /// The channel's current position becomes logical DMG offset zero. Setup failure closes the channel.
    ///
    /// @param source the channel whose ownership is transferred
    /// @return a new owning image
    /// @throws IOException if source adaptation or image parsing fails
    public static DMGImage open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an image over one owned seekable channel with common archive read options.
    ///
    /// @param source the channel whose current position becomes logical DMG offset zero
    /// @param options the resource limits applied while parsing and decoding the image
    /// @return a new owning image
    /// @throws IOException if source adaptation or image parsing fails
    public static DMGImage open(SeekableByteChannel source, ArchiveReadOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(ArkivoSeekableChannelSource.of(source), options);
    }

    /// Opens an image over an owned repeatable source with default options.
    ///
    /// @param source the source whose ownership is transferred
    /// @return a new owning image
    /// @throws IOException if the image cannot be opened or parsed
    public static DMGImage open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens an image over an owned repeatable source with common archive read options.
    ///
    /// Ownership transfers after argument validation. Setup failure closes the source without hiding the primary
    /// failure.
    ///
    /// @param source the repeatable source whose ownership is transferred
    /// @param options the resource limits applied while parsing and decoding the image
    /// @return a new owning image
    /// @throws IOException if the image cannot be opened or parsed
    public static DMGImage open(ArkivoSeekableChannelSource source, ArchiveReadOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new DMGImage(UDIFImage.open(source, options));
    }

    /// Returns the decoded logical disk size.
    ///
    /// @return the non-negative decoded size in bytes
    public long size() {
        return image.size();
    }

    /// Returns the discovered partitions in on-disk order.
    ///
    /// An unpartitioned supported image is represented by one [DMGPartitionScheme#RAW] partition covering the complete
    /// decoded disk.
    ///
    /// @return the immutable partition list
    public @Unmodifiable List<DMGPartition> partitions() {
        return image.partitions();
    }

    /// Opens an independent read-only channel over the complete decoded disk.
    ///
    /// The returned channel implements [java.nio.channels.InterruptibleChannel] exactly when the independently opened
    /// encoded source channel does.
    ///
    /// @return a new channel positioned at decoded offset zero
    /// @throws IOException if the image is closed or its compressed source cannot be opened
    public SeekableByteChannel openChannel() throws IOException {
        return image.openChannel();
    }

    /// Opens an independent read-only channel over one discovered partition.
    ///
    /// The returned channel implements [java.nio.channels.InterruptibleChannel] exactly when the independently opened
    /// encoded source channel does.
    ///
    /// @param partition a partition equal to the description at its index in [#partitions()]
    /// @return a new channel positioned at partition offset zero
    /// @throws IllegalArgumentException if the partition does not belong to this image layout
    /// @throws IOException if the image is closed or its compressed source cannot be opened
    public SeekableByteChannel openPartition(DMGPartition partition) throws IOException {
        return image.openPartition(Objects.requireNonNull(partition, "partition"));
    }

    /// Returns whether this image remains open for new channels.
    ///
    /// @return {@code true} while the image remains open
    public boolean isOpen() {
        return image.isOpen();
    }

    /// Closes the owned source and prevents new channel creation.
    ///
    /// Repeated calls have no effect after a successful close.
    @Override
    public void close() throws IOException {
        image.close();
    }
}
