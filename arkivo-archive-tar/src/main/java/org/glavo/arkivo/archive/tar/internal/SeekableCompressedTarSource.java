// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/// Reuses one parsed outer-compression index for TAR scanning and entry-body slices.
@NotNullByDefault
final class SeekableCompressedTarSource {
    /// The repeatable complete compressed archive source borrowed from the file system.
    private final ArkivoSeekableChannelSource source;

    /// The immutable parsed compression index.
    private final CompressionCodec.Seekable.Index index;

    /// Parses an available index from a borrowed channel that already represents this archive source.
    private static @Nullable SeekableCompressedTarSource open(
            ArkivoSeekableChannelSource source,
            SeekableByteChannel channel,
            CompressionCodec.Seekable<?> seekable,
            ArchiveReadLimits readLimits
    ) throws IOException {
        channel.position(0L);
        @Nullable CompressionCodec.Seekable.Index index = seekable.readIndex(
                channel,
                TarCompressionStreams.decodingOptions(readLimits)
        );
        return index != null ? new SeekableCompressedTarSource(source, index) : null;
    }

    /// Detects and parses an index from a borrowed already-open archive channel.
    static @Nullable SeekableCompressedTarSource open(
            ArkivoSeekableChannelSource source,
            SeekableByteChannel channel,
            @Nullable CompressionCodec<?> compressionCodec,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(readLimits, "readLimits");
        if (!(compressionCodec instanceof CompressionCodec.Seekable<?> seekable)
                || !seekable.supportsSeekableEncoding()) {
            return null;
        }
        return open(source, channel, seekable, readLimits);
    }

    /// Creates reusable access from a borrowed source and immutable parsed index.
    private SeekableCompressedTarSource(
            ArkivoSeekableChannelSource source,
            CompressionCodec.Seekable.Index index
    ) {
        this.source = source;
        this.index = index;
    }

    /// Opens an owning decoded stream whose skip operations reposition the logical channel.
    InputStream newInputStream() throws IOException {
        return StreamChannelAdapters.inputStream(newReadableByteChannel());
    }

    /// Creates a lightweight read-only stored-content view over one contiguous decoded TAR body.
    ArkivoStoredContent newStoredContent(long offset, long size) {
        if (offset < 0L || size < 0L || offset > index.uncompressedSize() - size) {
            throw new IllegalArgumentException("TAR content slice is outside the decoded archive");
        }
        return new SliceStoredContent(this, offset, size);
    }

    /// Opens a new owning logical decoded channel over the complete TAR byte stream.
    private SeekableByteChannel newReadableByteChannel() throws IOException {
        SeekableByteChannel encoded = source.openChannel();
        try {
            encoded.position(0L);
            return index.newReadableByteChannel(encoded, ResourceOwnership.OWNED);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                encoded.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /// Represents one read-only contiguous decoded TAR body without staging its bytes.
    ///
    /// @param archive the reusable decoded archive source
    /// @param offset the logical decoded offset of the first body byte
    /// @param size the fixed logical body size
    @NotNullByDefault
    private record SliceStoredContent(
            SeekableCompressedTarSource archive,
            long offset,
            long size
    ) implements ArkivoStoredContent {
        /// Validates a source-backed body slice.
        private SliceStoredContent {
            Objects.requireNonNull(archive, "archive");
        }

        /// Opens a new read-only random-access channel over this body slice.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            Objects.requireNonNull(options, "options");
            for (OpenOption option : options) {
                if (option != StandardOpenOption.READ) {
                    throw new UnsupportedOperationException("Source-backed TAR content is read-only");
                }
            }
            SeekableByteChannel decoded = archive.newReadableByteChannel();
            try {
                SliceChannel channel = decoded instanceof InterruptibleChannel
                        ? new InterruptibleSliceChannel(decoded, offset, size)
                        : new SliceChannel(decoded, offset, size);
                decoded.position(offset);
                return channel;
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    decoded.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }

        /// Releases no resources because channels own their decoded sessions and the file system owns the source.
        @Override
        public void close() {
        }
    }

    /// Restricts one owning decoded archive channel to a logical body range.
    @NotNullByDefault
    private static class SliceChannel implements SeekableByteChannel {
        /// The owning complete decoded archive channel.
        private final SeekableByteChannel decoded;

        /// The logical decoded body origin.
        private final long origin;

        /// The fixed logical body size.
        private final long size;

        /// The current body-relative position.
        private long position;

        /// Whether this slice remains open.
        private boolean open = true;

        /// Creates a body slice around an owning decoded channel.
        private SliceChannel(SeekableByteChannel decoded, long origin, long size) {
            this.decoded = decoded;
            this.origin = origin;
            this.size = size;
        }

        /// Reads no more than the remaining body range.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            int count = (int) Math.min(target.remaining(), size - position);
            ByteBuffer boundedTarget = target.duplicate();
            boundedTarget.limit(boundedTarget.position() + count);
            decoded.position(origin + position);
            int read = decoded.read(boundedTarget);
            if (read < 0) {
                throw new EOFException("Decoded TAR source ended inside an indexed entry body");
            }
            if (read > 0) {
                target.position(target.position() + read);
                position += read;
            }
            return read;
        }

        /// Rejects writes because source-backed archive bodies are read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current body-relative position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the body-relative position without decoding intervening bytes.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            ensureOpen();
            position = newPosition;
            return this;
        }

        /// Returns the fixed logical body size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Rejects truncation because source-backed archive bodies have a fixed read-only extent.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this slice and its decoded channel remain open.
        @Override
        public boolean isOpen() {
            return open && decoded.isOpen();
        }

        /// Closes the owning decoded archive channel.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            decoded.close();
            open = false;
        }

        /// Requires this slice and its decoded channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Marks a slice as interruptible when its decoded channel preserves that capability.
    @NotNullByDefault
    private static final class InterruptibleSliceChannel extends SliceChannel implements InterruptibleChannel {
        /// Creates an interruptible body slice.
        private InterruptibleSliceChannel(SeekableByteChannel decoded, long origin, long size) {
            super(decoded, origin, size);
        }
    }
}
