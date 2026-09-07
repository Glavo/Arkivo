// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.internal.ArchiveSliceChannel;
import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
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
        CompressionCodec<?> configured = TarCompressionStreams.withReadLimits(seekable, readLimits);
        CompressionCodec.Seekable<?> configuredSeekable = (CompressionCodec.Seekable<?>) configured;
        @Nullable CompressionCodec.Seekable.Index index = configuredSeekable.readIndex(channel);
        if (index != null) {
            long maximum = readLimits.maximumDecodedArchiveSize();
            if (maximum >= 0L && index.uncompressedSize() > maximum) {
                throw new ArkivoReadLimitException(
                        ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE,
                        maximum,
                        index.uncompressedSize(),
                        null
                );
            }
        }
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
                if (exception != cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
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
                SeekableByteChannel channel = ArchiveSliceChannel.open(decoded, offset, size);
                decoded.position(offset);
                return channel;
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    decoded.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    if (exception != cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                throw exception;
            }
        }

        /// Releases no resources because channels own their decoded sessions and the file system owns the source.
        @Override
        public void close() {
        }
    }
}
