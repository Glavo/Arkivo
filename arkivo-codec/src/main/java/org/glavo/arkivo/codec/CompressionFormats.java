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

    /// Returns the first installed format matching the remaining prefix bytes.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable CompressionFormat detect(ByteBuffer prefix) {
        return CompressionFormatRegistry.load().detect(prefix);
    }

    /// Detects an installed format from a forward-only channel while preserving every source byte for later reads.
    ///
    /// The returned channel must replace the original channel in the caller's read pipeline. Closing it leaves the
    /// original channel open.
    public static CompressionProbeResult probe(ReadableByteChannel source) throws IOException {
        return probe(source, 0L, ChannelOwnership.RETAIN);
    }

    /// Detects an installed format from a forward-only channel with explicit source ownership.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return probe(source, 0L, ownership);
    }

    /// Detects an installed format while retaining at least the requested prefix and applying source ownership.
    ///
    /// The returned channel replays all retained bytes before continuing from the source. A larger prefix allows a
    /// container parser to reject signature collisions without reading the original channel twice. With CLOSE, the
    /// source is closed when the replay channel closes or when probing fails after argument validation.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            long minimumPrefixSize,
            ChannelOwnership ownership
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
            if (ownership == ChannelOwnership.CLOSE) {
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

    /// Opens an encoder for the named format while retaining the target channel.
    public static CompressingWritableByteChannel openEncoder(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return openEncoder(formatName, target, ChannelOwnership.RETAIN);
    }

    /// Opens an encoder for the named format with explicit target ownership.
    ///
    /// After argument validation, CLOSE transfers ownership before codec lookup and setup.
    public static CompressingWritableByteChannel openEncoder(
            String formatName,
            WritableByteChannel target,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).openEncoder(target, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ChannelOwnership.CLOSE) {
                closeAfterOpenFailure(target, exception);
            }
            throw exception;
        }
    }

    /// Opens a decoder for the named format while retaining the source channel.
    public static DecompressingReadableByteChannel openDecoder(
            String formatName,
            ReadableByteChannel source
    ) throws IOException {
        return openDecoder(
                formatName,
                source,
                DecompressionLimits.UNLIMITED,
                ChannelOwnership.RETAIN
        );
    }

    /// Opens a decoder for the named format with explicit source ownership.
    public static DecompressingReadableByteChannel openDecoder(
            String formatName,
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return openDecoder(formatName, source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Opens a limited decoder for the named format while retaining the source channel.
    public static DecompressingReadableByteChannel openDecoder(
            String formatName,
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return openDecoder(formatName, source, limits, ChannelOwnership.RETAIN);
    }

    /// Opens a limited decoder for the named format with explicit source ownership.
    ///
    /// This method bypasses signature detection. After argument validation, CLOSE transfers ownership before codec
    /// lookup and setup.
    public static DecompressingReadableByteChannel openDecoder(
            String formatName,
            ReadableByteChannel source,
            DecompressionLimits limits,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).openDecoder(source, limits, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ChannelOwnership.CLOSE) {
                closeAfterOpenFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects a signed stream and opens an unlimited decoder while retaining the source channel.
    public static DecompressingReadableByteChannel openDecoder(ReadableByteChannel source) throws IOException {
        return openDecoder(source, DecompressionLimits.UNLIMITED, ChannelOwnership.RETAIN);
    }

    /// Detects a signed stream and opens a limited decoder while retaining the source channel.
    public static DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return openDecoder(source, limits, ChannelOwnership.RETAIN);
    }

    /// Detects a signed stream and opens an unlimited decoder with explicit source ownership.
    public static DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return openDecoder(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Detects a signed stream and opens a limited decoder with explicit source ownership.
    ///
    /// The decoder receives every byte consumed by detection. Formats without a reliable signature must be opened by
    /// name. Closing the decoder closes its replay channel; that channel applies the requested ownership to the original
    /// source.
    public static DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            DecompressionLimits limits,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");

        CompressionProbeResult probe = probe(source, ownership);
        ReadableByteChannel replay = probe.channel();
        try {
            @Nullable CompressionFormat format = probe.format();
            if (format == null) {
                throw new IOException("Unrecognized compression format");
            }
            return format.defaultCodec().openDecoder(replay, limits, ChannelOwnership.CLOSE);
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
                     openDecoder(source, limits, ChannelOwnership.RETAIN)) {
            return CodecTransferSupport.decompress(decoder, target);
        }
    }

    /// Opens an owning compression output stream for the named format.
    public static OutputStream compressTo(String formatName, OutputStream target) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        return StreamChannelAdapters.outputStream(
                openEncoder(
                        formatName,
                        StreamChannelAdapters.writableChannel(target),
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Opens an owning unlimited decompression input stream for the named format.
    public static InputStream decompressFrom(String formatName, InputStream source) throws IOException {
        return decompressFrom(formatName, source, DecompressionLimits.UNLIMITED);
    }

    /// Opens an owning limited decompression input stream for the named format.
    public static InputStream decompressFrom(
            String formatName,
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        formatName,
                        StreamChannelAdapters.readableChannel(source),
                        limits,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Detects a signed stream and opens an owning unlimited decompression input stream.
    public static InputStream decompressFrom(InputStream source) throws IOException {
        return decompressFrom(source, DecompressionLimits.UNLIMITED);
    }

    /// Detects a signed stream and opens an owning limited decompression input stream.
    public static InputStream decompressFrom(
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        StreamChannelAdapters.readableChannel(source),
                        limits,
                        ChannelOwnership.CLOSE
                )
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
