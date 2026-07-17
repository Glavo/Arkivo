// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.glavo.arkivo.codec.internal.PrefixReplayReadableByteChannel;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/// Discovers compression formats and uses their default immutable codecs for convenience operations.
@NotNullByDefault
public final class CompressionFormats {
    /// Creates no instances.
    private CompressionFormats() {
    }

    /// Returns all compression formats visible to the current thread's context class loader.
    public static @Unmodifiable List<CompressionFormat> installed() {
        return CompressionFormatRegistry.load().formats();
    }

    /// Returns the first format with the given stable name or alias, ignoring case.
    public static @Nullable CompressionFormat find(String name) {
        return CompressionFormatRegistry.load().find(name);
    }

    /// Returns the installed format with the given stable name or alias, ignoring case.
    ///
    /// @throws IllegalArgumentException when no matching format is installed
    public static CompressionFormat require(String name) {
        return CompressionFormatRegistry.load().require(name);
    }

    /// Returns the matching installed format with the largest preferred probe size.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable CompressionFormat detect(ByteBuffer prefix) {
        return CompressionFormatRegistry.load().detect(prefix);
    }

    /// Detects an installed format from a forward-only channel while preserving every source byte for later reads.
    ///
    /// The result owns a replay channel until `takeChannel()` transfers it into the caller's read pipeline. Closing the
    /// replay channel leaves the original channel open.
    public static CompressionProbeResult probe(ReadableByteChannel source) throws IOException {
        return probe(source, 0L, ResourceOwnership.BORROWED);
    }

    /// Detects an installed format from a forward-only channel with explicit source ownership.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return probe(source, 0L, ownership);
    }

    /// Detects an installed format while retaining at least the requested prefix and applying source ownership.
    ///
    /// The result owns a channel that replays all retained bytes before continuing from the source. A larger prefix
    /// allows a container parser to reject signature collisions without reading the original channel twice. Call
    /// `takeChannel()` to transfer the replay channel; otherwise close the result. With OWNED, the source is closed when
    /// the owning result or transferred replay channel closes, or when probing fails after argument validation.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            long minimumPrefixSize,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        if (minimumPrefixSize < 0L || minimumPrefixSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "minimumPrefixSize must be between zero and " + Integer.MAX_VALUE
            );
        }
        try {
            CompressionFormatRegistry registry = CompressionFormatRegistry.load();
            int probeSize = Math.max(Math.toIntExact(minimumPrefixSize), registry.probeSize());
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = source.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Compression format probe made no progress");
                }
            }
            prefix.flip();
            @Nullable CompressionFormat format = registry.detect(prefix);
            return new CompressionProbeResult(
                    format,
                    prefix.asReadOnlyBuffer(),
                    new PrefixReplayReadableByteChannel(prefix, source, ownership)
            );
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterProbeFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects an installed format from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    public static @Nullable CompressionFormat detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        CompressionFormatRegistry registry = CompressionFormatRegistry.load();
        int probeSize = registry.probeSize();
        long originalPosition = channel.position();
        @Nullable Throwable failure = null;
        try {
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = channel.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Compression format probe made no progress");
                }
            }
            prefix.flip();
            return registry.detect(prefix);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed format from the beginning of the given path.
    public static @Nullable CompressionFormat detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Creates a compressing writable channel for the named format while retaining the target channel.
    public static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return newWritableByteChannel(formatName, target, ResourceOwnership.BORROWED);
    }

    /// Creates a compressing writable channel for the named format with explicit target ownership.
    ///
    /// After argument validation, OWNED transfers ownership before codec lookup and setup.
    public static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            WritableByteChannel target,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).newWritableByteChannel(target, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterOpenFailure(target, exception);
            }
            throw exception;
        }
    }

    /// Creates an unlimited decompressing readable channel for the named format while retaining the source channel.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source
    ) throws IOException {
        return newReadableByteChannel(
                formatName,
                source,
                DecompressionLimits.UNLIMITED,
                ResourceOwnership.BORROWED
        );
    }

    /// Creates an unlimited decompressing readable channel for the named format with explicit source ownership.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return newReadableByteChannel(formatName, source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates a limited decompressing readable channel for the named format while retaining the source channel.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return newReadableByteChannel(formatName, source, limits, ResourceOwnership.BORROWED);
    }

    /// Creates a limited decompressing readable channel for the named format with explicit source ownership.
    ///
    /// This method bypasses signature detection. After argument validation, OWNED transfers ownership before codec
    /// lookup and setup.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source,
            DecompressionLimits limits,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).newReadableByteChannel(source, limits, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterOpenFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects a signed stream and creates an unlimited decompressing channel while retaining the source channel.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source
    ) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates a limited decompressing channel while retaining the source channel.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return newReadableByteChannel(source, limits, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates an unlimited decompressing channel with explicit source ownership.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Detects a signed stream and creates a limited decompressing channel with explicit source ownership.
    ///
    /// The decoder receives every byte consumed by detection. Formats without a reliable signature must be opened by
    /// name. Closing the decoder closes its replay channel; that channel applies the requested ownership to the original
    /// source.
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            DecompressionLimits limits,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");

        CompressionProbeResult probe = probe(source, ownership);
        ReadableByteChannel replay = probe.takeChannel();
        try {
            @Nullable CompressionFormat format = probe.format();
            if (format == null) {
                throw new IOException("Unrecognized compression format");
            }
            return format.defaultCodec().newReadableByteChannel(replay, limits, ResourceOwnership.OWNED);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(replay, exception);
            throw exception;
        }
    }

    /// Compresses all source bytes with the named format while retaining both channels.
    public static CodecTransferResult compress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return requireDefaultCodec(formatName).compress(source, target);
    }

    /// Decompresses all source bytes with the named format while retaining both channels.
    public static CodecTransferResult decompress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(formatName, source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses all source bytes with the named format and operation-scoped limits.
    public static CodecTransferResult decompress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(limits, "limits");
        return requireDefaultCodec(formatName).decompress(source, target, limits);
    }

    /// Detects a signed stream and decompresses all bytes while retaining both channels.
    public static CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Detects a signed stream and decompresses all bytes with operation-scoped limits.
    public static CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(limits, "limits");
        try (DecompressingReadableByteChannel decoder =
                     newReadableByteChannel(source, limits, ResourceOwnership.BORROWED)) {
            return CodecTransferSupport.decompress(decoder, target);
        }
    }

    /// Creates a compressing output stream for the named format while retaining the target stream.
    public static OutputStream newOutputStream(String formatName, OutputStream target) throws IOException {
        return newOutputStream(formatName, target, ResourceOwnership.BORROWED);
    }

    /// Creates a compressing output stream for the named format with explicit target ownership.
    public static OutputStream newOutputStream(
            String formatName,
            OutputStream target,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.outputStream(
                newWritableByteChannel(formatName, StreamChannelAdapters.writableChannel(target), ownership)
        );
    }

    /// Creates an unlimited decompressing input stream for the named format while retaining the source stream.
    public static InputStream newInputStream(String formatName, InputStream source) throws IOException {
        return newInputStream(formatName, source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
    }

    /// Creates a limited decompressing input stream for the named format while retaining the source stream.
    public static InputStream newInputStream(
            String formatName,
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        return newInputStream(formatName, source, limits, ResourceOwnership.BORROWED);
    }

    /// Creates an unlimited decompressing input stream for the named format with explicit source ownership.
    public static InputStream newInputStream(
            String formatName,
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        return newInputStream(formatName, source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates a limited decompressing input stream for the named format with explicit source ownership.
    public static InputStream newInputStream(
            String formatName,
            InputStream source,
            DecompressionLimits limits,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(
                        formatName,
                        StreamChannelAdapters.readableChannel(source),
                        limits,
                        ownership
                )
        );
    }

    /// Detects a signed stream and creates an unlimited decompressing input stream while retaining the source stream.
    public static InputStream newInputStream(InputStream source) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates a limited decompressing input stream while retaining the source stream.
    public static InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        return newInputStream(source, limits, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates an unlimited decompressing input stream with explicit source ownership.
    public static InputStream newInputStream(
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Detects a signed stream and creates a limited decompressing input stream with explicit source ownership.
    public static InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(StreamChannelAdapters.readableChannel(source), limits, ownership)
        );
    }

    /// Returns the named format's installed default codec.
    private static CompressionCodec requireDefaultCodec(String formatName) throws IOException {
        @Nullable CompressionFormat format = find(formatName);
        if (format == null) {
            throw new IOException("Unknown compression format: " + formatName);
        }
        return format.defaultCodec();
    }

    /// Closes one still-open channel after context setup fails without hiding the primary failure.
    private static void closeAfterOpenFailure(Channel channel, Throwable failure) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Closes an owned source after probe failure without hiding the primary exception.
    private static void closeAfterProbeFailure(ReadableByteChannel source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
    }

    /// Restores a probed channel position without hiding an earlier probe failure.
    private static void restorePosition(
            SeekableByteChannel channel,
            long position,
            @Nullable Throwable failure
    ) throws IOException {
        try {
            channel.position(position);
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }
}
